param(
    [string]$Url,
    [string]$SourceLabel,
    [string]$HtmlFile,
    [string]$PdfFile,
    [string]$OutputFile,
    [switch]$NoOpen
)

$ErrorActionPreference = 'Stop'

if (-not $Url -and -not $HtmlFile -and -not $PdfFile) {
    throw 'Provide -Url, -HtmlFile, or -PdfFile.'
}

$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $OutputFile) {
    $OutputFile = Join-Path $projectRoot 'app\build\reports\extraction-tool\latest.html'
}

$effectiveSourceLabel = if ($Url) { $Url } else { $SourceLabel }

$envUpdates = @{
    'READ_DEBUG_INPUT_URL' = $effectiveSourceLabel
    'READ_DEBUG_WEB_HTML_FILE' = $HtmlFile
    'READ_DEBUG_PDF_FILE' = $PdfFile
    'READ_DEBUG_OUTPUT_FILE' = $OutputFile
}
$previousValues = @{}

try {
    foreach ($key in $envUpdates.Keys) {
        $previousValues[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $envUpdates[$key], 'Process')
    }

    & (Join-Path $projectRoot 'gradlew.bat') ':app:testDebugUnitTest' '--tests' 'org.read.mobile.WebExtractionToolTest.previewExtractionFromSuppliedInput' '--no-daemon' '--rerun-tasks'
    $resolvedOutput = [System.IO.Path]::GetFullPath($OutputFile)
    Write-Host "Saved extraction report to $resolvedOutput"
    if (-not (Test-Path -LiteralPath $resolvedOutput)) {
        throw "Expected output file was not created: $resolvedOutput"
    }
    if (-not $NoOpen) {
        Invoke-Item -LiteralPath $resolvedOutput
    }
}
finally {
    foreach ($key in $previousValues.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process')
    }
}
