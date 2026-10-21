$ErrorActionPreference = 'Stop'
$demoStateDir = Join-Path $env:LOCALAPPDATA 'NexusDemo'
$demoProcessFile = Join-Path $demoStateDir 'processes.json'
if (!(Test-Path -LiteralPath $demoProcessFile)) { Write-Host 'No managed demo processes'; return }
$demoHelper = (Join-Path $PSScriptRoot 'Invoke-DemoService.ps1').ToLowerInvariant()
$demoEntries = @(Get-Content -Raw -LiteralPath $demoProcessFile | ConvertFrom-Json)
foreach ($demoEntry in $demoEntries) {
    $demoProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($demoEntry.pid)"
    if (!$demoProcess) { continue }
    $demoStartDifference = [Math]::Abs(((Get-Process -Id $demoEntry.pid).StartTime.ToUniversalTime() - ([datetime]$demoEntry.started).ToUniversalTime()).TotalSeconds)
    if ($demoProcess.Name -ne 'powershell.exe' -or !$demoProcess.CommandLine.ToLowerInvariant().Contains($demoHelper) -or $demoStartDifference -ge 5) {
        Write-Warning "Skipping PID $($demoEntry.pid): it is no longer the managed $($demoEntry.name) process"
        continue
    }
    & taskkill.exe /PID $demoEntry.pid /T /F
    if ($LASTEXITCODE -ne 0) { throw "Could not stop $($demoEntry.name)" }
}
Write-Host 'Managed demo processes stopped. Demo databases and Docker infrastructure are preserved.'
