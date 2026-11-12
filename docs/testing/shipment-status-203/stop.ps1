param([switch]$KeepContainers)

$ErrorActionPreference = 'Stop'
$statePath = Join-Path $PSScriptRoot 'evidence/runtime-state.json'
if (Test-Path -LiteralPath $statePath) {
    $state = Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json
    foreach ($processId in @($state.servicePid, $state.companionPid)) {
        $processInfo = Get-CimInstance Win32_Process -Filter "ProcessId=$processId" -ErrorAction SilentlyContinue
        $belongsToKit = $processInfo -and $processInfo.CommandLine -and $processInfo.CommandLine.Replace('/', '\').Contains($PSScriptRoot)
        if ($belongsToKit -and ($processInfo.CommandLine -like '*shipment-service-0.0.1-SNAPSHOT.jar*' -or $processInfo.CommandLine -like '*shipment-status-203*companion.mjs*')) {
            Stop-Process -Id $processId -ErrorAction SilentlyContinue
        }
    }
    Remove-Item -LiteralPath $statePath
}
if (-not $KeepContainers) {
    & docker compose -f (Join-Path $PSScriptRoot 'compose.yaml') stop
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop the isolated Docker stack.' }
}
