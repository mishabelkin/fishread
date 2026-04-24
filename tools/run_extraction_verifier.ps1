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
} elseif (-not [System.IO.Path]::IsPathRooted($OutputFile)) {
    $OutputFile = Join-Path $projectRoot $OutputFile
}
$OutputFile = [System.IO.Path]::GetFullPath($OutputFile)

$effectiveSourceLabel = if ($Url) { $Url } else { $SourceLabel }

$envUpdates = @{
    'READ_DEBUG_INPUT_URL' = $effectiveSourceLabel
    'READ_DEBUG_WEB_HTML_FILE' = $HtmlFile
    'READ_DEBUG_PDF_FILE' = $PdfFile
    'READ_DEBUG_OUTPUT_FILE' = $OutputFile
}
$previousValues = @{}
$gradleWrapper = Join-Path $projectRoot 'gradlew.bat'
$gradleArgs = @(
    ':app:testDebugUnitTest',
    '--tests',
    'org.read.mobile.WebExtractionToolTest.previewExtractionFromSuppliedInput',
    '--no-daemon',
    '--rerun-tasks'
)

function Invoke-GradleVerifierTask {
    param(
        [string]$WrapperPath,
        [string[]]$Arguments
    )

    $stdoutFile = [System.IO.Path]::GetTempFileName()
    $stderrFile = [System.IO.Path]::GetTempFileName()
    try {
        $process = Start-Process -FilePath $WrapperPath `
            -ArgumentList $Arguments `
            -WorkingDirectory $projectRoot `
            -NoNewWindow `
            -PassThru `
            -Wait `
            -RedirectStandardOutput $stdoutFile `
            -RedirectStandardError $stderrFile

        $stdout = if (Test-Path -LiteralPath $stdoutFile) {
            [System.IO.File]::ReadAllText($stdoutFile)
        } else {
            ''
        }
        $stderr = if (Test-Path -LiteralPath $stderrFile) {
            [System.IO.File]::ReadAllText($stderrFile)
        } else {
            ''
        }

        if ($stdout) {
            Write-Host $stdout.TrimEnd()
        }
        if ($stderr) {
            Write-Host $stderr.TrimEnd()
        }

        return @{
            ExitCode = $process.ExitCode
            StdOut = $stdout
            StdErr = $stderr
        }
    }
    finally {
        Remove-Item -LiteralPath $stdoutFile -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $stderrFile -Force -ErrorAction SilentlyContinue
    }
}

function Test-IsTransientGradleFileLock {
    param(
        [string]$StdOut,
        [string]$StdErr
    )

    $combined = "$StdOut`n$StdErr"
    return $combined -match "Couldn't delete .*R\.jar" -or
        $combined -match 'The process cannot access the file' -or
        $combined -match 'being used by another process'
}

try {
    foreach ($key in $envUpdates.Keys) {
        $previousValues[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $envUpdates[$key], 'Process')
    }

    $gradleResult = Invoke-GradleVerifierTask -WrapperPath $gradleWrapper -Arguments $gradleArgs
    if ($gradleResult.ExitCode -ne 0 -and (Test-IsTransientGradleFileLock -StdOut $gradleResult.StdOut -StdErr $gradleResult.StdErr)) {
        Write-Host 'Gradle hit a transient build-output file lock. Waiting 3 seconds and retrying once...'
        Start-Sleep -Seconds 3
        $gradleResult = Invoke-GradleVerifierTask -WrapperPath $gradleWrapper -Arguments $gradleArgs
    }
    if ($gradleResult.ExitCode -ne 0) {
        throw "Verifier Gradle task failed with exit code $($gradleResult.ExitCode)."
    }

    $resolvedOutput = [System.IO.Path]::GetFullPath($OutputFile)
    if (-not (Test-Path -LiteralPath $resolvedOutput)) {
        throw "Expected output file was not created: $resolvedOutput"
    }
    Write-Host "Saved extraction report to $resolvedOutput"
    if (-not $NoOpen) {
        Invoke-Item -LiteralPath $resolvedOutput
    }
}
finally {
    foreach ($key in $previousValues.Keys) {
        [Environment]::SetEnvironmentVariable($key, $previousValues[$key], 'Process')
    }
}
