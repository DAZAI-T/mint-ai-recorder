<#
  Download official local models from catalog-pinned URLs and verify size plus SHA-256.
  Incomplete or mismatched files never replace an installed model.
#>

param(
    [Parameter(Mandatory = $false)]
    [ValidatePattern('^[a-z0-9][a-z0-9._-]{0,95}$')]
    [string]$ModelId
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$catalogPath = Join-Path $PSScriptRoot "..\app\src\main\assets\models\official-models-v1.json"
$catalog = Get-Content -LiteralPath $catalogPath -Raw | ConvertFrom-Json
[array]$selectedModels = if ($ModelId) {
    @($catalog.models | Where-Object { $_.modelId -eq $ModelId })
} else {
    @($catalog.models)
}
if ($selectedModels.Count -eq 0) { throw "Unknown official modelId: $ModelId" }
$outDir = Join-Path $PSScriptRoot "..\models"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

function Test-OfficialModelFile {
    param([string]$Path, $Definition)
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { return $false }
    $file = Get-Item -LiteralPath $Path
    if ($file.Length -ne [int64]$Definition.byteSize) { return $false }
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    return $actual -eq [string]$Definition.sha256
}

foreach ($model in $selectedModels) {
    $dest = Join-Path $outDir ([string]$model.fileName)
    if (Test-OfficialModelFile -Path $dest -Definition $model) {
        Write-Host "verified (already exists): $($model.fileName)"
        continue
    }

    $partial = "$dest.partial"
    Write-Host "downloading and verifying: $($model.fileName)"
    try {
        Invoke-WebRequest -Uri ([string]$model.sourceUrl) -OutFile $partial
        if (-not (Test-OfficialModelFile -Path $partial -Definition $model)) {
            throw "size or SHA-256 mismatch: $($model.fileName)"
        }
        Move-Item -LiteralPath $partial -Destination $dest -Force
    } finally {
        if (Test-Path -LiteralPath $partial) { Remove-Item -LiteralPath $partial -Force }
    }
}

Write-Host "done. verified models are in $outDir"
Write-Host "Select each matching file from the app's official model verification buttons."
