<#
.SYNOPSIS
Provision owned demo images through the running backend, using a seller access token.
.DESCRIPTION
Requires PowerShell 7+, seed-buyer-shop.ps1 and the configured catalog BE.
Skips products that already have an owned image. Never uses a Storage key in this client.
Afterwards rerun seed-buyer-shop.ps1 to remove the shared initial fallback rows.
#>
param([string]$ApiBaseUrl='http://localhost:8080')
$ErrorActionPreference='Stop'
if (-not $env:NEXUS_SELLER_ACCESS_TOKEN) { throw 'Set NEXUS_SELLER_ACCESS_TOKEN from the demo seller OIDC session.' }
$verified=0
$uploaded=0
for ($n=1; $n -le 24; $n++) {
    $productId='92000000-0000-4000-8000-'+(200+$n).ToString('000000000000')
    $product=Invoke-RestMethod ($ApiBaseUrl.TrimEnd('/')+"/api/v1/products/$productId")
    $owned=@($product.data.images | Where-Object { $_.imageUrl -like "*/products/$productId/*" })
    if ($owned.Count -eq 0) {
        $filename=if ($n -le 8) { 'phone.jpg' } elseif ($n -le 16) { 'shirt.jpg' } else { 'sneaker.jpg' }
        & (Join-Path $PSScriptRoot 'upload-product-image.ps1') -ApiBaseUrl $ApiBaseUrl -ProductId $productId -ImagePath (Join-Path $PSScriptRoot "seed-images/$filename") | Out-Null
        $uploaded++
        $product=Invoke-RestMethod ($ApiBaseUrl.TrimEnd('/')+"/api/v1/products/$productId")
        $owned=@($product.data.images | Where-Object { $_.imageUrl -like "*/products/$productId/*" })
    }
    if ($owned.Count -eq 0) { throw "Owned product image missing for $productId." }
    $imageIds=@($owned | ForEach-Object { $_.id })+@($product.data.images | Where-Object { $_.imageUrl -notlike "*/products/$productId/*" } | ForEach-Object { $_.id })
    Invoke-RestMethod -Method Put -Uri ($ApiBaseUrl.TrimEnd('/')+"/api/v1/products/$productId/images/order") -Headers @{ Authorization="Bearer $env:NEXUS_SELLER_ACCESS_TOKEN" } -ContentType 'application/json' -Body (@{imageIds=$imageIds} | ConvertTo-Json) | Out-Null
    # A separate public request must not carry the seller access token.
    $public=Invoke-WebRequest $owned[0].imageUrl
    if ($public.StatusCode -ne 200) { throw "Public image verification failed for $productId." }
    $verified++
}
[pscustomobject]@{ uploaded=$uploaded; verified=$verified } | ConvertTo-Json
