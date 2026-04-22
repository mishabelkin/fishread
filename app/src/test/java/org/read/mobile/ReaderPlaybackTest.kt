package org.read.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPlaybackTest {

    @Test
    fun buildSpeechSegments_skipsMetadataAndFootnotes_andSplitsParagraphIntoSentences() {
        val blocks = listOf(
            ReaderBlock(ReaderBlockType.Metadata, "Alice Author"),
            ReaderBlock(ReaderBlockType.Heading, "Introduction"),
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "First sentence of the paragraph. Second sentence follows right after it."
            ),
            ReaderBlock(ReaderBlockType.Footnote, "1 A footnote that should not be spoken.")
        )

        val segments = buildSpeechSegments(blocks)

        assertEquals(3, segments.size)
        assertEquals("Introduction.", segments[0].text)
        assertEquals(1, segments[0].blockIndex)
        assertEquals(2, segments[1].blockIndex)
        assertEquals(2, segments[2].blockIndex)
        assertTrue(segments[1].text.startsWith("First sentence"))
        assertTrue(segments[2].text.startsWith("Second sentence"))
    }

    @Test
    fun playbackStore_seekToProgressFraction_movesToLaterSegment() {
        val blocks = listOf(
            ReaderBlock(ReaderBlockType.Heading, "Introduction"),
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "This is the first sentence. This is the second sentence. This is the third sentence."
            )
        )

        ReaderPlaybackStore.loadDocument(
            documentId = "test-doc-progress",
            title = "Test",
            blocks = blocks
        )

        ReaderPlaybackStore.seekToProgressFraction(0.8f)

        assertTrue(ReaderPlaybackStore.segmentCount() >= 4)
        assertTrue(ReaderPlaybackStore.uiState.value.currentSegmentIndex >= 2)
        assertTrue(ReaderPlaybackStore.currentPlaybackText().isNullOrBlank().not())
    }

    @Test
    fun playbackStore_progressLabel_includesRemainingTimeEstimate() {
        val blocks = listOf(
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "This is a longer paragraph with enough words to produce a useful time estimate. " +
                    "It keeps going so the playback label can show remaining reading time in addition to percent complete."
            )
        )

        ReaderPlaybackStore.loadDocument(
            documentId = "test-doc-time-estimate",
            title = "Test",
            blocks = blocks
        )
        ReaderPlaybackStore.setSpeed(1.0f)

        val label = ReaderPlaybackStore.progressLabel()

        assertTrue(label.contains("%"))
        assertTrue(label.contains("left"))
    }

    @Test
    fun playbackStore_visualState_staysAlignedWithCurrentSegmentAndRange() {
        val blocks = listOf(
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "First sentence. Second sentence follows."
            )
        )

        ReaderPlaybackStore.loadDocument(
            documentId = "test-doc-visual-state",
            title = "Test",
            blocks = blocks
        )
        ReaderPlaybackStore.setCurrentSegmentIndex(1)
        val currentSegment = ReaderPlaybackStore.currentSegment()
        ReaderPlaybackStore.markRange(start = 3, end = 9)

        val state = ReaderPlaybackStore.uiState.value
        val expectedRange = currentSegment?.let { (it.startOffset + 3) until (it.startOffset + 9) }

        assertEquals(currentSegment?.blockIndex ?: -1, state.currentBlockIndex)
        assertEquals(expectedRange, state.currentSegmentRange)
        assertEquals(state.currentBlockIndex, ReaderPlaybackStore.currentBlockIndex())
        assertEquals(state.currentSegmentRange, ReaderPlaybackStore.currentSegmentRange())
    }

    @Test
    fun buildSpeechSegments_stripsInlineCitationsFromSpeechText() {
        val blocks = listOf(
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "This claim has support (Smith, 2021; Jones, 2020) and more evidence [12, 13]."
            )
        )

        val segments = buildSpeechSegments(blocks)

        assertEquals(1, segments.size)
        assertFalse(segments[0].text.contains("Smith"))
        assertFalse(segments[0].text.contains("[12"))
        assertTrue(segments[0].text.contains("This claim has support"))
        assertTrue(segments[0].text.contains("more evidence"))
    }

    @Test
    fun buildSpeechSegments_stripsBracketedCitationLists() {
        val blocks = listOf(
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "The result has been replicated [12, 13, 15-18] across multiple studies."
            )
        )

        val segments = buildSpeechSegments(blocks)

        assertEquals(1, segments.size)
        assertFalse(segments[0].text.contains("[12"))
        assertFalse(segments[0].text.contains("15-18"))
        assertTrue(segments[0].text.contains("The result has been replicated"))
        assertTrue(segments[0].text.contains("across multiple studies"))
    }

    @Test
    fun buildSpeechSegments_stripsAlphanumericBracketedCitationLists() {
        val blocks = listOf(
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "The annotated examples [abc23, abc24] support the same conclusion."
            )
        )

        val segments = buildSpeechSegments(blocks)

        assertEquals(1, segments.size)
        assertFalse(segments[0].text.contains("abc23"))
        assertFalse(segments[0].text.contains("abc24"))
        assertTrue(segments[0].text.contains("The annotated examples"))
        assertTrue(segments[0].text.contains("support the same conclusion"))
    }

    @Test
    fun buildSpeechSegments_stripsPlusDelimitedBracketedCitationLists() {
        val blocks = listOf(
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "The synthetic samples [abc+32, abc+33] reinforce the same result."
            )
        )

        val segments = buildSpeechSegments(blocks)

        assertEquals(1, segments.size)
        assertFalse(segments[0].text.contains("abc+32"))
        assertFalse(segments[0].text.contains("abc+33"))
        assertTrue(segments[0].text.contains("The synthetic samples"))
        assertTrue(segments[0].text.contains("reinforce the same result"))
    }

    @Test
    fun buildSpeechSegments_skipsReferenceSectionBackMatter() {
        val blocks = listOf(
            ReaderBlock(ReaderBlockType.Heading, "Introduction"),
            ReaderBlock(ReaderBlockType.Paragraph, "Useful main text."),
            ReaderBlock(ReaderBlockType.Heading, "References"),
            ReaderBlock(ReaderBlockType.Paragraph, "[1] Smith, J. Example reference entry."),
            ReaderBlock(ReaderBlockType.Paragraph, "[2] Jones, A. Another reference entry.")
        )

        val segments = buildSpeechSegments(blocks)

        assertEquals(2, segments.size)
        assertEquals("Introduction.", segments[0].text)
        assertEquals("Useful main text.", segments[1].text)
    }

    @Test
    fun playbackStore_loadDocument_replacesPreviousDocumentState() {
        ReaderPlaybackStore.loadDocument(
            documentId = "doc-a",
            title = "Document A",
            blocks = listOf(ReaderBlock(ReaderBlockType.Paragraph, "Alpha paragraph."))
        )
        ReaderPlaybackStore.setCurrentSegmentIndex(0)

        ReaderPlaybackStore.loadDocument(
            documentId = "doc-b",
            title = "Document B",
            blocks = listOf(ReaderBlock(ReaderBlockType.Paragraph, "Beta paragraph."))
        )

        assertEquals("doc-b", ReaderPlaybackStore.uiState.value.currentDocumentId)
        assertEquals("Document B", ReaderPlaybackStore.uiState.value.currentTitle)
        assertTrue(ReaderPlaybackStore.currentPlaybackText()?.contains("Beta") == true)
    }

    @Test
    fun pronunciationHintsForPlayback_expandsUsAbbreviation() {
        val hints = pronunciationHintsForPlayback("The U.S. economy remained strong.")

        assertEquals(1, hints.size)
        assertEquals("United States", hints.first().spokenText)
        assertEquals(SpeechPronunciationHintKind.SubstituteText, hints.first().kind)
    }

    @Test
    fun pronunciationHintsForPlayback_spellsOutUnknownDottedAcronyms() {
        val hints = pronunciationHintsForPlayback("Recent A.I. systems improved quickly.")

        assertEquals(1, hints.size)
        assertEquals("A I", hints.first().spokenText)
        assertEquals(SpeechPronunciationHintKind.SpellOut, hints.first().kind)
    }

    @Test
    fun buildSpeechPlaybackPlan_rewritesUsAbbreviationForSpeech() {
        val plan = buildSpeechPlaybackPlan("The U.S. economy remained strong.")

        assertEquals("The United States economy remained strong.", plan.spokenText)
        assertEquals(4, plan.mapSpokenOffsetToOriginal(4))
        assertTrue(plan.mapSpokenOffsetToOriginal(10, preferEnd = true) in 6..8)
    }

    @Test
    fun buildSpeechPlaybackPlan_rewritesMrAbbreviationForSpeech() {
        val plan = buildSpeechPlaybackPlan("Mr. Smith spoke first.")

        assertEquals("Mister Smith spoke first.", plan.spokenText)
        assertEquals(0, plan.mapSpokenOffsetToOriginal(0))
        assertTrue(plan.mapSpokenOffsetToOriginal(5, preferEnd = true) in 2..3)
    }

    @Test
    fun buildSpeechSegments_keepsUsAbbreviationInsideSingleSentence() {
        val blocks = listOf(
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "The U.S. economy remained strong. Another sentence follows."
            )
        )

        val segments = buildSpeechSegments(blocks)

        assertEquals(2, segments.size)
        assertTrue(segments[0].text.contains("U.S. economy remained strong"))
        assertTrue(segments[1].text.startsWith("Another sentence"))
    }

    @Test
    fun buildSpeechSegments_keepsMrAbbreviationInsideSingleSentence() {
        val blocks = listOf(
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "Mr. Smith spoke first. Another sentence follows."
            )
        )

        val segments = buildSpeechSegments(blocks)

        assertEquals(2, segments.size)
        assertTrue(segments[0].text.contains("Mr. Smith spoke first"))
        assertTrue(segments[1].text.startsWith("Another sentence"))
    }

    @Test
    fun isActivePlaybackUtterance_matchesOnlyCurrentUtterance() {
        assertTrue(isActivePlaybackUtteranceForHeuristics("utt-1", "utt-1"))
        assertFalse(isActivePlaybackUtteranceForHeuristics("utt-1", "utt-2"))
        assertFalse(isActivePlaybackUtteranceForHeuristics(null, "utt-1"))
    }

    @Test
    fun shouldRetrySpeechStartup_allowsOnlySingleAutomaticRecovery() {
        assertTrue(shouldRetrySpeechStartupForHeuristics(hasRetriedSpeechStart = false))
        assertFalse(shouldRetrySpeechStartupForHeuristics(hasRetriedSpeechStart = true))
    }
}
