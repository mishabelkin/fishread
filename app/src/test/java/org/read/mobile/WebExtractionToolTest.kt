package org.read.mobile

import java.io.File
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class WebExtractionToolTest {

    private data class BlockDecision(
        val rawBlock: WebExtractionDebugBlock,
        val finalBlock: ReaderBlock?
    ) {
        val included: Boolean
            get() = finalBlock != null
    }

    @Test
    fun previewExtractionFromSuppliedInput() {
        val urlInput = debugValue("read.debugInputUrl", "READ_DEBUG_INPUT_URL")
            ?: debugValue("read.debugWebUrl", "READ_DEBUG_WEB_URL")
        val htmlFile = debugValue("read.debugWebHtmlFile", "READ_DEBUG_WEB_HTML_FILE")
        val rawHtml = debugValue("read.debugWebRawHtml", "READ_DEBUG_WEB_RAW_HTML")
        val pdfFile = debugValue("read.debugPdfFile", "READ_DEBUG_PDF_FILE")
        val outputFile = debugValue("read.debugOutputFile", "READ_DEBUG_OUTPUT_FILE")
            ?: debugValue("read.debugWebOutputFile", "READ_DEBUG_WEB_OUTPUT_FILE")
        val defaultOutput = File("app/build/reports/extraction-tool/latest.html").absoluteFile

        if (urlInput == null && htmlFile == null && rawHtml == null && pdfFile == null) {
            println(
                """
                Extraction verifier usage:
                  -Url or -Dread.debugInputUrl=<page url>
                  -HtmlFile or -Dread.debugWebHtmlFile=<path to saved html>
                  -PdfFile or -Dread.debugPdfFile=<path to saved pdf>
                  -OutputFile or -Dread.debugOutputFile=<html report path, optional>
                """.trimIndent()
            )
            return
        }

        val effectiveSourceLabel = urlInput
            ?: htmlFile?.let { File(it).toURI().toString() }
            ?: pdfFile?.let { File(it).toURI().toString() }
            ?: "debug://local-web-extraction"

        val reportHtml = runCatching {
            val loader = ReaderDocumentLoader(RuntimeEnvironment.getApplication())
            val artifact = when {
                pdfFile != null -> {
                    val file = File(pdfFile)
                    ReaderExtractionDebugArtifact.Pdf(
                        sourceLabel = file.toURI().toString(),
                        report = loader.buildPdfExtractionDebugReport(
                            bytes = file.readBytes(),
                            title = file.nameWithoutExtension.ifBlank { file.name },
                            sourceLabel = file.toURI().toString()
                        )
                    )
                }

                rawHtml != null || htmlFile != null -> {
                    val resolvedSource = urlInput
                        ?: htmlFile?.let { File(it).toURI().toString() }
                        ?: "debug://local-web-extraction"
                    val html = rawHtml ?: File(htmlFile!!).readText()
                    ReaderExtractionDebugArtifact.Web(
                        sourceLabel = resolvedSource,
                        html = html,
                        report = loader.buildWebExtractionDebugReport(
                            html = html,
                            sourceLabel = resolvedSource,
                            title = resolvedSource
                        )
                    )
                }

                else -> loader.buildRemoteExtractionDebugArtifact(urlInput!!)
            }

            renderReport(artifact)
        }.getOrElse { error ->
            renderFailureReport(
                sourceLabel = effectiveSourceLabel,
                error = error
            )
        }

        val reportFile = File(outputFile ?: defaultOutput.path).absoluteFile
        reportFile.parentFile?.mkdirs()
        reportFile.writeText(reportHtml)
        println("Saved extraction report to ${reportFile.absolutePath}")
    }

    private fun renderReport(artifact: ReaderExtractionDebugArtifact): String {
        val body = when (artifact) {
            is ReaderExtractionDebugArtifact.Web -> renderWebReport(artifact)
            is ReaderExtractionDebugArtifact.Pdf -> renderPdfReport(artifact)
        }
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>Read! Extraction Verifier</title>
              <style>
                body { font-family: Segoe UI, Arial, sans-serif; margin: 0; background: #f5f7fb; color: #1f2937; }
                main { max-width: 1260px; margin: 0 auto; padding: 24px; }
                h1, h2, h3 { margin: 0 0 12px; }
                h1 { font-size: 30px; }
                h2 { font-size: 22px; margin-top: 24px; }
                h3 { font-size: 17px; margin-top: 18px; }
                p { line-height: 1.5; }
                ul { margin: 8px 0 0 18px; padding: 0; }
                li { margin: 6px 0; }
                .muted { color: #667085; }
                .grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 12px; }
                .card { background: white; border: 1px solid #d9e2f1; border-radius: 14px; padding: 16px; box-shadow: 0 2px 8px rgba(15, 23, 42, 0.05); }
                .metric { font-size: 12px; color: #667085; text-transform: uppercase; letter-spacing: 0.04em; }
                .value { font-size: 16px; font-weight: 600; margin-top: 6px; word-break: break-word; }
                table { width: 100%; border-collapse: collapse; background: white; border: 1px solid #d9e2f1; border-radius: 12px; overflow: hidden; }
                th, td { padding: 10px 12px; border-bottom: 1px solid #e5ebf5; text-align: left; vertical-align: top; }
                th { font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; color: #667085; background: #f8fafc; }
                tr.selected { background: #ecfdf3; }
                .blocks { display: grid; gap: 12px; }
                .block { background: white; border: 1px solid #d9e2f1; border-radius: 12px; padding: 14px; }
                .block-type { font-size: 12px; color: #667085; text-transform: uppercase; letter-spacing: 0.04em; margin-bottom: 8px; }
                .block-text { white-space: pre-wrap; line-height: 1.55; }
                code, pre { font-family: Consolas, monospace; }
                pre { background: #0f172a; color: #e2e8f0; padding: 14px; border-radius: 12px; overflow-x: auto; }
                details { margin-top: 12px; }
                summary { cursor: pointer; font-weight: 600; }
                .pill { display: inline-block; background: #dbeafe; color: #1d4ed8; border-radius: 999px; padding: 4px 10px; font-size: 12px; font-weight: 600; margin-right: 8px; }
                .subtle { background: #eef2ff; color: #4338ca; }
                .success { background: #dcfce7; color: #166534; }
                .danger { background: #fee2e2; color: #991b1b; }
                .comparison-row { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; align-items: stretch; }
                .comparison-card { background: white; border: 1px solid #d9e2f1; border-radius: 12px; padding: 14px; min-height: 92px; }
                .comparison-card.dropped { border-color: #fca5a5; background: #fff1f2; }
                .comparison-card.final { border-color: #86efac; background: #f0fdf4; }
                .comparison-label { font-size: 12px; color: #667085; text-transform: uppercase; letter-spacing: 0.04em; margin-bottom: 8px; }
                .comparison-text { white-space: pre-wrap; line-height: 1.55; }
              </style>
            </head>
            <body>
              <main>
                <h1>Read! Extraction Verifier</h1>
                <p><a href="/" onclick="if (window.history.length > 1) { window.history.back(); return false; }">&larr; Back to verifier form</a></p>
                <p class="muted">This report uses the same extraction pipeline as the Android app, with the same block scoring and cleanup logic. Desktop results can still differ from the phone for WebView challenges, DNS quirks, or accessibility capture.</p>
                $body
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderWebReport(artifact: ReaderExtractionDebugArtifact.Web): String {
        val report = artifact.report
        val decisions = classifyBlockDecisions(report.rawBlocks, report.finalBlocks)
        val document = ReaderDocument(
            title = report.title,
            sourceLabel = artifact.sourceLabel,
            kind = DocumentKind.WEB,
            pageCount = 1,
            paragraphCount = report.finalBlocks.count { it.type == ReaderBlockType.Paragraph },
            headingCount = report.finalBlocks.count { it.type == ReaderBlockType.Heading },
            metadataBlocks = report.metadataBlocks,
            footnoteBlocks = emptyList(),
            blocks = report.finalBlocks
        )
        return buildString {
            appendLine(renderDocumentSummaryCards(document, artifact.sourceLabel, "Web"))

            appendLine("<h2>Web Extraction Strategy</h2>")
            appendLine("<div class='card'>")
            appendLine("<p><span class='pill'>Chosen: ${escapeHtml(report.chosenStrategy)}</span><span class='pill subtle'>Sentence fallback: ${if (report.sentenceFallbackUsed) "yes" else "no"}</span></p>")
            appendLine("<p><span class='pill success'>Included: ${decisions.count { it.included }}</span><span class='pill danger'>Dropped: ${decisions.count { !it.included }}</span></p>")
            appendLine("<p class='muted'>Candidate containers and strategy scoring come from the same heuristics used by Read! on Android.</p>")
            appendLine("</div>")

            appendLine(renderSideBySideSection("Original vs cleaned (chosen strategy)", decisions))

            appendLine("<h2>Candidate Containers</h2>")
            appendLine("<table><thead><tr><th>Container</th><th>Score</th><th>Paragraphs</th><th>Headings</th><th>Chars</th><th>Link density</th></tr></thead><tbody>")
            if (report.candidateSummaries.isEmpty()) {
                appendLine("<tr><td colspan='6' class='muted'>No candidate container scoring was recorded for this document.</td></tr>")
            } else {
                report.candidateSummaries.forEach { candidate ->
                    appendLine("<tr><td>${escapeHtml(candidate.label)}</td><td>${formatScore(candidate.score)}</td><td>${candidate.paragraphCount}</td><td>${candidate.headingCount}</td><td>${candidate.textLength}</td><td>${formatPercent(candidate.linkDensity)}</td></tr>")
                }
            }
            appendLine("</tbody></table>")

            appendLine("<h2>Strategy Outcomes</h2>")
            appendLine("<table><thead><tr><th>Strategy</th><th>Score</th><th>Blocks</th><th>Total chars</th></tr></thead><tbody>")
            report.strategies.forEach { strategy ->
                val selectedClass = if (strategy.name == report.chosenStrategy) " class='selected'" else ""
                appendLine("<tr$selectedClass><td>${escapeHtml(strategy.name)}</td><td>${formatScore(strategy.score)}</td><td>${strategy.blockCount}</td><td>${strategy.totalChars}</td></tr>")
            }
            appendLine("</tbody></table>")

            appendLine(renderBlocksSection("Metadata", report.metadataBlocks))
            appendLine(renderBlocksSection("Final blocks", report.finalBlocks))
            appendLine(renderDebugBlocksSection("Raw blocks before final cleanup", report.rawBlocks))
            appendLine(renderStrategyDetails(report.strategies))

            appendLine("<details>")
            appendLine("<summary>HTML source preview</summary>")
            appendLine("<pre>${escapeHtml(truncate(artifact.html, 24000))}</pre>")
            appendLine("</details>")
        }
    }

    private fun renderPdfReport(artifact: ReaderExtractionDebugArtifact.Pdf): String {
        val report = artifact.report
        return buildString {
            appendLine(renderDocumentSummaryCards(report.document, artifact.sourceLabel, "PDF"))

            appendLine("<h2>PDF Engine Comparison</h2>")
            appendLine("<table><thead><tr><th>Engine</th><th>Score</th><th>Pages</th><th>Metadata</th><th>Footnotes</th><th>Content blocks</th></tr></thead><tbody>")
            report.candidates.forEach { candidate ->
                val selectedClass = if (candidate.engine == report.selectedEngine) " class='selected'" else ""
                appendLine("<tr$selectedClass><td>${escapeHtml(candidate.engine.name)}</td><td>${candidate.score}</td><td>${candidate.pageCount}</td><td>${candidate.metadataBlocks.size}</td><td>${candidate.footnoteBlocks.size}</td><td>${candidate.contentBlocks.size}</td></tr>")
            }
            appendLine("</tbody></table>")

            appendLine(renderBlocksSection("Selected metadata", report.document.metadataBlocks))
            appendLine(renderBlocksSection("Selected footnotes", report.document.footnoteBlocks))
            appendLine(renderBlocksSection("Selected content blocks", report.document.blocks))

            report.candidates.forEach { candidate ->
                appendLine("<details>")
                appendLine("<summary>${escapeHtml(candidate.engine.name)} candidate blocks</summary>")
                appendLine(renderBlocksSection("${candidate.engine.name} content blocks", candidate.contentBlocks))
                appendLine("</details>")
            }
        }
    }

    private fun renderFailureReport(sourceLabel: String, error: Throwable): String {
        val stack = error.stackTraceToString()
        val summary = error.message?.ifBlank { null } ?: error::class.java.simpleName
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>Read! Extraction Verifier</title>
              <style>
                body { font-family: Segoe UI, Arial, sans-serif; margin: 0; background: #f5f7fb; color: #1f2937; }
                main { max-width: 1100px; margin: 0 auto; padding: 24px; }
                .card { background: white; border: 1px solid #d9e2f1; border-radius: 14px; padding: 16px; box-shadow: 0 2px 8px rgba(15, 23, 42, 0.05); margin-top: 16px; }
                .danger { color: #991b1b; }
                .muted { color: #667085; }
                pre { background: #0f172a; color: #e2e8f0; padding: 14px; border-radius: 12px; overflow-x: auto; white-space: pre-wrap; }
              </style>
            </head>
            <body>
              <main>
                <h1>Read! Extraction Verifier</h1>
                <p><a href="/" onclick="if (window.history.length > 1) { window.history.back(); return false; }">&larr; Back to verifier form</a></p>
                <div class="card">
                  <h2 class="danger">Remote load failed</h2>
                  <p><strong>Source:</strong> ${escapeHtml(sourceLabel)}</p>
                  <p><strong>Error:</strong> ${escapeHtml(summary)}</p>
                  <p class="muted">The verifier stayed up and wrote this report, but the remote page could not be opened through the Android-style loader path. This is common with paywalls, login-gated pages, challenge pages, or sites that rely on behaviors Robolectric WebView does not reproduce well.</p>
                </div>
                <div class="card">
                  <h2>Recommended next steps</h2>
                  <ul>
                    <li>Use the verifier form to load a saved HTML file for this page.</li>
                    <li>Paste raw HTML from the browser into the verifier.</li>
                    <li>If the phone app opens the page but the verifier cannot, compare this error against the phone path rather than treating it as an extractor failure.</li>
                  </ul>
                </div>
                <div class="card">
                  <h2>Stack trace</h2>
                  <pre>${escapeHtml(stack)}</pre>
                </div>
              </main>
            </body>
            </html>
        """.trimIndent()
    }

    private fun renderDocumentSummaryCards(document: ReaderDocument, sourceLabel: String, kindLabel: String): String {
        return buildString {
            appendLine("<div class='grid'>")
            appendLine(renderCard("Input kind", kindLabel))
            appendLine(renderCard("Title", document.title))
            appendLine(renderCard("Source", sourceLabel))
            appendLine(renderCard("Paragraphs", document.paragraphCount.toString()))
            appendLine(renderCard("Headings", document.headingCount.toString()))
            appendLine(renderCard("Metadata blocks", document.metadataBlocks.size.toString()))
            appendLine(renderCard("Footnote blocks", document.footnoteBlocks.size.toString()))
            appendLine(renderCard("Display blocks", document.blocks.size.toString()))
            appendLine("</div>")
        }
    }

    private fun renderCard(label: String, value: String): String {
        return "<div class='card'><div class='metric'>${escapeHtml(label)}</div><div class='value'>${escapeHtml(value)}</div></div>"
    }

    private fun renderBlocksSection(title: String, blocks: List<ReaderBlock>): String {
        return buildString {
            appendLine("<h2>${escapeHtml(title)}</h2>")
            if (blocks.isEmpty()) {
                appendLine("<div class='card muted'>No blocks recorded.</div>")
            } else {
                appendLine("<div class='blocks'>")
                blocks.forEachIndexed { index, block ->
                    appendLine("<div class='block'>")
                    appendLine("<div class='block-type'>${index + 1}. ${escapeHtml(block.type.name)}</div>")
                    appendLine("<div class='block-text'>${escapeHtml(block.text)}</div>")
                    appendLine("</div>")
                }
                appendLine("</div>")
            }
        }
    }

    private fun renderDebugBlocksSection(title: String, blocks: List<WebExtractionDebugBlock>): String {
        return buildString {
            appendLine("<h2>${escapeHtml(title)}</h2>")
            if (blocks.isEmpty()) {
                appendLine("<div class='card muted'>No blocks recorded.</div>")
            } else {
                appendLine("<div class='blocks'>")
                blocks.forEachIndexed { index, block ->
                    appendLine("<div class='block'>")
                    appendLine("<div class='block-type'>${index + 1}. ${escapeHtml(block.type.name)}</div>")
                    appendLine("<div class='block-text'>${escapeHtml(block.text)}</div>")
                    if (block.links.isNotEmpty()) {
                        appendLine("<details>")
                        appendLine("<summary>Links (${block.links.size})</summary>")
                        appendLine(renderLinkList(block.links))
                        appendLine("</details>")
                    }
                    appendLine("</div>")
                }
                appendLine("</div>")
            }
        }
    }

    private fun renderSideBySideSection(title: String, decisions: List<BlockDecision>): String {
        return buildString {
            appendLine("<h2>${escapeHtml(title)}</h2>")
            if (decisions.isEmpty()) {
                appendLine("<div class='card muted'>No chosen-strategy blocks were recorded.</div>")
            } else {
                appendLine("<div class='card'>")
                appendLine("<p class='muted'>Left is the original chosen-strategy text before final cleanup. Right is what survived into the final document. Blank right-hand entries were dropped.</p>")
                appendLine("</div>")
                appendLine("<div class='blocks'>")
                decisions.forEachIndexed { index, decision ->
                    val finalCardClass = if (decision.included) "comparison-card final" else "comparison-card dropped"
                    val finalLabel = if (decision.included) "Cleaned / kept" else "Dropped"
                    val finalText = decision.finalBlock?.text ?: "Dropped during final cleanup"
                    appendLine("<div class='comparison-row'>")
                    appendLine("<div class='comparison-card'>")
                    appendLine("<div class='comparison-label'>${index + 1}. Original ${escapeHtml(decision.rawBlock.type.name)}</div>")
                    appendLine("<div class='comparison-text'>${escapeHtml(decision.rawBlock.text)}</div>")
                    if (decision.rawBlock.links.isNotEmpty()) {
                        appendLine("<details>")
                        appendLine("<summary>Links (${decision.rawBlock.links.size})</summary>")
                        appendLine(renderLinkList(decision.rawBlock.links))
                        appendLine("</details>")
                    }
                    appendLine("</div>")
                    appendLine("<div class='$finalCardClass'>")
                    appendLine("<div class='comparison-label'>$finalLabel</div>")
                    appendLine("<div class='comparison-text'>${escapeHtml(finalText)}</div>")
                    appendLine("</div>")
                    appendLine("</div>")
                }
                appendLine("</div>")
            }
        }
    }

    private fun renderStrategyDetails(strategies: List<WebExtractionDebugStrategy>): String {
        return buildString {
            appendLine("<h2>Strategy block previews</h2>")
            if (strategies.isEmpty()) {
                appendLine("<div class='card muted'>No strategy block previews were recorded.</div>")
            } else {
                strategies.forEach { strategy ->
                    appendLine("<details>")
                    appendLine("<summary>${escapeHtml(strategy.name)} - ${strategy.blockCount} blocks - ${strategy.totalChars} chars</summary>")
                    appendLine(renderDebugBlocksSection("${strategy.name} blocks", strategy.blocks))
                    appendLine("</details>")
                }
            }
        }
    }

    private fun classifyBlockDecisions(
        rawBlocks: List<WebExtractionDebugBlock>,
        finalBlocks: List<ReaderBlock>
    ): List<BlockDecision> {
        val remainingFinalBlocks = finalBlocks.toMutableList()
        return rawBlocks.map { rawBlock ->
            val index = remainingFinalBlocks.indexOfFirst { candidate ->
                candidate.type == rawBlock.type && candidate.text == rawBlock.text
            }
            if (index >= 0) {
                val matched = remainingFinalBlocks.removeAt(index)
                BlockDecision(rawBlock = rawBlock, finalBlock = matched)
            } else {
                BlockDecision(rawBlock = rawBlock, finalBlock = null)
            }
        }
    }

    private fun renderLinkList(links: List<WebExtractionDebugLink>): String {
        return buildString {
            appendLine("<ul>")
            links.forEach { link ->
                val label = escapeHtml(link.label)
                val href = escapeHtml(link.href)
                appendLine("<li><a href=\"$href\" target=\"_blank\" rel=\"noreferrer\">$label</a><div class='muted'>$href</div></li>")
            }
            appendLine("</ul>")
        }
    }

    private fun formatScore(score: Double): String {
        return if (score.isFinite()) "%.1f".format(score) else score.toString()
    }

    private fun formatPercent(value: Double): String {
        return "%.1f%%".format(value * 100.0)
    }

    private fun truncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) {
            return text
        }
        return text.take(maxChars) + "\n\n[truncated after $maxChars chars]"
    }

    private fun escapeHtml(value: String): String {
        return buildString(value.length) {
            value.forEach { char ->
                when (char) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&#39;")
                    else -> append(char)
                }
            }
        }
    }

    private fun debugValue(propertyKey: String, envKey: String): String? {
        return System.getProperty(propertyKey)
            ?.takeIf { it.isNotBlank() }
            ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }
    }
}
