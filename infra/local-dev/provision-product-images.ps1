param(
    [string]$EnvFile = (Join-Path $PSScriptRoot '.env'),
    [string]$ManifestFile = (Join-Path $PSScriptRoot 'seed-images/manifest.json'),
    [string]$ImageDirectory = (Join-Path $PSScriptRoot 'seed-images'),
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
if (Test-Path -LiteralPath $EnvFile) {
    foreach ($line in Get-Content -LiteralPath $EnvFile) {
        if ($line -match '^(SUPABASE_URL|SUPABASE_SERVICE_KEY|SUPABASE_BUCKET)=(.*)$') {
            if (-not [Environment]::GetEnvironmentVariable($Matches[1], 'Process')) {
                [Environment]::SetEnvironmentVariable($Matches[1], $Matches[2].Trim().Trim('"').Trim("'"), 'Process')
            }
        }
    }
}
$seedBase = ([string]$env:SUPABASE_URL).TrimEnd('/')
if ($seedBase -notmatch '^https://[a-z0-9-]+\.supabase\.co$' -or (-not $VerifyOnly -and -not $env:SUPABASE_SERVICE_KEY)) {
    throw 'Set a valid SUPABASE_URL and SUPABASE_SERVICE_KEY before provisioning.'
}
$seedBucket = if ($env:SUPABASE_BUCKET) { $env:SUPABASE_BUCKET } else { 'product-images' }
if ($seedBucket -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid bucket name.' }
$seedImageRoot = [IO.Path]::GetFullPath($ImageDirectory).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
$seedEntries = @(Get-Content -LiteralPath $ManifestFile -Raw | ConvertFrom-Json)
if (-not $seedEntries.Count) { throw 'The image manifest is empty.' }
$seedKeys = [Collections.Generic.HashSet[string]]::new()
foreach ($entry in $seedEntries) {
    if ([string]$entry.productId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' -or
        [string]$entry.imageId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') { throw 'Every manifest entry must contain valid UUIDs.' }
    if ([string]$entry.file -notmatch '^[a-zA-Z0-9_.-]+\.(jpg|jpeg)$') { throw 'Manifest filenames must be JPEG images directly inside ImageDirectory.' }
    $seedFile = [IO.Path]::GetFullPath((Join-Path $seedImageRoot ([string]$entry.file)))
    if (-not $seedFile.StartsWith($seedImageRoot, [StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath $seedFile -PathType Leaf)) { throw 'Manifest image files must exist inside ImageDirectory.' }
    if (-not $seedKeys.Add("$($entry.productId)/$($entry.imageId)")) { throw 'Duplicate product/image pair in the manifest.' }
}
$seedHeaders = @{ Authorization = "Bearer $env:SUPABASE_SERVICE_KEY"; apikey = $env:SUPABASE_SERVICE_KEY; 'x-upsert' = 'false' }
$seedClient = [System.Net.Http.HttpClient]::new()
try {
    foreach ($entry in $seedEntries) {
        $seedBytes = [IO.File]::ReadAllBytes((Join-Path $seedImageRoot ([string]$entry.file)))
        if ($seedBytes.Length -lt 3 -or $seedBytes[0] -ne 255 -or $seedBytes[1] -ne 216 -or $seedBytes[2] -ne 255) { throw 'Manifest source is not a JPEG image; nothing was uploaded.' }
        $seedKey = "products/$($entry.productId)/$($entry.imageId).jpg"
        $publicUrl = "$seedBase/storage/v1/object/public/$seedBucket/$seedKey"
        $existing = $seedClient.GetAsync($publicUrl).GetAwaiter().GetResult()
        if (-not $existing.IsSuccessStatusCode) {
            # Supabase's public object endpoint can use HTTP 400 for a missing object.
            $missing = [int]$existing.StatusCode -eq 404
            if ([int]$existing.StatusCode -eq 400) {
                $providerBody = $existing.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
                $missing = [string]$providerBody.statusCode -eq '404'
            }
            if (-not $missing) { throw "Public seed lookup failed: $([int]$existing.StatusCode)" }
            if ($VerifyOnly) { throw "Seed object is missing for $($entry.productId); run provisioning without -VerifyOnly first." }
            try {
                Invoke-WebRequest -Method Post -Uri "$seedBase/storage/v1/object/$seedBucket/$seedKey" `
                    -Headers $seedHeaders -ContentType 'image/jpeg' -Body $seedBytes | Out-Null
            } catch { throw "Seed upload failed for $($entry.productId); check storage configuration." }
        }
        $actual = $seedClient.GetByteArrayAsync($publicUrl).GetAwaiter().GetResult()
        if ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($actual)) -ne
            [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($seedBytes))) {
            throw "Existing seed bytes differ for $($entry.productId); nothing was overwritten."
        }
        $seedHash = [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($actual)).ToLowerInvariant()
        Write-Output "$($entry.productId): public image verified ($($actual.Length) bytes, SHA256 $seedHash)"
    }
} finally { $seedClient.Dispose() }
