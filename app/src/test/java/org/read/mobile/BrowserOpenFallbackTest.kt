package org.read.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserOpenFallbackTest {

    @Test
    fun normalizeBrowserOpenFallbackCapture_returnsNullForShortText() {
        val fallback = normalizeBrowserOpenFallbackCapture(
            text = "Too short",
            title = "Short",
            sourceLabel = null,
            url = "https://example.com/article"
        )

        assertNull(fallback)
    }

    @Test
    fun normalizeBrowserOpenFallbackCapture_defaultsSourceLabelToUrl() {
        val fallback = normalizeBrowserOpenFallbackCapture(
            text = "word ".repeat(40),
            title = "  Example title  ",
            sourceLabel = null,
            url = "https://example.com/article"
        )

        requireNotNull(fallback)
        assertEquals("Example title", fallback.title)
        assertEquals("https://example.com/article", fallback.sourceLabel)
    }

    @Test
    fun normalizeBrowserOpenFallbackCapture_keepsExplicitSourceLabel() {
        val fallback = normalizeBrowserOpenFallbackCapture(
            text = "word ".repeat(40),
            title = null,
            sourceLabel = "https://example.com/article?via=browser",
            url = "https://example.com/article"
        )

        requireNotNull(fallback)
        assertEquals("https://example.com/article?via=browser", fallback.sourceLabel)
    }

    @Test
    fun choosePreferredBrowserOpenFallbackCapture_prefers_richer_session_snapshot_when_visible_capture_is_thin() {
        val visibleCapture = requireNotNull(
            normalizeBrowserOpenFallbackCapture(
                text = """
                    Sample Article Title

                    A concise subtitle about systems and layered engineering design.

                    JANE DOE

                    Systems Lab, Example University

                    APR 23, 2026
                """.trimIndent(),
                title = "Sample Article Title",
                sourceLabel = "https://example.com/article",
                url = "https://example.com/article"
            )
        )
        val sessionCapture = requireNotNull(
            normalizeBrowserOpenFallbackCapture(
                text = """
                    Sample Article Title

                    A concise subtitle about systems and layered engineering design.

                    APR 23, 2026

                    Jordan Rivera, a systems architecture researcher, has been a close collaborator for years and keeps returning to the need for clearer engineering abstractions across complex systems.

                    The article continues by walking through design tradeoffs, protocol boundaries, and why robust architectures depend on explicit interfaces rather than hidden assumptions.
                """.trimIndent(),
                title = "Sample Article Title",
                sourceLabel = "https://example.com/article",
                url = "https://example.com/article"
            )
        )

        assertEquals(
            sessionCapture,
            choosePreferredBrowserOpenFallbackCapture(
                visibleCapture = visibleCapture,
                sessionCapture = sessionCapture
            )
        )
    }

    @Test
    fun choosePreferredBrowserOpenFallbackCapture_keeps_visible_capture_when_it_already_has_real_body_text() {
        val visibleCapture = requireNotNull(
            normalizeBrowserOpenFallbackCapture(
                text = """
                    Sample Article Title

                    APR 23, 2026

                    This visible browser snapshot already contains the article body with enough detail to explain the argument clearly and should remain the preferred fallback because it is both readable and substantial. It includes enough real prose to avoid treating a title, subtitle, and date cluster as a complete document.
                """.trimIndent(),
                title = "Sample Article Title",
                sourceLabel = "https://example.com/article",
                url = "https://example.com/article"
            )
        )
        val sessionCapture = requireNotNull(
            normalizeBrowserOpenFallbackCapture(
                text = """
                    Sample Article Title

                    A concise subtitle about systems and layered engineering design.

                    JANE DOE

                    APR 23, 2026
                """.trimIndent(),
                title = "Sample Article Title",
                sourceLabel = "https://example.com/article",
                url = "https://example.com/article"
            )
        )

        assertEquals(
            visibleCapture,
            choosePreferredBrowserOpenFallbackCapture(
                visibleCapture = visibleCapture,
                sessionCapture = sessionCapture
            )
        )
    }

    @Test
    fun choosePreferredBrowserOpenFallbackCapture_rejects_lone_thin_capture() {
        val thinCapture = requireNotNull(
            normalizeBrowserOpenFallbackCapture(
                text = """
                    Sample Article Title

                    A concise subtitle about systems and layered engineering design.

                    JANE DOE

                    APR 23, 2026
                """.trimIndent(),
                title = "Sample Article Title",
                sourceLabel = "https://example.com/article",
                url = "https://example.com/article"
            )
        )

        assertNull(
            choosePreferredBrowserOpenFallbackCapture(
                visibleCapture = thinCapture,
                sessionCapture = null
            )
        )
    }

    @Test
    fun shouldPreferBrowserFallbackDocument_prefersFallbackForWeakRemoteDocument() {
        val remoteDocument = ReaderDocument(
            title = "Walk the Marble Malls",
            sourceLabel = "https://www.argmin.net/p/walk-the-marble-malls",
            kind = DocumentKind.WEB,
            pageCount = 1,
            paragraphCount = 1,
            headingCount = 1,
            metadataBlocks = emptyList(),
            footnoteBlocks = emptyList(),
            blocks = listOf(
                ReaderBlock(ReaderBlockType.Heading, "Walk the Marble Malls"),
                ReaderBlock(ReaderBlockType.Paragraph, "Apr 20, 2026")
            )
        )
        val fallback = BrowserOpenFallbackCapture(
            text = "John Doyle has been a close friend and mentor of mine for over two decades.\n\n" +
                "The world is run on complex, engineered feedback systems with astounding robustness.",
            title = "Walk the Marble Malls",
            sourceLabel = "https://www.argmin.net/p/walk-the-marble-malls"
        )

        assertTrue(shouldPreferBrowserFallbackDocument(remoteDocument, fallback))
    }

    @Test
    fun shouldPreferBrowserFallbackDocument_keepsStrongRemoteDocument() {
        val remoteDocument = ReaderDocument(
            title = "Walk the Marble Malls",
            sourceLabel = "https://www.argmin.net/p/walk-the-marble-malls",
            kind = DocumentKind.WEB,
            pageCount = 1,
            paragraphCount = 3,
            headingCount = 1,
            metadataBlocks = emptyList(),
            footnoteBlocks = emptyList(),
            blocks = listOf(
                ReaderBlock(ReaderBlockType.Heading, "Walk the Marble Malls"),
                ReaderBlock(ReaderBlockType.Paragraph, "Paragraph one with enough text to represent the article body cleanly and clearly."),
                ReaderBlock(ReaderBlockType.Paragraph, "Paragraph two continues the article with enough detail to show that the extractor worked."),
                ReaderBlock(ReaderBlockType.Paragraph, "Paragraph three keeps the document above the weak-document threshold.")
            )
        )
        val fallback = BrowserOpenFallbackCapture(
            text = "Short fallback snapshot that should not override a strong remote extraction.",
            title = "Walk the Marble Malls",
            sourceLabel = "https://www.argmin.net/p/walk-the-marble-malls"
        )

        assertFalse(shouldPreferBrowserFallbackDocument(remoteDocument, fallback))
    }
}
