import argparse
import base64
import html
import json
import subprocess
import tempfile
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs


SCRIPT_ROOT = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_ROOT.parent
VERIFIER_SCRIPT = SCRIPT_ROOT / "run_extraction_verifier.ps1"
TEMP_ROOT = Path(tempfile.gettempdir()) / "read-extraction-verifier"
TEMP_ROOT.mkdir(parents=True, exist_ok=True)


def ui_html(port: int) -> str:
    bookmarklet = (
        "javascript:(()=>{try{"
        "const form=document.createElement('form');"
        f"form.action='http://127.0.0.1:{port}/capture';"
        "form.method='POST';"
        "form.target='_blank';"
        "form.style.display='none';"
        "const add=(name,value)=>{const input=document.createElement('textarea');input.name=name;input.value=value;form.appendChild(input);};"
        "add('sourceLabel',location.href);"
        "add('rawHtml',document.documentElement.outerHTML);"
        "document.body.appendChild(form);"
        "form.submit();"
        "setTimeout(()=>form.remove(),1000);"
        "}catch(error){alert(error&&error.message?error.message:String(error));}})();"
    )
    bookmarklet_href = html.escape(bookmarklet, quote=True)
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Read! Extraction Verifier</title>
  <style>
    body {{ font-family: Segoe UI, Arial, sans-serif; margin: 0; background: #f5f7fb; color: #1f2937; }}
    main {{ max-width: 1040px; margin: 0 auto; padding: 18px; }}
    h1 {{ font-size: 30px; margin: 0 0 8px; }}
    p {{ line-height: 1.55; }}
    .muted {{ color: #667085; }}
    .card {{ background: white; border: 1px solid #d9e2f1; border-radius: 14px; padding: 14px; box-shadow: 0 2px 8px rgba(15, 23, 42, 0.05); margin-top: 12px; }}
    .grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(260px, 1fr)); gap: 12px; }}
    label {{ display: block; font-size: 13px; color: #475467; margin-bottom: 6px; font-weight: 600; }}
    input[type=text], input[type=url], input[type=file], textarea {{
      width: 100%; box-sizing: border-box; border: 1px solid #cbd5e1; border-radius: 10px; padding: 10px 12px;
      font: inherit; background: #fff;
    }}
    textarea {{ min-height: 180px; resize: vertical; }}
    button {{
      border: 0; border-radius: 10px; background: #2563eb; color: white; padding: 11px 18px; font: inherit;
      font-weight: 600; cursor: pointer;
    }}
    button:disabled {{ opacity: 0.65; cursor: wait; }}
    .actions {{ display: flex; gap: 12px; align-items: center; margin-top: 12px; }}
    .status {{ font-size: 14px; color: #475467; }}
    .pill {{ display: inline-block; background: #dbeafe; color: #1d4ed8; border-radius: 999px; padding: 4px 10px; font-size: 12px; font-weight: 600; margin-right: 8px; }}
    .tips li {{ margin-bottom: 6px; }}
    @media (max-width: 720px) {{
      main {{ padding: 14px; }}
      .card {{ padding: 12px; }}
      textarea {{ min-height: 150px; }}
    }}
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
      <p><a href="{bookmarklet_href}">Capture current page into verifier</a></p>
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

    function setStatus(message) {{
      statusEl.textContent = message;
    }}

    function readTextFile(file) {{
      return new Promise((resolve, reject) => {{
        const reader = new FileReader();
        reader.onerror = () => reject(new Error('Unable to read file.'));
        reader.onload = () => resolve(reader.result || '');
        reader.readAsText(file);
      }});
    }}

    function readBase64(file) {{
      return new Promise((resolve, reject) => {{
        const reader = new FileReader();
        reader.onerror = () => reject(new Error('Unable to read file.'));
        reader.onload = () => {{
          const result = String(reader.result || '');
          const commaIndex = result.indexOf(',');
          resolve(commaIndex >= 0 ? result.slice(commaIndex + 1) : result);
        }};
        reader.readAsDataURL(file);
      }});
    }}

    async function buildPayload() {{
      const url = urlInput.value.trim();
      const sourceLabel = sourceLabelInput.value.trim();
      const rawHtml = rawHtmlInput.value;
      const htmlFile = htmlFileInput.files[0];
      const pdfFile = pdfFileInput.files[0];

      const payload = {{
        url,
        sourceLabel,
        rawHtml,
        htmlFileName: htmlFile ? htmlFile.name : '',
        pdfFileName: pdfFile ? pdfFile.name : ''
      }};

      if (pdfFile) {{
        payload.pdfBase64 = await readBase64(pdfFile);
      }} else if (htmlFile) {{
        payload.rawHtml = await readTextFile(htmlFile);
      }}

      return payload;
    }}

    runButton.addEventListener('click', async () => {{
      runButton.disabled = true;
      setStatus('Running verifier...');

      try {{
        const payload = await buildPayload();
        const response = await fetch('/run', {{
          method: 'POST',
          headers: {{ 'Content-Type': 'application/json' }},
          body: JSON.stringify(payload)
        }});

        const page = await response.text();
        document.open();
        document.write(page);
        document.close();
      }} catch (error) {{
        setStatus(error && error.message ? error.message : 'Verifier failed.');
        runButton.disabled = false;
      }}
    }});
  </script>
</body>
</html>"""


def error_page(message: str, details: str = "") -> str:
    return f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <title>Verifier Error</title>
  <style>
    body {{ font-family: Segoe UI, Arial, sans-serif; margin: 0; background: #fff7ed; color: #7c2d12; }}
    main {{ max-width: 920px; margin: 0 auto; padding: 24px; }}
    .card {{ background: white; border: 1px solid #fdba74; border-radius: 14px; padding: 16px; }}
    pre {{ white-space: pre-wrap; background: #431407; color: #ffedd5; padding: 14px; border-radius: 12px; overflow-x: auto; }}
    a {{ color: #9a3412; }}
  </style>
</head>
<body>
  <main>
    <div class="card">
      <h1>Verifier failed</h1>
      <p>{html.escape(message)}</p>
      <p><a href="/">Back to verifier form</a></p>
    </div>
    <pre>{html.escape(details)}</pre>
  </main>
</body>
</html>"""


def invoke_verifier_request(payload: dict) -> str:
    request_id = uuid.uuid4().hex
    output_path = TEMP_ROOT / f"report-{request_id}.html"
    args = [
        "powershell",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        str(VERIFIER_SCRIPT),
        "-OutputFile",
        str(output_path),
        "-NoOpen",
    ]

    url = str(payload.get("url") or "").strip()
    source_label = str(payload.get("sourceLabel") or "").strip()
    raw_html = payload.get("rawHtml")
    pdf_base64 = str(payload.get("pdfBase64") or "").strip()

    if url:
        args.extend(["-Url", url])
    elif pdf_base64:
        pdf_name = str(payload.get("pdfFileName") or "").strip() or f"input-{request_id}.pdf"
        pdf_path = TEMP_ROOT / pdf_name
        pdf_path.write_bytes(base64.b64decode(pdf_base64))
        args.extend(["-PdfFile", str(pdf_path)])
    else:
        if not isinstance(raw_html, str) or not raw_html.strip():
            raise RuntimeError("Provide a URL, an HTML/PDF file, or pasted HTML.")
        html_path = TEMP_ROOT / f"input-{request_id}.html"
        html_path.write_text(raw_html, encoding="utf-8")
        args.extend(["-HtmlFile", str(html_path)])
        if source_label:
            args.extend(["-SourceLabel", source_label])

    completed = subprocess.run(
        args,
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(
            f"Verifier script failed with exit code {completed.returncode}.\n\n"
            f"STDOUT:\n{completed.stdout}\n\nSTDERR:\n{completed.stderr}"
        )

    if not output_path.exists():
        raise RuntimeError("Verifier did not create an output report.")
    return output_path.read_text(encoding="utf-8", errors="replace")


class VerifierHandler(BaseHTTPRequestHandler):
    server_version = "ReadVerifier/1.0"

    def _write_html(self, body: str, status: int = 200, headers: dict | None = None) -> None:
        encoded = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(encoded)))
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(encoded)

    def _read_body_text(self) -> str:
        length = int(self.headers.get("Content-Length", "0") or "0")
        data = self.rfile.read(length) if length > 0 else b""
        return data.decode("utf-8", errors="replace")

    def _parse_payload(self) -> dict:
        content_type = (self.headers.get("Content-Type") or "").lower()
        body = self._read_body_text()
        if "application/json" in content_type or "text/plain" in content_type:
            return json.loads(body or "{}")
        if "application/x-www-form-urlencoded" in content_type:
            parsed = parse_qs(body, keep_blank_values=True)
            return {key: values[-1] if values else "" for key, values in parsed.items()}
        raise RuntimeError(f"Unsupported content type for verifier capture: {content_type or 'unknown'}")

    def do_OPTIONS(self) -> None:
        if self.path == "/capture":
            self.send_response(200)
            self.send_header("Access-Control-Allow-Origin", "*")
            self.send_header("Access-Control-Allow-Methods", "POST, OPTIONS")
            self.send_header("Access-Control-Allow-Headers", "Content-Type")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        self._write_html(error_page(f"Unknown route: OPTIONS {self.path}"), status=404)

    def do_GET(self) -> None:
        if self.path in ("/", "/index.html"):
            body = ui_html(self.server.server_address[1])
            self._write_html(body)
            return
        self._write_html(error_page(f"Unknown route: GET {self.path}"), status=404)

    def do_POST(self) -> None:
        try:
            if self.path == "/run":
                payload = self._parse_payload()
                body = invoke_verifier_request(payload)
                self._write_html(body)
                return

            if self.path == "/capture":
                payload = self._parse_payload()
                body = invoke_verifier_request(payload)
                self._write_html(body, headers={"Access-Control-Allow-Origin": "*"})
                return

            self._write_html(error_page(f"Unknown route: POST {self.path}"), status=404)
        except Exception as exc:  # noqa: BLE001
            headers = {"Access-Control-Allow-Origin": "*"} if self.path == "/capture" else None
            self._write_html(error_page(str(exc), details=str(exc)), status=500, headers=headers)

    def log_message(self, fmt: str, *args) -> None:
        print(f"{self.address_string()} - {fmt % args}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("-Port", "--port", type=int, default=8765)
    parser.add_argument("-NoOpenBrowser", "--no-open-browser", action="store_true")
    args = parser.parse_args()

    server = ThreadingHTTPServer(("127.0.0.1", args.port), VerifierHandler)
    root_url = f"http://127.0.0.1:{args.port}/"
    print(f"Read! extraction verifier server running at {root_url}")
    print("Press Ctrl+C to stop.")

    if not args.no_open_browser:
        webbrowser.open(root_url)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
