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
    fun looksLikeBrowserPackage_matchesCommonBrowsers() {
        assertTrue(looksLikeBrowserPackage("com.android.chrome"))
        assertTrue(looksLikeBrowserPackage("org.mozilla.firefox"))
        assertTrue(looksLikeBrowserPackage("com.microsoft.emmx"))
        assertFalse(looksLikeBrowserPackage("org.read.mobile"))
    }

    @Test
    fun resolveRecentTrackedBrowserUrl_returnsRecentSamePackageUrl() {
        val tracked = TrackedBrowserUrl(
            packageName = "com.android.chrome",
            url = "https://www.wsj.com/tech/ai/story",
            capturedAtMs = 10_000L,
            pageSignature = "current article title"
        )

        val resolved = resolveRecentTrackedBrowserUrl(
            activePackageName = "com.android.chrome",
            trackedBrowserUrl = tracked,
            nowMs = 12_000L,
            maxAgeMs = 5_000L,
            currentPageSignature = "current article title"
        )

        assertEquals("https://www.wsj.com/tech/ai/story", resolved)
    }

    @Test
    fun resolveRecentTrackedBrowserUrl_rejectsExpiredOrMismatchedPackage() {
        val tracked = TrackedBrowserUrl(
            packageName = "com.android.chrome",
            url = "https://www.wsj.com/tech/ai/story",
            capturedAtMs = 10_000L,
            pageSignature = "current article title"
        )

        assertEquals(
            null,
            resolveRecentTrackedBrowserUrl(
                activePackageName = "org.mozilla.firefox",
                trackedBrowserUrl = tracked,
                nowMs = 12_000L,
                maxAgeMs = 5_000L,
                currentPageSignature = "current article title"
            )
        )
        assertEquals(
            null,
            resolveRecentTrackedBrowserUrl(
                activePackageName = "com.android.chrome",
                trackedBrowserUrl = tracked,
                nowMs = 20_500L,
                maxAgeMs = 5_000L,
                currentPageSignature = "current article title"
            )
        )
    }

    @Test
    fun resolveRecentTrackedBrowserUrl_rejectsMismatchedPageSignature() {
        val tracked = TrackedBrowserUrl(
            packageName = "com.android.chrome",
            url = "https://www.wsj.com/tech/ai/story",
            capturedAtMs = 10_000L,
            pageSignature = "old article title"
        )

        assertEquals(
            null,
            resolveRecentTrackedBrowserUrl(
                activePackageName = "com.android.chrome",
                trackedBrowserUrl = tracked,
                nowMs = 12_000L,
                maxAgeMs = 5_000L,
                currentPageSignature = "new article title"
            )
        )
    }

    @Test
    fun shouldPreferCapturedBrowserPage_prefersReadableBrowserArticles() {
        assertTrue(
            shouldPreferCapturedBrowserPage(
                activePackageName = "com.android.chrome",
                currentUrl = "https://www.wsj.com/tech/ai/story",
                combinedBlockCount = 4,
                combinedChars = 900
            )
        )
    }

    @Test
    fun shouldPreferCapturedBrowserPage_skipsNonPreferredHostsPdfAndSparsePages() {
        assertFalse(
            shouldPreferCapturedBrowserPage(
                activePackageName = "com.android.chrome",
                currentUrl = "https://www.argmin.net/p/engineering-architecture-a-syllabus",
                combinedBlockCount = 8,
                combinedChars = 3200
            )
        )
        assertFalse(
            shouldPreferCapturedBrowserPage(
                activePackageName = "com.android.chrome",
                currentUrl = "https://example.com/paper.pdf",
                combinedBlockCount = 6,
                combinedChars = 1200
            )
        )
        assertFalse(
            shouldPreferCapturedBrowserPage(
                activePackageName = "com.android.chrome",
                currentUrl = "https://www.wsj.com/tech/ai/story",
                combinedBlockCount = 1,
                combinedChars = 120
            )
        )
    }

    @Test
    fun shouldAttemptAccessibilityAutoScroll_keepsScrollingBrowsersUntilCaptureIsLarge() {
        assertTrue(
            shouldAttemptAccessibilityAutoScroll(
                activePackageName = "com.android.chrome",
                hasScrollTarget = true,
                combinedBlockCount = 5,
                combinedChars = 1800,
                alreadyExposedEnough = true
            )
        )
        assertFalse(
            shouldAttemptAccessibilityAutoScroll(
                activePackageName = "com.android.chrome",
                hasScrollTarget = true,
                combinedBlockCount = 15,
                combinedChars = 8200,
                alreadyExposedEnough = true
            )
        )
    }

    @Test
    fun shouldUseExposedAccessibilityBlocks_skipsBrowserArticleCaptureButKeepsOtherCases() {
        assertFalse(
            shouldUseExposedAccessibilityBlocks(
                activePackageName = "com.android.chrome",
                sourceLabel = "https://www.wsj.com/tech/ai/story"
            )
        )
        assertTrue(
            shouldUseExposedAccessibilityBlocks(
                activePackageName = "com.android.chrome",
                sourceLabel = "https://www.argmin.net/p/engineering-architecture-a-syllabus"
            )
        )
        assertTrue(
            shouldUseExposedAccessibilityBlocks(
                activePackageName = "com.android.chrome",
                sourceLabel = "https://example.com/paper.pdf"
            )
        )
        assertTrue(
            shouldUseExposedAccessibilityBlocks(
                activePackageName = "org.read.mobile",
                sourceLabel = "https://example.com/paper.pdf"
            )
        )
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
    fun normalizeBrowserPageSignature_rejectsUrlsAndKeepsTitles() {
        assertEquals("sample article title by jane doe", normalizeBrowserPageSignature("Sample Article Title - by Jane Doe"))
        assertEquals(null, normalizeBrowserPageSignature("https://example.com/news/story"))
        assertEquals(null, normalizeBrowserPageSignature("Subscribe"))
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
    fun resolveAccessibilityNodeTextCandidate_uses_content_description_when_text_is_blank() {
        val candidate = resolveAccessibilityNodeTextCandidate(
            rawText = "",
            rawContentDescription = "a systems architecture researcher"
        )

        requireNotNull(candidate)
        assertEquals("a systems architecture researcher", candidate.text)
        assertTrue(candidate.fromContentDescription)
    }

    @Test
    fun resolveAccessibilityNodeTextCandidate_rejects_urlish_content_description_fallback() {
        val candidate = resolveAccessibilityNodeTextCandidate(
            rawText = null,
            rawContentDescription = "https%3A%2F%2Fsubstack-post-media.s3.amazonaws.com%2Fpublic%2Fimages%2Fexample"
        )

        assertEquals(null, candidate)
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
    fun mergeAccessibilityBlocks_preserves_short_clickable_linked_name_inside_sentence() {
        val merged = mergeAccessibilityBlocks(
            listOf(
                block(text = "A table of contents is,", top = 40, left = 16, bottom = 62, right = 210),
                block(text = "John Doyle", top = 42, left = 214, bottom = 62, right = 304, isClickable = true),
                block(
                    text = "has been a close friend and mentor of mine for over two decades.",
                    top = 64,
                    left = 16,
                    bottom = 92,
                    right = 360
                )
            )
        )

        assertEquals(
            listOf("A table of contents is, John Doyle has been a close friend and mentor of mine for over two decades."),
            merged
        )
    }

    @Test
    fun mergeAccessibilityBlocks_preserves_single_word_inline_link() {
        val merged = mergeAccessibilityBlocks(
            listOf(
                block(text = "A table of contents is", top = 40, left = 16, bottom = 62, right = 180),
                block(text = "here", top = 42, left = 184, bottom = 62, right = 220, isClickable = true)
            )
        )

        assertEquals(listOf("A table of contents is here"), merged)
    }

    @Test
    fun mergeAccessibilityBlocks_reorders_same_line_linked_name_before_body_fragment() {
        val merged = mergeAccessibilityBlocks(
            listOf(
                block(
                    text = "has been a close friend and mentor of mine for over two decades.",
                    top = 42,
                    left = 112,
                    bottom = 64,
                    right = 360
                ),
                block(text = "John Doyle,", top = 44, left = 16, bottom = 64, right = 102, isClickable = true)
            )
        )

        assertEquals(
            listOf("John Doyle, has been a close friend and mentor of mine for over two decades."),
            merged
        )
    }

    @Test
    fun mergeAccessibilityBlocks_orders_same_line_inline_fragments_by_left_despite_top_jitter() {
        val merged = mergeAccessibilityBlocks(
            listOf(
                block(
                    text = "This is a live blog of Lecture 10 of my graduate seminar. A table of contents is",
                    top = 40,
                    left = 16,
                    bottom = 82,
                    right = 360
                ),
                block(
                    text = ", has been a close friend and mentor of mine for over two decades.",
                    top = 88,
                    left = 236,
                    bottom = 114,
                    right = 420
                ),
                block(
                    text = "John Doyle,",
                    top = 92,
                    left = 16,
                    bottom = 114,
                    right = 108
                ),
                block(
                    text = "he of robust control infamy",
                    top = 90,
                    left = 112,
                    bottom = 114,
                    right = 232,
                    isClickable = true
                ),
                block(
                    text = "here.",
                    top = 44,
                    left = 364,
                    bottom = 66,
                    right = 404,
                    isClickable = true
                )
            )
        )

        assertEquals(
            listOf(
                "This is a live blog of Lecture 10 of my graduate seminar. A table of contents is here.",
                "John Doyle, he of robust control infamy, has been a close friend and mentor of mine for over two decades."
            ),
            merged
        )
    }

    @Test
    fun mergeAccessibilityBlocks_handles_real_chrome_full_width_continuation_after_inline_phrase() {
        val merged = mergeAccessibilityBlocks(
            listOf(
                block(
                    text = "John Doyle, ",
                    top = 1830,
                    left = 46,
                    bottom = 1891,
                    right = 310
                ),
                block(
                    text = "he of robust control infamy",
                    top = 1830,
                    left = 307,
                    bottom = 1891,
                    right = 891,
                    isClickable = true
                ),
                block(
                    text = ", has been a close friend and mentor of mine for over two decades. And for the entirety of those two decades, he’s been yelling about the need for a unified theory of engineering architecture.",
                    top = 1830,
                    left = 46,
                    bottom = 2410,
                    right = 1026
                )
            )
        )

        assertEquals(
            listOf(
                "John Doyle, he of robust control infamy, has been a close friend and mentor of mine for over two decades. And for the entirety of those two decades, he’s been yelling about the need for a unified theory of engineering architecture."
            ),
            merged
        )
    }

    @Test
    fun trimBrowserFallbackBlocks_trims_leading_chrome_and_trailing_discussion() {
        val trimmed = trimBrowserFallbackBlocks(
            listOf(
                "Walk the Marble Malls - by Ben Recht - arg min",
                ": we build a simulator at some abstraction level, and then shape a policy by designing an appropriate set of costs and constraints.",
                "Walk the Marble Malls",
                "Identifying the elements of a theory of engineering architecture.",
                "APR 20, 2026",
                "John Doyle has been yelling about the need for a unified theory of engineering architecture for over two decades.",
                "Discussion about this post",
                "Comment by Nico Formanek"
            )
        )

        assertEquals(
            listOf(
                "Walk the Marble Malls",
                "Identifying the elements of a theory of engineering architecture.",
                "APR 20, 2026",
                "John Doyle has been yelling about the need for a unified theory of engineering architecture for over two decades."
            ),
            trimmed
        )
    }

    @Test
    fun joinAccessibilityBlockText_keepsDashWrappedContinuationTight() {
        assertEquals(
            "alpha\u2014beta",
            joinAccessibilityBlockText("alpha\u2014", "beta")
        )
    }

    @Test
    fun browserFallbackCapture_endToEnd_preserves_john_doyle_and_inline_linked_descriptor() {
        val merged = mergeAccessibilityBlocks(
            listOf(
                block(text = "Walk the Marble Malls", top = 8, left = 16, bottom = 28, right = 240, isHeading = true),
                block(
                    text = "This is a live blog of Lecture 10 of my graduate seminar “Feedback, Learning, and Adaptation.” A table of contents is ",
                    top = 1537,
                    left = 46,
                    bottom = 1756,
                    right = 1029
                ),
                block(
                    text = "here",
                    top = 1695,
                    left = 506,
                    bottom = 1756,
                    right = 598,
                    isClickable = true
                ),
                block(
                    text = ".",
                    top = 1695,
                    left = 595,
                    bottom = 1756,
                    right = 612
                ),
                block(
                    text = "John Doyle, ",
                    top = 1830,
                    left = 46,
                    bottom = 1891,
                    right = 310
                ),
                block(
                    text = "he of robust control infamy",
                    top = 1830,
                    left = 307,
                    bottom = 1891,
                    right = 891,
                    isClickable = true
                ),
                block(
                    text = ", has been a close friend and mentor of mine for over two decades. And for the entirety of those two decades, he’s been yelling about the need for a unified theory of engineering architecture.",
                    top = 1830,
                    left = 46,
                    bottom = 2410,
                    right = 1026
                )
            )
        )

        val capture = requireNotNull(
            normalizeBrowserOpenFallbackCapture(
                text = merged.joinToString("\n\n"),
                title = "Walk the Marble Malls",
                sourceLabel = "https://www.argmin.net/p/walk-the-marble-malls",
                url = "https://www.argmin.net/p/walk-the-marble-malls"
            )
        )
        val parsed = parseCapturedTextReaderContent(
            rawText = capture.text,
            providedTitle = capture.title,
            fallbackTitle = "Captured text"
        )

        assertEquals("Walk the Marble Malls", parsed.title)
        assertEquals(
            listOf(
                "This is a live blog of Lecture 10 of my graduate seminar “Feedback, Learning, and Adaptation.” A table of contents is here.",
                "John Doyle, he of robust control infamy, has been a close friend and mentor of mine for over two decades. And for the entirety of those two decades, he’s been yelling about the need for a unified theory of engineering architecture."
            ),
            parsed.blocks.map { it.text }
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
