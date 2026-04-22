package org.read.mobile

import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDocumentLoaderPdfSelectionTest {

    @Test
    fun scorePdfExtractionForSelection_prefers_author_and_abstract_metadata_over_noisy_body_leaks() {
        val clean = FormattedReaderContent(
            displayTitle = "MATHEMATICAL METHODS AND HUMAN THOUGHT IN THE AGE OF AI",
            metadataBlocks = listOf(
                ReaderBlock(ReaderBlockType.Metadata, "MATHEMATICAL METHODS AND HUMAN THOUGHT IN THE AGE OF AI"),
                ReaderBlock(ReaderBlockType.Metadata, "TANYA KLOWDEN AND TERENCE TAO"),
                ReaderBlock(ReaderBlockType.Metadata, "Abstract"),
                ReaderBlock(
                    ReaderBlockType.Metadata,
                    "Artificial intelligence (AI) is the name popularly given to a broad spectrum of computer tools designed to perform increasingly complex cognitive tasks."
                )
            ),
            contentBlocks = listOf(
                ReaderBlock(ReaderBlockType.Heading, "1. Introduction"),
                ReaderBlock(
                    ReaderBlockType.Paragraph,
                    "This is the real opening paragraph of the paper body and should remain in the reader text."
                ),
                ReaderBlock(
                    ReaderBlockType.Paragraph,
                    "This follow-on paragraph should stay in the paper body without the arXiv banner being read aloud."
                )
            ),
            footnoteBlocks = emptyList()
        )

        val noisy = FormattedReaderContent(
            displayTitle = "MATHEMATICAL METHODS AND HUMAN THOUGHT IN THE AGE OF AI",
            metadataBlocks = emptyList(),
            contentBlocks = listOf(
                ReaderBlock(ReaderBlockType.Heading, "MATHEMATICAL METHODS AND HUMAN THOUGHT IN THE AGE OF AI"),
                ReaderBlock(ReaderBlockType.Heading, "TANYA KLOWDEN AND TERENCE TAO"),
                ReaderBlock(ReaderBlockType.Paragraph, "Abstract. Artificial intelligence (AI) is the name popularly given to a broad spectrum of computer tools designed to perform increasingly complex cognitive tasks."),
                ReaderBlock(ReaderBlockType.Paragraph, "arXiv:2603.26524v1 [math.HO] 27 Mar 2026"),
                ReaderBlock(ReaderBlockType.Paragraph, "2 TANYA KLOWDEN AND TERENCE TAO"),
                ReaderBlock(ReaderBlockType.Heading, "1. Introduction"),
                ReaderBlock(
                    ReaderBlockType.Paragraph,
                    "This is the real opening paragraph of the paper body and should remain in the reader text."
                )
            ),
            footnoteBlocks = emptyList()
        )

        assertTrue(
            scorePdfExtractionForSelection(clean) > scorePdfExtractionForSelection(noisy)
        )
    }
}
