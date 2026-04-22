package org.read.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderCapturedTextTest {

    @Test
    fun splitCapturedTextIntoParagraphs_mergesWrappedLinesAndDehyphenates() {
        val paragraphs = splitCapturedTextIntoParagraphs(
            """
            A Wrapped Title

            This is a para-
            graph that should stay together.

            Second paragraph line one
            still continues here.
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "A Wrapped Title",
                "This is a paragraph that should stay together.",
                "Second paragraph line one still continues here."
            ),
            paragraphs
        )
    }

    @Test
    fun parseCapturedTextReaderContent_extractsTitleAndMetadata() {
        val parsed = parseCapturedTextReaderContent(
            rawText = """
                A Useful Paper

                Jane Doe, John Roe

                Department of Interesting Systems, Example University

                Introduction

                This is the first body paragraph.
            """.trimIndent(),
            providedTitle = null,
            fallbackTitle = "Captured text"
        )

        assertEquals("A Useful Paper", parsed.title)
        assertEquals(2, parsed.metadataBlocks.size)
        assertEquals(ReaderBlockType.Heading, parsed.blocks.first().type)
        assertTrue(parsed.blocks.last().text.contains("first body paragraph"))
    }
}
