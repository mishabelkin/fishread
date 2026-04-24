package org.read.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class ReadAccessibilityFixtureReplayTest {

    @Test
    fun chromeInlineAccessibilityFixture_replays_browserFallbackCleanup_endToEnd() {
        val merged = mergeAccessibilityBlocks(loadFixtureBlocks("org/read/mobile/chrome_inline_accessibility_blocks.tsv"))

        val capture = requireNotNull(
            normalizeBrowserOpenFallbackCapture(
                text = merged.joinToString("\n\n"),
                title = "Sample Article Title",
                sourceLabel = "https://example.com/article",
                url = "https://example.com/article"
            )
        )
        val parsed = parseCapturedTextReaderContent(
            rawText = capture.text,
            providedTitle = capture.title,
            fallbackTitle = "Captured text"
        )

        assertEquals(
            listOf(
                "This is a sample article paragraph about large-scale systems. A reference link is here.",
                "Jordan Rivera, a systems architecture researcher, has been a close collaborator for years and keeps returning to the need for clearer engineering abstractions across complex systems."
            ),
            parsed.blocks.map { it.text }
        )
    }

    private fun loadFixtureBlocks(resourcePath: String): List<AccessibilityTextBlock> {
        val stream = javaClass.classLoader?.getResourceAsStream(resourcePath)
            ?: error("Missing test fixture: $resourcePath")
        val lines = stream.bufferedReader().use { it.readLines() }
        return lines
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split('\t')
                require(parts.size == 7) { "Unexpected fixture row: $line" }
                AccessibilityTextBlock(
                    text = parts[0],
                    top = parts[1].toInt(),
                    left = parts[2].toInt(),
                    bottom = parts[3].toInt(),
                    right = parts[4].toInt(),
                    isHeading = parts[5].toBooleanStrict(),
                    isClickable = parts[6].toBooleanStrict(),
                    className = "android.widget.TextView",
                    packageName = "com.android.chrome"
                )
            }
    }
}
