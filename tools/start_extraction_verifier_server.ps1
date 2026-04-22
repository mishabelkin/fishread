param(
    [int]$Port = 8765,
    [switch]$NoOpenBrowser
)

$ErrorActionPreference = 'Stop'

$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Split-Path -Parent $scriptRoot
$verifierScript = Join-Path $scriptRoot 'run_extraction_verifier.ps1'
$tempRoot = Join-Path $env:TEMP 'read-extraction-verifier'
New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null

function Write-TextResponse {
    param(
        [Parameter(Mandatory = $true)] [System.Net.HttpListenerResponse]$Response,
        [Parameter(Mandatory = $true)] [string]$Body,
        [string]$ContentType = 'text/html; charset=utf-8',
        [int]$StatusCode = 200,
        [hashtable]$Headers = @{}
    )

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
    $Response.StatusCode = $StatusCode
    $Response.ContentType = $ContentType
    foreach ($headerName in $Headers.Keys) {
        $Response.Headers[$headerName] = [string]$Headers[$headerName]
    }
    $Response.ContentLength64 = $bytes.LongLength
    $Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $Response.OutputStream.Close()
}

function Get-UiHtml {
    param(
        [int]$Port
    )

    $bookmarklet = "javascript:(()=>{try{const form=document.createElement('form');form.action='http://127.0.0.1:$Port/capture';form.method='POST';form.target='_blank';form.style.display='none';const add=(name,value)=>{const input=document.createElement('textarea');input.name=name;input.value=value;form.appendChild(input);};add('sourceLabel',location.href);add('rawHtml',document.documentElement.outerHTML);document.body.appendChild(form);form.submit();setTimeout(()=>form.remove(),1000);}catch(error){alert(error&&error.message?error.message:String(error));}})();"
    $bookmarkletHref = [System.Net.WebUtility]::HtmlEncode($bookmarklet)

    return @"
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Read! Extraction Verifier</title>
  <style>
    body { font-family: Segoe UI, Arial, sans-serif; margin: 0; background: #f5f7fb; color: #1f2937; }
    main { max-width: 1040px; margin: 0 auto; padding: 18px; }
    h1 { font-size: 30px; margin: 0 0 8px; }
    p { line-height: 1.55; }
    .muted { color: #667085; }
    .card { background: white; border: 1px solid #d9e2f1; border-radius: 14px; padding: 14px; box-shadow: 0 2px 8px rgba(15, 23, 42, 0.05); margin-top: 12px; }
    .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 12px; }
    label { display: block; font-size: 13px; color: #475467; margin-bottom: 6px; font-weight: 600; }
    input[type=text], input[type=url], input[type=file], textarea {
      width: 100%; box-sizing: border-box; border: 1px solid #cbd5e1; border-radius: 10px; padding: 10px 12px;
      font: inherit; background: #fff;
    }
    textarea { min-height: 180px; resize: vertical; }
    button {
      border: 0; border-radius: 10px; background: #2563eb; color: white; padding: 11px 18px; font: inherit;
      font-weight: 600; cursor: pointer;
    }
    button:disabled { opacity: 0.65; cursor: wait; }
    .actions { display: flex; gap: 12px; align-items: center; margin-top: 12px; }
    .status { font-size: 14px; color: #475467; }
    .pill { display: inline-block; background: #dbeafe; color: #1d4ed8; border-radius: 999px; padding: 4px 10px; font-size: 12px; font-weight: 600; margin-right: 8px; }
    .tips li { margin-bottom: 6px; }
    @media (max-width: 720px) {
      main { padding: 14px; }
      .card { padding: 12px; }
      textarea { min-height: 150px; }
    }
  </style>
</head>
<body>
  <main>
    <h1>Read! Extraction Verifier</h1>
    <p class="muted">This browser UI still uses the same Read! extraction pipeline under the hood. It is easier to work with, but the actual extraction still runs through the local verifier script and Gradle test harness.</p>

    <div class="card">
      <p><span class="pill">1</span>Enter a URL, choose a saved HTML/PDF file, or paste raw HTML.</p>
      <p><span class="pill">2</span>Click <strong>Run verifier</strong>.</p>
      <p><span class="pill">3</span>The generated report replaces this page in the same browser tab.</p>
    </div>

    <div class="card">
      <div class="grid">
        <div>
          <label for="urlInput">Remote URL</label>
          <input id="urlInput" type="url" placeholder="https://example.com/article" />
        </div>
        <div>
          <label for="sourceLabelInput">Source label for pasted/saved HTML (optional)</label>
          <input id="sourceLabelInput" type="text" placeholder="https://example.com/article" />
        </div>
      </div>

      <div class="grid" style="margin-top:14px;">
        <div>
          <label for="htmlFileInput">Saved HTML file</label>
          <input id="htmlFileInput" type="file" accept=".html,.htm,text/html" />
        </div>
        <div>
          <label for="pdfFileInput">Saved PDF file</label>
          <input id="pdfFileInput" type="file" accept=".pdf,application/pdf" />
        </div>
      </div>

      <div style="margin-top:14px;">
        <label for="rawHtmlInput">Raw HTML paste</label>
        <textarea id="rawHtmlInput" placeholder="Paste page HTML here if you want to inspect a saved DOM snapshot directly."></textarea>
      </div>

      <div class="actions">
        <button id="runButton" type="button">Run verifier</button>
        <span id="status" class="status">Idle</span>
      </div>
    </div>

    <div class="card">
      <h2 style="margin-top:0;">Tips</h2>
      <ul class="tips">
        <li>For saved HTML, the optional source label matters because some heuristics depend on host or URL shape.</li>
        <li>For raw HTML paste, give the source label if the page came from a specific URL.</li>
        <li>PDF and HTML file inputs are read in the browser and posted to the local verifier service on this machine only.</li>
        <li>For challenge-gated pages like WSJ, open the article in your browser first and use the capture bookmarklet below.</li>
      </ul>
    </div>

    <div class="card">
      <h2 style="margin-top:0;">Capture current browser page</h2>
      <p class="muted">For pages that only work once your browser has solved the challenge or loaded the live DOM, drag this link to your bookmarks bar, open the article, and click it.</p>
      <p><a href="$bookmarkletHref">Capture current page into verifier</a></p>
      <p class="muted">The bookmarklet sends the current page HTML to your local verifier server and replaces the tab with the generated report.</p>
    </div>
  </main>

  <script>
    const runButton = document.getElementById('runButton');
    const statusEl = document.getElementById('status');
    const urlInput = document.getElementById('urlInput');
    const sourceLabelInput = document.getElementById('sourceLabelInput');
    const htmlFileInput = document.getElementById('htmlFileInput');
    const pdfFileInput = document.getElementById('pdfFileInput');
    const rawHtmlInput = document.getElementById('rawHtmlInput');

    function setStatus(message) {
      statusEl.textContent = message;
    }

    function readTextFile(file) {
      return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onerror = () => reject(new Error('Unable to read file.'));
        reader.onload = () => resolve(reader.result || '');
        reader.readAsText(file);
      });
    }

    function readBase64(file) {
      return new Promise((resolve, reject) => {
        const reader = new FileReader();
        reader.onerror = () => reject(new Error('Unable to read file.'));
        reader.onload = () => {
          const result = String(reader.result || '');
          const commaIndex = result.indexOf(',');
          resolve(commaIndex >= 0 ? result.slice(commaIndex + 1) : result);
        };
        reader.readAsDataURL(file);
      });
    }

    async function buildPayload() {
      const url = urlInput.value.trim();
      const sourceLabel = sourceLabelInput.value.trim();
      const rawHtml = rawHtmlInput.value;
      const htmlFile = htmlFileInput.files[0];
      const pdfFile = pdfFileInput.files[0];

      const payload = {
        url,
        sourceLabel,
        rawHtml,
        htmlFileName: htmlFile ? htmlFile.name : '',
        pdfFileName: pdfFile ? pdfFile.name : ''
      };

      if (pdfFile) {
        payload.pdfBase64 = await readBase64(pdfFile);
      } else if (htmlFile) {
        payload.rawHtml = await readTextFile(htmlFile);
      }

      return payload;
    }

    runButton.addEventListener('click', async () => {
      runButton.disabled = true;
      setStatus('Running verifier...');

      try {
        const payload = await buildPayload();
        const response = await fetch('/run', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        });

        const html = await response.text();
        if (!response.ok) {
          document.open();
          document.write(html);
          document.close();
          return;
        }

        document.open();
        document.write(html);
        document.close();
      } catch (error) {
        setStatus(error && error.message ? error.message : 'Verifier failed.');
        runButton.disabled = false;
      }
    });
  </script>
</body>
</html>
"@
}

function New-ErrorPage {
    param(
        [string]$Message,
        [string]$Details = ''
    )

    $safeMessage = [System.Net.WebUtility]::HtmlEncode($Message)
    $safeDetails = [System.Net.WebUtility]::HtmlEncode($Details)
    return @"
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Verifier Error</title>
  <style>
    body { font-family: Segoe UI, Arial, sans-serif; margin: 0; background: #fff7ed; color: #7c2d12; }
    main { max-width: 920px; margin: 0 auto; padding: 24px; }
    .card { background: white; border: 1px solid #fdba74; border-radius: 14px; padding: 16px; }
    pre { white-space: pre-wrap; background: #431407; color: #ffedd5; padding: 14px; border-radius: 12px; overflow-x: auto; }
    a { color: #9a3412; }
  </style>
</head>
<body>
  <main>
    <div class="card">
      <h1>Verifier failed</h1>
      <p>$safeMessage</p>
      <p><a href="/">Back to verifier form</a></p>
    </div>
    <pre>$safeDetails</pre>
  </main>
</body>
</html>
"@
}

function Invoke-VerifierRequest {
    param(
        [pscustomobject]$Payload
    )

    $id = [guid]::NewGuid().ToString('N')
    $outputPath = Join-Path $tempRoot "report-$id.html"
    $args = @{
        OutputFile = $outputPath
        NoOpen = $true
    }

    if ($Payload.url) {
        $args.Url = [string]$Payload.url
    } elseif ($Payload.pdfBase64) {
        $pdfName = if ([string]::IsNullOrWhiteSpace([string]$Payload.pdfFileName)) {
            "input-$id.pdf"
        } else {
            [string]$Payload.pdfFileName
        }
        $pdfPath = Join-Path $tempRoot $pdfName
        [System.IO.File]::WriteAllBytes($pdfPath, [Convert]::FromBase64String([string]$Payload.pdfBase64))
        $args.PdfFile = $pdfPath
    } else {
        $rawHtml = [string]$Payload.rawHtml
        if ([string]::IsNullOrWhiteSpace($rawHtml)) {
            throw "Provide a URL, an HTML/PDF file, or pasted HTML."
        }
        $htmlPath = Join-Path $tempRoot "input-$id.html"
        Set-Content -LiteralPath $htmlPath -Value $rawHtml -Encoding UTF8
        $args.HtmlFile = $htmlPath
        if (-not [string]::IsNullOrWhiteSpace([string]$Payload.sourceLabel)) {
            $args.SourceLabel = [string]$Payload.sourceLabel
        }
    }

    & $verifierScript @args | Out-Null

    if (-not (Test-Path -LiteralPath $outputPath)) {
        throw "Verifier did not create an output report."
    }

    return Get-Content -LiteralPath $outputPath -Raw
}

$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$listener.Prefixes.Add("http://localhost:$Port/")
$listener.Start()

$rootUrl = "http://127.0.0.1:$Port/"
Write-Host "Read! extraction verifier server running at $rootUrl"
Write-Host "Press Ctrl+C to stop."

if (-not $NoOpenBrowser) {
    Start-Process $rootUrl
}

try {
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        $request = $context.Request
        $response = $context.Response

        try {
            if ($request.HttpMethod -eq 'GET' -and ($request.Url.AbsolutePath -eq '/' -or $request.Url.AbsolutePath -eq '/index.html')) {
                Write-TextResponse -Response $response -Body (Get-UiHtml -Port $Port)
                continue
            }

            if ($request.HttpMethod -eq 'POST' -and $request.Url.AbsolutePath -eq '/run') {
                $reader = New-Object System.IO.StreamReader($request.InputStream, $request.ContentEncoding)
                try {
                    $body = $reader.ReadToEnd()
                } finally {
                    $reader.Dispose()
                }

                $payload = $body | ConvertFrom-Json
                $reportHtml = Invoke-VerifierRequest -Payload $payload
                Write-TextResponse -Response $response -Body $reportHtml
                continue
            }

            if ($request.HttpMethod -eq 'OPTIONS' -and $request.Url.AbsolutePath -eq '/capture') {
                Write-TextResponse `
                    -Response $response `
                    -Body '' `
                    -ContentType 'text/plain; charset=utf-8' `
                    -Headers @{
                        'Access-Control-Allow-Origin' = '*'
                        'Access-Control-Allow-Methods' = 'POST, OPTIONS'
                        'Access-Control-Allow-Headers' = 'Content-Type'
                    }
                continue
            }

            if ($request.HttpMethod -eq 'POST' -and $request.Url.AbsolutePath -eq '/capture') {
                $reader = New-Object System.IO.StreamReader($request.InputStream, $request.ContentEncoding)
                try {
                    $body = $reader.ReadToEnd()
                } finally {
                    $reader.Dispose()
                }

                $payload = $body | ConvertFrom-Json
                $reportHtml = Invoke-VerifierRequest -Payload $payload
                Write-TextResponse `
                    -Response $response `
                    -Body $reportHtml `
                    -Headers @{
                        'Access-Control-Allow-Origin' = '*'
                    }
                continue
            }

            Write-TextResponse -Response $response -Body (New-ErrorPage -Message "Unknown route: $($request.HttpMethod) $($request.Url.AbsolutePath)") -StatusCode 404
        } catch {
            $details = $_ | Out-String
            $headers = @{}
            if ($request.Url.AbsolutePath -eq '/capture') {
                $headers['Access-Control-Allow-Origin'] = '*'
            }
            Write-TextResponse -Response $response -Body (New-ErrorPage -Message $_.Exception.Message -Details $details) -StatusCode 500 -Headers $headers
        }
    }
} finally {
    if ($listener.IsListening) {
        $listener.Stop()
    }
    $listener.Close()
}
