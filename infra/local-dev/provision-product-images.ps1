param([string]$EnvFile = (Join-Path $PSScriptRoot '.env'))
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
if ($seedBase -notmatch '^https://[a-z0-9-]+\.supabase\.co$' -or -not $env:SUPABASE_SERVICE_KEY) {
    throw 'Set a valid SUPABASE_URL and SUPABASE_SERVICE_KEY before provisioning.'
}
$seedBucket = if ($env:SUPABASE_BUCKET) { $env:SUPABASE_BUCKET } else { 'product-images' }
if ($seedBucket -notmatch '^[a-zA-Z0-9_-]+$') { throw 'Invalid bucket name.' }
$seedHeaders = @{ Authorization = "Bearer $env:SUPABASE_SERVICE_KEY"; apikey = $env:SUPABASE_SERVICE_KEY; 'x-upsert' = 'false' }
$seedClient = [System.Net.Http.HttpClient]::new()
try {
    foreach ($entry in Get-Content (Join-Path $PSScriptRoot 'seed-images/manifest.json') -Raw | ConvertFrom-Json) {
        $seedBytes = [IO.File]::ReadAllBytes((Join-Path $PSScriptRoot "seed-images/$($entry.file)"))
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
        Write-Output "$($entry.productId): public image verified ($($actual.Length) bytes)"
    }
} finally { $seedClient.Dispose() }
