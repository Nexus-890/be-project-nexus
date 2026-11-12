<#
.SYNOPSIS
Upload a product image THROUGH the catalog BE, then verify the returned public image.
.EXAMPLE
$env:NEXUS_SELLER_ACCESS_TOKEN = '<seller access token>'
.\upload-product-image.ps1 -ProductId '<owned product uuid>' -ImagePath '.\image.jpg'
#>
param(
    [Parameter(Mandatory=$true)][guid]$ProductId,
    [Parameter(Mandatory=$true)][string]$ImagePath,
    [string]$ApiBaseUrl = 'http://localhost:8080',
    [string]$SupabaseUrl = 'https://nzjvjvyqxcisjgvglwvj.supabase.co',
    [string]$Bucket = 'product-images'
)
$ErrorActionPreference = 'Stop'
if (-not $env:NEXUS_SELLER_ACCESS_TOKEN) { throw 'Set NEXUS_SELLER_ACCESS_TOKEN to the product owner seller JWT.' }
$image = Get-Item -LiteralPath $ImagePath
if ($image.PSIsContainer -or $image.Length -eq 0 -or $image.Length -gt 5MB) { throw 'Choose a nonempty JPEG, PNG or WebP image up to 5 MB.' }
$apiUri = [uri]$ApiBaseUrl
if (-not $apiUri.IsAbsoluteUri -or $apiUri.Scheme -notin @('http','https')) { throw 'Invalid ApiBaseUrl.' }
if ($apiUri.Scheme -eq 'http' -and -not $apiUri.IsLoopback) { throw 'Use HTTPS for a remote backend.' }
$client = [System.Net.Http.HttpClient]::new()
$form = [System.Net.Http.MultipartFormDataContent]::new()
try {
    $client.DefaultRequestHeaders.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$env:NEXUS_SELLER_ACCESS_TOKEN)
    $bytes = [IO.File]::ReadAllBytes($image.FullName)
    $part = [System.Net.Http.ByteArrayContent]::new($bytes)
    $part.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::new('application/octet-stream')
    $form.Add($part,'file',$image.Name)
    $response = $client.PostAsync($ApiBaseUrl.TrimEnd('/') + "/api/v1/products/$ProductId/images",$form).GetAwaiter().GetResult()
    if ([int]$response.StatusCode -ne 201) { throw "BE image upload returned HTTP $([int]$response.StatusCode). Check product ownership, PRODUCT.UPDATE permission, image count/type and server Storage credentials." }
    $payload = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult() | ConvertFrom-Json
    $publicUrl = [string]$payload.data.imageUrl
    $expectedPrefix = $SupabaseUrl.TrimEnd('/') + "/storage/v1/object/public/$Bucket/products/$ProductId/"
    if (-not $publicUrl.StartsWith($expectedPrefix,[StringComparison]::Ordinal)) { throw 'BE returned an unexpected public image URL; check SupabaseUrl/Bucket.' }
    # Public image verification must not forward the seller JWT to Supabase.
    $client.DefaultRequestHeaders.Authorization = $null
    $actual = $client.GetByteArrayAsync($publicUrl).GetAwaiter().GetResult()
    if ([Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($actual)) -ne [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes))) { throw 'Public image bytes differ from the uploaded image.' }
    [pscustomobject]@{ imageId=$payload.data.id; imageUrl=$publicUrl; bytes=$actual.Length; verified=$true } | ConvertTo-Json
} finally { $form.Dispose(); $client.Dispose() }
