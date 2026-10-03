param(
    [switch]$CheckOnly
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
    Write-Error "ERROR: $Message"
    exit 1
}

function TopicMismatch([string]$Message) {
    Write-Error "ERROR: $Message"
    Write-Error "Topic validation failed. Fix mismatches manually; this script never deletes or alters existing topics."
    exit 1
}

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$LocalDevDir = Split-Path -Parent $ScriptDir
$ComposeFile = Join-Path $LocalDevDir "compose.yaml"
$EnvFile = Join-Path $LocalDevDir ".env"
$ManifestFile = Join-Path $ScriptDir "topics.json"
$BootstrapServer = "kafka:9092"

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

function Invoke-KafkaTopicsCapture {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$CommandArgs)
    Invoke-ComposeCapture exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server $BootstrapServer @CommandArgs
}

function Invoke-KafkaTopicsChecked {
    param([Parameter(ValueFromRemainingArguments=$true)][string[]]$CommandArgs)
    $Result = Invoke-KafkaTopicsCapture @CommandArgs
    if ($Result.ExitCode -ne 0) { exit $Result.ExitCode }
    return $Result.Output
}

function Assert-PositiveInteger($Value, [string]$Path) {
    if (($Value -is [bool]) -or -not (($Value -is [int]) -or ($Value -is [long])) -or $Value -lt 1) {
        Fail "$Path must be a positive integer"
    }
}

function Get-ConfigMap($ConfigObject) {
    $Map = @{}
    foreach ($Property in $ConfigObject.PSObject.Properties) {
        if ($Property.Name -notmatch '^[A-Za-z0-9._-]+$') {
            Fail "invalid config key: $($Property.Name)"
        }
        if (-not ($Property.Value -is [string]) -or $Property.Value -eq '' -or $Property.Value -match '[,;\t\r\n]') {
            Fail "$($Property.Name): config value must be a non-empty string without comma, semicolon, tab, or newline"
        }
        $Map[$Property.Name] = [string]$Property.Value
    }
    return $Map
}

function Test-TopicExists([string[]]$TopicList, [string]$Name) {
    foreach ($TopicName in $TopicList) {
        if ($TopicName -eq $Name) { return $true }
    }
    return $false
}

function Validate-Describe($Topic, [string[]]$Describe) {
    if ($Describe.Count -lt 1) { TopicMismatch "$($Topic.Name) describe output is empty" }
    $Summary = [string]$Describe[0]
    $PartitionMatch = [regex]::Match($Summary, 'PartitionCount: (\d+)')
    $RfMatch = [regex]::Match($Summary, 'ReplicationFactor: (\d+)')
    $ActualPartitions = if ($PartitionMatch.Success) { $PartitionMatch.Groups[1].Value } else { '' }
    $ActualRf = if ($RfMatch.Success) { $RfMatch.Groups[1].Value } else { '' }
    if ($ActualPartitions -ne [string]$Topic.Partitions) {
        TopicMismatch "$($Topic.Name) partitions=$ActualPartitions expected=$($Topic.Partitions)"
    }
    if ($ActualRf -ne [string]$Topic.ReplicationFactor) {
        TopicMismatch "$($Topic.Name) replicationFactor=$ActualRf expected=$($Topic.ReplicationFactor)"
    }

    $ActualConfigs = @{}
    $ConfigMarker = 'Configs: '
    $Index = $Summary.IndexOf($ConfigMarker)
    if ($Index -ge 0) {
        $ConfigText = $Summary.Substring($Index + $ConfigMarker.Length).Trim()
        foreach ($Pair in $ConfigText.Split(',')) {
            $Trimmed = $Pair.Trim()
            if ($Trimmed.Contains('=')) {
                $Parts = $Trimmed.Split(@('='), 2)
                $ActualConfigs[$Parts[0].Trim()] = $Parts[1].Trim()
            }
        }
    }

    foreach ($Key in $Topic.Configs.Keys) {
        $ActualValue = $ActualConfigs[$Key]
        $ExpectedValue = $Topic.Configs[$Key]
        if ($ActualValue -ne $ExpectedValue) {
            TopicMismatch "$($Topic.Name) $Key='$ActualValue' expected='$ExpectedValue'"
        }
    }

    $Describe | ForEach-Object { Write-Host $_ }
}

