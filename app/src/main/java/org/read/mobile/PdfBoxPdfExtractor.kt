package org.read.mobile

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

internal class PdfBoxPdfExtractor {
    fun extract(bytes: ByteArray): PdfExtractionCandidate {
        PDDocument.load(bytes).use { document ->
            val pageTexts = (1..document.numberOfPages).map { pageNumber ->
                PDFTextStripper().apply {
                    sortByPosition = true
                    setShouldSeparateByBeads(true)
                    startPage = pageNumber
                    endPage = pageNumber
                }.getText(document)
            }

            return PdfExtractionCandidate(
                engine = PdfTextEngine.PdfBox,
                pageCount = document.numberOfPages,
                pageTexts = pageTexts
            )
        }
    }
}
