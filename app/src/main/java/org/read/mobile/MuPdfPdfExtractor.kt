package org.read.mobile

import com.artifex.mupdf.fitz.Context
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Page

internal enum class PdfTextEngine {
    MuPdf,
    PdfBox
}

internal data class PdfExtractionCandidate(
    val engine: PdfTextEngine,
    val pageCount: Int,
    val pageTexts: List<String>
)

internal class MuPdfPdfExtractor {
    companion object {
        private val STRUCTURED_TEXT_OPTIONS = listOf(
            "segment,paragraph-break,dehyphenate,preserve-whitespace",
            "segment,dehyphenate,preserve-whitespace",
            "dehyphenate,preserve-whitespace",
            ""
        )

        init {
            Context.init()
        }
    }

    fun extract(bytes: ByteArray): PdfExtractionCandidate {
        val document = Document.openDocument(bytes, "application/pdf")
        try {
            val pageCount = document.countPages()
            val pageTexts = (0 until pageCount).map { pageIndex ->
                extractPageText(document.loadPage(pageIndex))
            }

            return PdfExtractionCandidate(
                engine = PdfTextEngine.MuPdf,
                pageCount = pageCount,
                pageTexts = pageTexts
            )
        } finally {
            document.destroy()
        }
    }

    private fun extractPageText(page: Page): String {
        try {
            for (options in STRUCTURED_TEXT_OPTIONS) {
                val text = runCatching { extractStructuredText(page, options) }.getOrNull()
                if (!text.isNullOrBlank()) {
                    return text
                }
            }
            return ""
        } finally {
            page.destroy()
        }
    }

    private fun extractStructuredText(page: Page, options: String): String {
        val structuredText = if (options.isBlank()) {
            page.toStructuredText()
        } else {
            page.toStructuredText(options)
        }

        try {
            return structuredText.asText().normalizeMuPdfPageText()
        } finally {
            structuredText.destroy()
        }
    }
}

private fun String.normalizeMuPdfPageText(): String {
    return replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("""[ \t]+\n"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
}
