param([switch]$SkipFrontend)
$ErrorActionPreference = 'Stop'
# Windows PowerShell 5.1 otherwise converts Vietnamese SQL literals to ASCII on native stdin.
$OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$demoStateDir = Join-Path $env:LOCALAPPDATA 'NexusDemo'
New-Item -ItemType Directory -Path $demoStateDir -Force | Out-Null
$demoProcessFile = Join-Path $demoStateDir 'processes.json'
$demoProcesses = @()
if (Test-Path -LiteralPath $demoProcessFile) { $demoProcesses = @(Get-Content -Raw -LiteralPath $demoProcessFile | ConvertFrom-Json) }
function Test-DemoPort([int]$Port) {
    $demoClient = New-Object Net.Sockets.TcpClient
    try { return $demoClient.ConnectAsync('127.0.0.1', $Port).Wait(300) -and $demoClient.Connected }
    catch { return $false }
    finally { $demoClient.Dispose() }
}
function Wait-DemoPort([int]$Port, [string]$Name) {
    $demoDeadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $demoDeadline) {
        if (Test-DemoPort $Port) { Write-Host "$Name ready on $Port"; return }
        Start-Sleep -Seconds 2
    }
    throw "$Name did not start. See $demoStateDir/$Name.out.log"
}
function Start-DemoService([string]$Name, [int]$Port, [string]$DatabaseName = '') {
    if (Test-DemoPort $Port) {
        if ($DatabaseName -or $Name -eq 'frontend') {
            $demoOwned = @($script:demoProcesses | Where-Object { $_.name -eq $Name -and $_.database -eq $DatabaseName -and $_.port -eq $Port })
            $demoOwnershipValid = $false
            if ($demoOwned.Count -eq 1) {
                $demoWrapper = Get-CimInstance Win32_Process -Filter "ProcessId=$($demoOwned[0].pid)"
                $demoHelper = (Join-Path $PSScriptRoot 'Invoke-DemoService.ps1').ToLowerInvariant()
                if ($demoWrapper -and $demoWrapper.Name -eq 'powershell.exe' -and $demoWrapper.CommandLine.ToLowerInvariant().Contains($demoHelper)) {
                    $demoLiveProcess = Get-Process -Id $demoWrapper.ProcessId
                    $demoStartDifference = [Math]::Abs(($demoLiveProcess.StartTime.ToUniversalTime() - ([datetime]$demoOwned[0].started).ToUniversalTime()).TotalSeconds)
                    if ($demoStartDifference -lt 5) {
                        $demoAllProcesses = @(Get-CimInstance Win32_Process)
                        $demoFamily = New-Object 'System.Collections.Generic.HashSet[int]'
                        [void]$demoFamily.Add([int]$demoWrapper.ProcessId)
                        do {
                            $demoFamilyCount = $demoFamily.Count
                            foreach ($demoChild in $demoAllProcesses) {
                                if ($demoFamily.Contains([int]$demoChild.ParentProcessId)) { [void]$demoFamily.Add([int]$demoChild.ProcessId) }
                            }
                        } while ($demoFamily.Count -gt $demoFamilyCount)
                        $demoListeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
                        $demoOwnershipValid = $demoListeners.Count -gt 0 -and @($demoListeners | Where-Object { !$demoFamily.Contains([int]$_.OwningProcess) }).Count -eq 0
                    }
                }
            }
            if (!$demoOwnershipValid) { throw "Port $Port is already occupied by an unmanaged $Name. Stop that service before starting the demo databases." }
        }
        Write-Host "Reusing $Name on $Port"
        return
    }
    $demoArguments = @('-NoProfile','-ExecutionPolicy','Bypass','-File', ('"' + (Join-Path $PSScriptRoot 'Invoke-DemoService.ps1') + '"'), '-Service', $Name)
    if ($DatabaseName) { $demoArguments += @('-DatabaseName', $DatabaseName) }
    $demoProcess = Start-Process powershell.exe -ArgumentList $demoArguments -WorkingDirectory $repoRoot -WindowStyle Hidden -RedirectStandardOutput (Join-Path $demoStateDir "$Name.out.log") -RedirectStandardError (Join-Path $demoStateDir "$Name.err.log") -PassThru
    $script:demoProcesses = @($script:demoProcesses | Where-Object name -ne $Name) + [pscustomobject]@{ name=$Name; pid=$demoProcess.Id; port=$Port; database=$DatabaseName; started=(Get-Date -Format o) }
    $script:demoProcesses | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $demoProcessFile -Encoding UTF8
    Wait-DemoPort $Port $Name
}
$demoDbUser = $null
foreach ($demoLine in Get-Content -LiteralPath (Join-Path $PSScriptRoot '../local-dev/.env')) {
    if ($demoLine -match '^DB_USERNAME=(.+)$') { $demoDbUser = $Matches[1].Trim() }
}
if (!$demoDbUser) { throw 'DB_USERNAME missing in infra/local-dev/.env' }
& docker exec nexus-postgres pg_isready -U $demoDbUser -d postgres | Out-Null
if ($LASTEXITCODE -ne 0) { throw 'Start Docker infrastructure first; nexus-postgres must be healthy.' }
foreach ($demoDb in @('nexus_demo_user_db','nexus_demo_catalog_db','nexus_demo_commerce_db')) {
    $demoExists = & docker exec nexus-postgres psql -U $demoDbUser -d postgres -Atc "SELECT 1 FROM pg_database WHERE datname = '$demoDb'"
    if ($LASTEXITCODE -ne 0) { throw 'Could not inspect demo databases' }
    if ($demoExists -ne '1') {
        & docker exec nexus-postgres psql -U $demoDbUser -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE $demoDb"
        if ($LASTEXITCODE -ne 0) { throw "Could not create $demoDb" }
    }
}
Push-Location (Join-Path $repoRoot 'be-project-nexus/lib')
try { & .\mvnw.cmd '-Dmaven.test.skip=true' install; if ($LASTEXITCODE -ne 0) { throw 'Shared libraries build failed' } }
finally { Pop-Location }
Start-DemoService 'config-service' 8888
Start-DemoService 'discovery-service' 8761
Start-DemoService 'api-gateway' 8080
Start-DemoService 'user-service' 8081 'nexus_demo_user_db'
Start-DemoService 'catalog-service' 8082 'nexus_demo_catalog_db'
Start-DemoService 'commerce-service' 8083 'nexus_demo_commerce_db'
foreach ($demoSeed in @(
    @{ file='seed-users.sql'; database='nexus_demo_user_db' },
    @{ file='seed-catalog.sql'; database='nexus_demo_catalog_db' },
    @{ file='seed-catalog-images.sql'; database='nexus_demo_catalog_db' },
    @{ file='seed-commerce.sql'; database='nexus_demo_commerce_db' }
)) {
    Get-Content -Raw -Encoding UTF8 -LiteralPath (Join-Path $PSScriptRoot $demoSeed.file) | & docker exec -i nexus-postgres psql -U $demoDbUser -d $demoSeed.database -v ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) { throw "Seed failed: $($demoSeed.file)" }
}
if (!$SkipFrontend) { Start-DemoService 'frontend' 3000 }
if ($SkipFrontend) { Write-Host 'Demo API ready: http://localhost:8080 (frontend skipped)' }
else { Write-Host 'Demo ready: http://127.0.0.1:3000; API: http://localhost:8080' }
Write-Host "Logs and managed process IDs: $demoStateDir"
