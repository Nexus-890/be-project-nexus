param(
    [string]$EnvFile,
    [switch]$Help
)

if ($Help) {
    Write-Output 'Usage: .\keycloak\seed-users.ps1 [-EnvFile PATH]'
    Write-Output 'Set the three imported demo users'' passwords from local-dev/.env.'
    exit 0
}

if (-not $EnvFile) {
    $EnvFile = Join-Path $PSScriptRoot '..\.env'
}
if (-not (Test-Path -LiteralPath $EnvFile -PathType Leaf)) {
    [Console]::Error.WriteLine("ERROR: missing env file: $EnvFile")
    exit 1
}

# Pass the file to Compose; never evaluate dotenv contents as PowerShell code.
$EnvFile = (Resolve-Path -LiteralPath $EnvFile).Path
$ComposeFile = Join-Path $PSScriptRoot '..\compose.yaml'
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    [Console]::Error.WriteLine('ERROR: Docker CLI is not installed or is not on PATH')
    exit 1
}
& docker compose --env-file $EnvFile -f $ComposeFile run --rm --no-deps -T auth-seed
exit $LASTEXITCODE
