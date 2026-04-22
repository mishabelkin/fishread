package org.read.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadAccessibilityServiceTest {

    @Test
    fun shouldResumeExistingReaderSession_resumesSpeakingLoadedDocument() {
        assertTrue(
            shouldResumeExistingReaderSession(
                PlaybackUiState(
                    currentDocumentId = "read-capture://article/1",
                    isSpeaking = true,
                    hasSegments = true
                )
            )
        )
    }

    @Test
    fun shouldResumeExistingReaderSession_doesNotResumeStoppedNonPdfSession() {
        assertFalse(
            shouldResumeExistingReaderSession(
                PlaybackUiState(
                    currentDocumentId = "read-capture://article/1",
                    isSpeaking = false,
                    hasSegments = true
                )
            )
        )
    }

    @Test
    fun shouldResumeExistingReaderSession_doesNotResumePausedPdfSessionByItself() {
        assertFalse(
            shouldResumeExistingReaderSession(
                PlaybackUiState(
                    currentDocumentId = "https://arxiv.org/pdf/2603.26524",
                    isSpeaking = false,
                    hasSegments = true
                )
            )
        )
    }

    @Test
    fun looksLikePdfSessionDocumentId_matchesPdfSources() {
        assertTrue(looksLikePdfSessionDocumentId("https://arxiv.org/pdf/2603.26524"))
        assertTrue(looksLikePdfSessionDocumentId("https://example.com/paper.pdf"))
        assertTrue(looksLikePdfSessionDocumentId("content://org.read.mobile/cache/paper"))
        assertTrue(looksLikePdfSessionDocumentId("file:///storage/emulated/0/Download/paper.pdf"))
        assertFalse(looksLikePdfSessionDocumentId("https://example.com/article"))
    }

    @Test
    fun looksLikeBrowserOpenableUrl_matchesHttpPages() {
        assertTrue(looksLikeBrowserOpenableUrl("https://example.com/article"))
        assertTrue(looksLikeBrowserOpenableUrl("http://example.com/paper.pdf"))
        assertFalse(looksLikeBrowserOpenableUrl("content://org.read.mobile/cache/paper"))
        assertFalse(looksLikeBrowserOpenableUrl("read-capture://article/1"))
    }

    @Test
    fun normalizeAccessibilityUrlCandidate_extractsPdfUrlFromAddressBarText() {
        assertEquals(
            listOf("https://arxiv.org/pdf/2603.26524"),
            normalizeAccessibilityUrlCandidate("https://arxiv.org/pdf/2603.26524")
        )
        assertEquals(
            listOf("https://arxiv.org/pdf/2603.26524"),
            normalizeAccessibilityUrlCandidate("arxiv.org/pdf/2603.26524")
        )
    }

    @Test
    fun normalizeAccessibilityUrlCandidate_extractsArticleUrlFromAddressBarText() {
        assertEquals(
            listOf("https://example.com/news/story"),
            normalizeAccessibilityUrlCandidate("https://example.com/news/story")
        )
        assertEquals(
            listOf("https://example.com/news/story"),
            normalizeAccessibilityUrlCandidate("example.com/news/story")
        )
    }

    @Test
    fun chooseBestAccessibilityUrlCandidate_prefersAddressBarSignal() {
        val chosen = chooseBestAccessibilityUrlCandidate(
            listOf(
                AccessibilityUrlCandidate(
                    url = "https://example.com/related-link",
                    score = 1
                ),
                AccessibilityUrlCandidate(
                    url = "https://example.com/current-article",
                    score = 8
                )
            )
        )

        assertEquals("https://example.com/current-article", chosen)
    }

    @Test
    fun chooseBestAccessibilityPdfUrlCandidate_prefersAddressBarSignal() {
        val chosen = chooseBestAccessibilityPdfUrlCandidate(
            listOf(
                AccessibilityUrlCandidate(
                    url = "https://example.com/article-about-a-paper",
                    score = 1
                ),
                AccessibilityUrlCandidate(
                    url = "https://arxiv.org/pdf/2603.26524",
                    score = 8
                ),
                AccessibilityUrlCandidate(
                    url = "https://arxiv.org/pdf/2603.26524",
                    score = 3
                )
            )
        )

        assertEquals("https://arxiv.org/pdf/2603.26524", chosen)
    }

    @Test
    fun chooseBestAccessibilityPdfUrlCandidate_handlesTwoPageBrowserNoise() {
        val chosen = chooseBestAccessibilityPdfUrlCandidate(
            listOf(
                AccessibilityUrlCandidate(
                    url = "https://arxiv.org/pdf/2603.26524#page=2",
                    score = 8
                ),
                AccessibilityUrlCandidate(
                    url = "https://arxiv.org/pdf/2603.26524",
                    score = 6
                )
            )
        )

        assertEquals("https://arxiv.org/pdf/2603.26524#page=2", chosen)
    }

    @Test
    fun shouldResumeExistingReaderSession_requiresLoadedDocument() {
        assertFalse(
            shouldResumeExistingReaderSession(
                PlaybackUiState(
                    currentDocumentId = null,
                    isSpeaking = true,
                    hasSegments = true
                )
            )
        )
    }

    @Test
    fun shouldReopenReaderForCurrentPdfUrl_reopensSamePausedPdfDocument() {
        assertTrue(
            shouldReopenReaderForCurrentPdfUrl(
                state = PlaybackUiState(
                    currentDocumentId = "https://arxiv.org/pdf/2603.26524",
                    isSpeaking = false,
                    hasSegments = true
                ),
                currentPdfUrl = "https://arxiv.org/pdf/2603.26524#page=2"
            )
        )
    }

    @Test
    fun shouldReopenReaderForCurrentPdfUrl_doesNotReopenDifferentPausedPdfDocument() {
        assertFalse(
            shouldReopenReaderForCurrentPdfUrl(
                state = PlaybackUiState(
                    currentDocumentId = "https://arxiv.org/pdf/2603.26524",
                    isSpeaking = false,
                    hasSegments = true
                ),
                currentPdfUrl = "https://arxiv.org/pdf/2604.12345"
            )
        )
    }

    @Test
    fun shouldReopenReaderForCurrentPdfUrl_alwaysReopensWhileSpeaking() {
        assertTrue(
            shouldReopenReaderForCurrentPdfUrl(
                state = PlaybackUiState(
                    currentDocumentId = "https://arxiv.org/pdf/2603.26524",
                    isSpeaking = true,
                    hasSegments = true
                ),
                currentPdfUrl = "https://arxiv.org/pdf/2604.12345"
            )
        )
    }

    @Test
    fun shouldReopenReaderForCurrentUrl_reopensSamePausedWebDocument() {
        assertTrue(
            shouldReopenReaderForCurrentUrl(
                state = PlaybackUiState(
                    currentDocumentId = "https://example.com/news/story",
                    isSpeaking = false,
                    hasSegments = true
                ),
                currentUrl = "https://example.com/news/story#comments"
            )
        )
    }

    @Test
    fun shouldReopenReaderForCurrentUrl_doesNotReopenDifferentPausedWebDocument() {
        assertFalse(
            shouldReopenReaderForCurrentUrl(
                state = PlaybackUiState(
                    currentDocumentId = "https://example.com/news/story",
                    isSpeaking = false,
                    hasSegments = true
                ),
                currentUrl = "https://example.com/other-story"
            )
        )
    }

    @Test
    fun mergeAccessibilityBlocks_joinsWrappedLinesIntoParagraphs() {
        val merged = mergeAccessibilityBlocks(
            listOf(
                block(text = "This is a para-", top = 10, left = 20, bottom = 30),
                block(text = "graph that should read smoothly.", top = 33, left = 20, bottom = 54),
                block(text = "A new paragraph starts here.", top = 88, left = 20, bottom = 108)
            )
        )

        assertEquals(
            listOf(
                "This is a paragraph that should read smoothly.",
                "A new paragraph starts here."
            ),
            merged
        )
    }

    @Test
    fun mergeAccessibilityBlocks_filtersShortClickableUiNoise() {
        val merged = mergeAccessibilityBlocks(
            listOf(
                block(text = "Back", top = 0, left = 0, bottom = 12, isClickable = true),
                block(text = "Share", top = 0, left = 70, bottom = 12, isClickable = true),
                block(text = "This is the article paragraph we want to keep intact.", top = 40, left = 18, bottom = 70)
            )
        )

        assertEquals(listOf("This is the article paragraph we want to keep intact."), merged)
    }

    @Test
    fun shouldMergeAccessibilityBlocks_requiresNearbyAlignedBodyBlocks() {
        assertTrue(
            shouldMergeAccessibilityBlocks(
                previous = block(text = "First line without ending punctuation", top = 0, left = 10, bottom = 20),
                next = block(text = "continues here", top = 24, left = 12, bottom = 44)
            )
        )

        assertFalse(
            shouldMergeAccessibilityBlocks(
                previous = block(text = "Far away line", top = 0, left = 10, bottom = 20),
                next = block(text = "other column", top = 24, left = 180, bottom = 44)
            )
        )
    }

    @Test
    fun mergeAccessibilityBlocks_trimsPeripheralUiNoiseAroundBody() {
        val merged = mergeAccessibilityBlocks(
            listOf(
                block(text = "Open in app", top = 0, left = 10, bottom = 12, isClickable = true),
                block(text = "A Good Article Title", top = 22, left = 14, bottom = 42, isHeading = true),
                block(text = "This is the first real body paragraph with enough content to count as substantial reading text.", top = 60, left = 14, bottom = 100),
                block(text = "This is the second real body paragraph, again long enough to count as the main content.", top = 110, left = 14, bottom = 150),
                block(text = "Share", top = 162, left = 14, bottom = 174, isClickable = true)
            )
        )

        assertEquals(
            listOf(
                "A Good Article Title",
                "This is the first real body paragraph with enough content to count as substantial reading text.",
                "This is the second real body paragraph, again long enough to count as the main content."
            ),
            merged
        )
    }

    @Test
    fun mergeAccessibilityBlocks_filtersMisalignedInlineNoise() {
        val merged = mergeAccessibilityBlocks(
            listOf(
                block(
                    text = "This is the first real paragraph with enough detail to count as substantial article text.",
                    top = 40,
                    left = 16,
                    bottom = 80,
                    right = 340
                ),
                block(
                    text = "Related",
                    top = 88,
                    left = 220,
                    bottom = 104,
                    right = 288
                ),
                block(
                    text = "This is the second real paragraph, again long enough and aligned with the article body.",
                    top = 112,
                    left = 16,
                    bottom = 152,
                    right = 344
                )
            )
        )

        assertEquals(
            listOf(
                "This is the first real paragraph with enough detail to count as substantial article text.",
                "This is the second real paragraph, again long enough and aligned with the article body."
            ),
            merged
        )
    }

    @Test
    fun joinAccessibilityBlockText_keepsDashWrappedContinuationTight() {
        assertEquals(
            "alpha\u2014beta",
            joinAccessibilityBlockText("alpha\u2014", "beta")
        )
    }

    private fun block(
        text: String,
        top: Int,
        left: Int,
        bottom: Int,
        right: Int = 200,
        isHeading: Boolean = false,
        isClickable: Boolean = false,
        className: String = "android.widget.TextView",
        packageName: String = "com.example.app"
    ): AccessibilityTextBlock {
        return AccessibilityTextBlock(
            text = text,
            top = top,
            left = left,
            bottom = bottom,
            right = right,
            isHeading = isHeading,
            isClickable = isClickable,
            className = className,
            packageName = packageName
        )
    }
}
