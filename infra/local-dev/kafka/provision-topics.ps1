param(
    [switch]$CheckOnly
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$LocalDevDir = Split-Path -Parent $ScriptDir
$ComposeFile = Join-Path $LocalDevDir "compose.yaml"
$EnvFile = Join-Path $LocalDevDir ".env"

function Fail([string]$Message) {
    Write-Error "ERROR: $Message"
    exit 1
}

function Invoke-ComposeCapture {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$CommandArgs)
    $Output = & docker compose --env-file $EnvFile -f $ComposeFile @CommandArgs
    [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = @($Output) }
}

function Invoke-ComposeChecked {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$CommandArgs)
    $Result = Invoke-ComposeCapture @CommandArgs
    if ($Result.ExitCode -ne 0) { exit $Result.ExitCode }
    return $Result.Output
}

if (-not (Test-Path $EnvFile)) {
    Fail "missing $EnvFile. Copy infra/local-dev/.env.example to .env first."
}

$KafkaContainerId = (Invoke-ComposeChecked ps -q kafka | Select-Object -First 1)
if (-not $KafkaContainerId) { Fail "kafka container is not running" }
$KafkaHealth = & docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $KafkaContainerId
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($KafkaHealth -ne 'healthy') { Fail "kafka container health is $KafkaHealth, expected healthy" }

$RunArgs = @('run', '--rm', '--no-deps', 'kafka-init')
if ($CheckOnly) { $RunArgs += '--check-only' }
Invoke-ComposeChecked @RunArgs | ForEach-Object { Write-Host $_ }
