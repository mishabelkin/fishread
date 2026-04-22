package org.read.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CleanupReportDiffTest {

    @Test
    fun buildCleanupDiffEntries_prefersStoredParagraphTexts() {
        val document = ReaderDocument(
            title = "Doc",
            sourceLabel = "source",
            kind = DocumentKind.WEB,
            pageCount = 1,
            paragraphCount = 1,
            headingCount = 0,
            metadataBlocks = emptyList(),
            footnoteBlocks = emptyList(),
            blocks = listOf(ReaderBlock(ReaderBlockType.Paragraph, "original")),
            presentation = ReaderPresentation(
                modelId = "different/model",
                promptVersion = 99,
                blocks = listOf(ReaderBlock(ReaderBlockType.Paragraph, "different cleaned"))
            ),
            cleanupDiagnostics = CleanupRunDiagnostics(
                modelId = "model",
                promptVersion = 1,
                totalParagraphs = 1,
                eligibleParagraphs = 1,
                totalChunks = 1,
                attemptedChunks = 1,
                acceptedChunks = 1,
                rejectedChunks = 0,
                changedParagraphs = 1,
                droppedParagraphs = 0,
                eligibleCharsBefore = 8,
                eligibleCharsAfter = 7,
                changeKindCounts = listOf(CleanupDiagnosticsCount("minor_rewrite", 1)),
                rejectionReasonCounts = emptyList(),
                chunkReports = listOf(
                    CleanupChunkReport(
                        firstBlockIndex = 0,
                        lastBlockIndex = 0,
                        status = CleanupChunkStatus.AcceptedChanged,
                        originalChars = 8,
                        cleanedChars = 7,
                        changedParagraphs = 1,
                        droppedParagraphs = 0,
                        paragraphReports = listOf(
                            CleanupParagraphReport(
                                blockIndex = 0,
                                changeKinds = listOf("minor_rewrite"),
                                beforeText = "stored before",
                                afterText = "stored after"
                            )
                        )
                    )
                )
            )
        )

        val entries = buildCleanupDiffEntries(document)

        assertEquals(1, entries.size)
        assertEquals("stored before", entries.single().beforeText)
        assertEquals("stored after", entries.single().afterText)
    }

    @Test
    fun buildCleanupDiffEntries_fallsBackToBlockComparisonForLegacyReports() {
        val document = ReaderDocument(
            title = "Doc",
            sourceLabel = "source",
            kind = DocumentKind.WEB,
            pageCount = 1,
            paragraphCount = 2,
            headingCount = 0,
            metadataBlocks = emptyList(),
            footnoteBlocks = emptyList(),
            blocks = listOf(
                ReaderBlock(ReaderBlockType.Paragraph, "before one"),
                ReaderBlock(ReaderBlockType.Paragraph, "before two")
            ),
            presentation = ReaderPresentation(
                modelId = "model",
                promptVersion = 1,
                blocks = listOf(
                    ReaderBlock(ReaderBlockType.Paragraph, "after one"),
                    ReaderBlock(ReaderBlockType.Paragraph, "before two")
                )
            ),
            cleanupDiagnostics = CleanupRunDiagnostics(
                modelId = "model",
                promptVersion = 1,
                totalParagraphs = 2,
                eligibleParagraphs = 2,
                totalChunks = 1,
                attemptedChunks = 1,
                acceptedChunks = 1,
                rejectedChunks = 0,
                changedParagraphs = 1,
                droppedParagraphs = 0,
                eligibleCharsBefore = 20,
                eligibleCharsAfter = 19,
                changeKindCounts = listOf(CleanupDiagnosticsCount("minor_rewrite", 1)),
                rejectionReasonCounts = emptyList(),
                chunkReports = listOf(
                    CleanupChunkReport(
                        firstBlockIndex = 0,
                        lastBlockIndex = 1,
                        status = CleanupChunkStatus.AcceptedChanged,
                        originalChars = 20,
                        cleanedChars = 19,
                        changedParagraphs = 1,
                        droppedParagraphs = 0,
                        paragraphReports = listOf(
                            CleanupParagraphReport(
                                blockIndex = 0,
                                changeKinds = listOf("minor_rewrite")
                            )
                        )
                    )
                )
            )
        )

        val entries = buildCleanupDiffEntries(document)

        assertEquals(1, entries.size)
        assertEquals("before one", entries.single().beforeText)
        assertEquals("after one", entries.single().afterText)
        assertTrue(entries.single().changeKinds.contains("minor_rewrite"))
    }
}
