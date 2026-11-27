# Rebuilds the annotation census corpus from samples/corpus/MANIFEST.tsv.
#
# The blobs are deliberately NOT in git: this script plus the manifest make them reproducible.
# Usage:
#   .\fetch.ps1                 # everything that is missing
#   .\fetch.ps1 -Source pmr     # one source
#   .\fetch.ps1 -Force          # re-download even when the file is present
param(
    [ValidateSet('all', 'biomodels', 'pmr', 'jws')]
    [string]$Source = 'all',
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$manifest = Join-Path $here 'MANIFEST.tsv'

if (-not (Test-Path $manifest)) { throw "manifest not found: $manifest" }

function TargetName($row) {
    switch ($row.source) {
        'biomodels' { return ($row.id + '_url.xml') }
        # PMR models in different workspaces share leaf names (model.cellml, units.cellml), so the
        # manifest id is used as a relative path instead of the bare file name.
        'pmr'       { return ($row.id -replace '/', '\') }
        'jws'       { return $row.id }
        default     { throw "unknown source in manifest: $($row.source)" }
    }
}

$rows = Import-Csv -Path $manifest -Delimiter "`t"
$wanted = $rows | Where-Object { $Source -eq 'all' -or $_.source -eq $Source }

$ok = 0; $skipped = 0; $failed = 0
foreach ($row in $wanted) {
    $dir = Join-Path $here $row.source
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    $dest = Join-Path $dir (TargetName $row)
    $name = Split-Path $dest -Leaf
    $parent = Split-Path $dest -Parent
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Force -Path $parent | Out-Null }

    if ((Test-Path $dest) -and -not $Force) {
        if ($row.sha256) {
            $have = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLowerInvariant()
            if ($have -eq $row.sha256) { $skipped++; continue }
            Write-Warning "$name exists but its hash differs from the manifest; re-downloading"
        } else {
            $skipped++; continue
        }
    }

    $downloaded = $false
    foreach ($attempt in 1..2) {
        try {
            Invoke-WebRequest -Uri $row.url -OutFile $dest -UseBasicParsing -TimeoutSec 60
            $downloaded = $true
            break
        } catch {
            if ($attempt -eq 2) {
                Write-Warning "FAILED $($row.source)/$name : $($_.Exception.Message)"
                $failed++
            }
        }
    }
    if (-not $downloaded) { continue }

    if ($row.sha256) {
        $have = (Get-FileHash $dest -Algorithm SHA256).Hash.ToLowerInvariant()
        if ($have -ne $row.sha256) {
            Write-Warning "$name hash mismatch: manifest $($row.sha256), got $have"
            $failed++
            continue
        }
    }
    $ok++
}

"downloaded=$ok skipped=$skipped failed=$failed  (blobs are gitignored; do not commit them)"
