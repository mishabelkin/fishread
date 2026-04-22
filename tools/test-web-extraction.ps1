param(
    [string]$Url,
    [string]$HtmlFile,
    [string]$OutputFile = (Join-Path $PSScriptRoot "..\web_extraction_preview.md")
)

$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

if ([string]::IsNullOrWhiteSpace($Url) -and [string]::IsNullOrWhiteSpace($HtmlFile)) {
    Write-Error "Provide either -Url or -HtmlFile."
    exit 1
}

$resolvedHtmlFile = $HtmlFile
$sourceLabel = $Url

if (-not [string]::IsNullOrWhiteSpace($Url) -and [string]::IsNullOrWhiteSpace($resolvedHtmlFile)) {
    $tempName = "read_web_extract_{0}.html" -f ([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
    $resolvedHtmlFile = Join-Path $env:TEMP $tempName
    & curl.exe -L `
        -A "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36" `
        -H "Accept-Language: en-US,en;q=0.9" `
        $Url `
        -o $resolvedHtmlFile
}

if ([string]::IsNullOrWhiteSpace($sourceLabel)) {
    $sourceLabel = (Resolve-Path $resolvedHtmlFile).Path
}

$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:READ_DEBUG_WEB_URL = $sourceLabel
$env:READ_DEBUG_WEB_HTML_FILE = $resolvedHtmlFile
$env:READ_DEBUG_WEB_OUTPUT_FILE = $OutputFile
$gradle = Join-Path $projectRoot 'gradlew.bat'
$resolvedOutputFile = (Resolve-Path (Split-Path -Parent $OutputFile) -ErrorAction SilentlyContinue)
if (-not $resolvedOutputFile) {
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputFile) | Out-Null
}

& $gradle `
    :app:testDebugUnitTest `
    --tests org.read.mobile.WebExtractionToolTest `
    --rerun-tasks `

if (Test-Path $OutputFile) {
    Write-Host "Saved extraction preview to $OutputFile"
} else {
    Write-Warning "The extraction preview was not written. The fetched HTML may have been a blocker/interstitial page."
}