Write-Host "==> Validating manifest: $ManifestFile"
$Manifest = Get-Content -Raw -Path $ManifestFile | ConvertFrom-Json
$Defaults = $Manifest.defaults
$RawTopics = @($Manifest.topics)
Assert-PositiveInteger $Defaults.partitions "defaults.partitions"
Assert-PositiveInteger $Defaults.replicationFactor "defaults.replicationFactor"
if ($null -eq $Defaults.configs) { Fail "defaults.configs must be a non-empty object" }
$DefaultConfigs = Get-ConfigMap $Defaults.configs
if ($DefaultConfigs.Count -lt 1) { Fail "defaults.configs must be a non-empty object" }
foreach ($RequiredConfig in @('cleanup.policy', 'retention.ms')) {
    if (-not $DefaultConfigs.ContainsKey($RequiredConfig)) {
        Fail "defaults.configs.$RequiredConfig is required"
    }
}
if ($RawTopics.Count -lt 1) { Fail "topics must be a non-empty list" }

$Seen = @{}
$Topics = @()
foreach ($Topic in $RawTopics) {
    $Name = [string]$Topic.name
    if ($Name -eq '.' -or $Name -eq '..' -or [Text.Encoding]::UTF8.GetByteCount($Name) -gt 249 -or $Name -notmatch '^[A-Za-z0-9._-]+$') {
        Fail "invalid topic name: $Name"
    }
    if ($Seen.ContainsKey($Name)) { Fail "duplicate topic name: $Name" }
    $Seen[$Name] = $true
    if (@('proposed', 'demo') -notcontains $Topic.status) { Fail "${Name}: status must be proposed or demo" }
    foreach ($Field in @('owner', 'key', 'description')) {
        if (-not ($Topic.$Field -is [string]) -or -not $Topic.$Field.Trim()) { Fail "${Name}: $Field is required" }
    }
    $TopicConfigs = @{}
    foreach ($Key in $DefaultConfigs.Keys) { $TopicConfigs[$Key] = $DefaultConfigs[$Key] }
    $Topics += [pscustomobject]@{
        Name = $Name
        Partitions = [int64]$Defaults.partitions
        ReplicationFactor = [int64]$Defaults.replicationFactor
        Configs = $TopicConfigs
        Owner = [string]$Topic.owner
        Key = [string]$Topic.key
        Status = [string]$Topic.status
    }
}

if (-not (Test-Path $EnvFile)) {
    Fail "Missing $EnvFile. Copy infra/local-dev/.env.example to .env and set local values first."
}

Write-Host "==> Preflight: kafka container must be healthy and reachable on $BootstrapServer"
$KafkaContainerId = (Invoke-ComposeChecked ps -q kafka | Select-Object -First 1)
if (-not $KafkaContainerId) { Fail "Kafka container is not running for $ComposeFile" }
$KafkaHealth = & docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $KafkaContainerId
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
if ($KafkaHealth -ne 'healthy') { Fail "Kafka container health is $KafkaHealth, expected healthy" }
& docker compose --env-file $EnvFile -f $ComposeFile exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server $BootstrapServer | Out-Null
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$ListResult = Invoke-KafkaTopicsCapture --list
if ($ListResult.ExitCode -ne 0) { Fail "Unable to list Kafka topics from $BootstrapServer" }
$TopicList = @($ListResult.Output)

foreach ($Topic in $Topics) {
    Write-Host "==> Topic: $($Topic.Name) ($($Topic.Status), owner=$($Topic.Owner), key=$($Topic.Key))"
    if (Test-TopicExists $TopicList $Topic.Name) {
        $DescribeResult = Invoke-KafkaTopicsCapture --describe --topic $Topic.Name
        if ($DescribeResult.ExitCode -ne 0) { Fail "Unable to describe existing topic $($Topic.Name)" }
        Validate-Describe $Topic ([string[]]@($DescribeResult.Output))
    } else {
        if ($CheckOnly) {
            TopicMismatch "$($Topic.Name) is missing (check-only mode will not create it)"
        }
        Write-Host "Creating $($Topic.Name)"
        $CreateArgs = @('--create', '--topic', $Topic.Name, '--partitions', [string]$Topic.Partitions, '--replication-factor', [string]$Topic.ReplicationFactor)
        foreach ($Key in $Topic.Configs.Keys) {
            $CreateArgs += @('--config', "$Key=$($Topic.Configs[$Key])")
        }
        Invoke-KafkaTopicsChecked @CreateArgs | Out-Null
        $DescribeResult = Invoke-KafkaTopicsCapture --describe --topic $Topic.Name
        if ($DescribeResult.ExitCode -ne 0) { Fail "Created $($Topic.Name) but could not describe it" }
        Validate-Describe $Topic ([string[]]@($DescribeResult.Output))
    }
}

Write-Host "Done. Topics match $ManifestFile."
