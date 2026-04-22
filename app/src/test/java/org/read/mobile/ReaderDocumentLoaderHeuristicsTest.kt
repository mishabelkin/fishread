package org.read.mobile

import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderDocumentLoaderHeuristicsTest {

    @Test
    fun looksLikeBrowserChallengeHtml_detectsHashcashStyleInterstitial() {
        val html = """
            <!DOCTYPE html><title>Checking your browser...</title>
            <noscript><p>Javascript required</p></noscript>
            <script>
              fetch("/__challenge", { method:"POST", headers:{"X-Hashcash-Solution":"abc"} })
            </script>
            <div>I am human</div>
        """.trimIndent()

        assertTrue(looksLikeBrowserChallengeHtmlForHeuristics(html))
    }

    @Test
    fun looksLikeNoisyWebContainer_preservesArticleWrapper_whenCommentEnabledContainsMainContent() {
        val parsed = Jsoup.parse(
            """
            <body>
              <div id="doc" class="container-fluid markdown-body comment-enabled">
                <h1>Solving the Strait of Hormuz Blockage</h1>
                <main>
                  <h2>Problem Statement</h2>
                  <p>The current Persian Gulf War has cut off an artery of raw materials by closing the Strait of Hormuz.</p>
                  <p>Important questions are how large the hole is and what transportation alternatives exist.</p>
                  <p>Markets can respond with substitution, rerouting, and new supply if price signals are allowed to function.</p>
                  <p>The world economy is more resilient than it first appears in the face of transport disruption.</p>
                  <p>That makes this a real article wrapper even though the site enables comments.</p>
                </main>
              </div>
            </body>
            """.trimIndent()
        )

        val wrapper = parsed.selectFirst("#doc")!!

        assertTrue(isLikelyArticleContentContainer(wrapper))
        assertFalse(looksLikeNoisyWebContainerForHeuristics(wrapper))
    }

    @Test
    fun resolveRedirectUrlPreservingWww_keepsResolvableWwwHostOnSameSiteRedirect() {
        val rewritten = resolveRedirectUrlPreservingWwwForHeuristics(
            currentUrl = "https://www.popularbydesign.org/p/academics-need-to-wake-up-on-ai-part-4c6",
            redirectLocation = "https://popularbydesign.org/p/academics-need-to-wake-up-on-ai-part-4c6"
        )

        assertEquals(
            "https://www.popularbydesign.org/p/academics-need-to-wake-up-on-ai-part-4c6",
            rewritten
        )
    }

    @Test
    fun retryUrlWithWwwHost_addsWwwForBareCustomDomain() {
        val retried = retryUrlWithWwwHostForHeuristics(
            "https://popularbydesign.org/p/academics-need-to-wake-up-on-ai-part-4c6"
        )

        assertEquals(
            "https://www.popularbydesign.org/p/academics-need-to-wake-up-on-ai-part-4c6",
            retried
        )
    }

    @Test
    fun retryUrlWithWwwHost_skipsAlreadyQualifiedHost() {
        val retried = retryUrlWithWwwHostForHeuristics(
            "https://www.popularbydesign.org/p/academics-need-to-wake-up-on-ai-part-4c6"
        )

        assertEquals(null, retried)
    }

    @Test
    fun looksLikeUnknownHostFailure_detectsAndroidResolverMessage() {
        val error = IllegalStateException(
            "java.net.UnknownHostException: Unable to resolve host \"popularbydesign.org\": No address associated with hostname"
        )

        assertTrue(looksLikeUnknownHostFailureForHeuristics(error))
    }

    @Test
    fun cleanupExtractedWebBlocks_removesLeadingMetadataAndTrailingBoilerplate() {
        val blocks = listOf(
            ReaderBlock(ReaderBlockType.Heading, "A Better Way to Read Long Articles"),
            ReaderBlock(ReaderBlockType.Paragraph, "By Alice Author"),
            ReaderBlock(ReaderBlockType.Paragraph, "This is the real opening paragraph of the article with enough substance to keep."),
            ReaderBlock(ReaderBlockType.Heading, "Related articles"),
            ReaderBlock(ReaderBlockType.Paragraph, "Read more from the archive.")
        )

        val cleaned = cleanupExtractedWebBlocksForHeuristics(
            blocks = blocks,
            titleText = "A Better Way to Read Long Articles",
            metadataTexts = listOf("By Alice Author")
        )

        assertEquals(1, cleaned.size)
        assertEquals(ReaderBlockType.Paragraph, cleaned.first().type)
        assertTrue(cleaned.first().text.startsWith("This is the real opening paragraph"))
    }

    @Test
    fun cleanupExtractedWebBlocks_mergesContinuationParagraphs() {
        val blocks = listOf(
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "The article body is sometimes split in awkward places where the sentence continues"
            ),
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "and the next paragraph begins with a lowercase continuation that should be merged."
            )
        )

        val cleaned = cleanupExtractedWebBlocksForHeuristics(
            blocks = blocks,
            titleText = "Sample title"
        )

        assertEquals(1, cleaned.size)
        assertTrue(cleaned.first().text.contains("continues and the next paragraph"))
    }

    @Test
    fun cleanupExtractedWebBlocks_removesPhotoCreditsButtonsAndAcknowledgements() {
        val blocks = listOf(
            ReaderBlock(ReaderBlockType.Paragraph, "Open in app"),
            ReaderBlock(ReaderBlockType.Paragraph, "Photo by Jane Doe for Example News"),
            ReaderBlock(ReaderBlockType.Paragraph, "This is the real article paragraph that should remain in the output."),
            ReaderBlock(ReaderBlockType.Paragraph, "Additional reporting by John Smith"),
            ReaderBlock(ReaderBlockType.Paragraph, "Edited by Mary Editor")
        )

        val cleaned = cleanupExtractedWebBlocksForHeuristics(
            blocks = blocks,
            titleText = "Example title"
        )

        assertEquals(1, cleaned.size)
        assertTrue(cleaned.first().text.startsWith("This is the real article paragraph"))
    }

    @Test
    fun cleanupExtractedWebBlocks_replacesShortDuplicateWithLongerVersion() {
        val blocks = listOf(
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "Iran’s leaders are weighing their options after the strike."
            ),
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "Iran’s leaders are weighing their options after the strike, and advisers are split over how aggressively to respond."
            )
        )

        val cleaned = cleanupExtractedWebBlocksForHeuristics(
            blocks = blocks,
            titleText = "Example title"
        )

        assertEquals(1, cleaned.size)
        assertTrue(cleaned.first().text.contains("advisers are split"))
    }

    @Test
    fun cleanupExtractedWebBlocks_removesSocialCommentsAndSiteInfoLines() {
        val blocks = listOf(
            ReaderBlock(ReaderBlockType.Paragraph, "Facebook Twitter X LinkedIn Email Print"),
            ReaderBlock(ReaderBlockType.Heading, "Comments"),
            ReaderBlock(ReaderBlockType.Paragraph, "Leave a comment"),
            ReaderBlock(ReaderBlockType.Paragraph, "World | U.S. | Politics | Business | Opinion"),
            ReaderBlock(ReaderBlockType.Paragraph, "Privacy Policy Terms of Service Contact Us Accessibility"),
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "This article paragraph should remain because it is actual content and not site chrome."
            )
        )

        val cleaned = cleanupExtractedWebBlocksForHeuristics(
            blocks = blocks,
            titleText = "Example title"
        )

        assertEquals(1, cleaned.size)
        assertTrue(cleaned.first().text.startsWith("This article paragraph should remain"))
    }

    @Test
    fun cleanupExtractedWebBlocks_removesSafeframeAndAdTechLines() {
        val blocks = listOf(
            ReaderBlock(ReaderBlockType.Paragraph, "Safeframe Container"),
            ReaderBlock(ReaderBlockType.Paragraph, "Ad Choices"),
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "This article paragraph should remain because it is actual content and not advertising chrome."
            )
        )

        val cleaned = cleanupExtractedWebBlocksForHeuristics(
            blocks = blocks,
            titleText = "Example title"
        )

        assertEquals(1, cleaned.size)
        assertTrue(cleaned.first().text.startsWith("This article paragraph should remain"))
    }

    @Test
    fun cleanupExtractedWebBlocks_removesCopyrightFooterLine() {
        val blocks = listOf(
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "Copyright ©2026 Dow Jones & Company, Inc. All Rights Reserved. 87990cbe856818d5eddac44c7b1cdeb8 The Wall Street Journal"
            ),
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "This is the actual article paragraph that should remain and should be the only prose carried forward."
            )
        )

        val cleaned = cleanupExtractedWebBlocksForHeuristics(
            blocks = blocks,
            titleText = "Example title"
        )

        assertEquals(1, cleaned.size)
        assertTrue(cleaned.first().text.startsWith("This is the actual article paragraph"))
    }

    @Test
    fun cleanupExtractedWebBlocks_stripsLeadingCopyrightFooterPrefixFromArticleParagraph() {
        val blocks = listOf(
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "Copyright ©2026 Dow Jones & Company, Inc. All Rights Reserved. 87990cbe856818d5eddac44c7b1cdeb8 The Wall Street Journal Markets were rattled by the overnight announcement, but traders recovered some confidence by midday as more details emerged."
            )
        )

        val cleaned = cleanupExtractedWebBlocksForHeuristics(
            blocks = blocks,
            titleText = "Example title"
        )

        assertEquals(1, cleaned.size)
        assertTrue(cleaned.first().text.startsWith("Markets were rattled by the overnight announcement"))
        assertFalse(cleaned.first().text.contains("All Rights Reserved"))
    }

    @Test
    fun cleanupExtractedWebBlocks_removesStandalonePublisherHashFooterLine() {
        val blocks = listOf(
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "87990cbe856818d5eddac44c7b1cdeb8 The Wall Street Journal"
            ),
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "Markets were rattled by the overnight announcement, but traders recovered some confidence by midday as more details emerged."
            )
        )

        val cleaned = cleanupExtractedWebBlocksForHeuristics(
            blocks = blocks,
            titleText = "Example title"
        )

        assertEquals(1, cleaned.size)
        assertTrue(cleaned.first().text.startsWith("Markets were rattled by the overnight announcement"))
    }

    @Test
    fun cleanupExtractedWebBlocks_stripsPublisherLabelFromHeadingPrefix() {
        val blocks = listOf(
            ReaderBlock(
                ReaderBlockType.Heading,
                "The Wall Street Journal Eli Lilly to Buy Centessa Pharmaceuticals for Initial $6.3 Billion"
            )
        )

        val cleaned = cleanupExtractedWebBlocksForHeuristics(
            blocks = blocks,
            titleText = "Example title"
        )

        assertEquals(1, cleaned.size)
        assertEquals("Eli Lilly to Buy Centessa Pharmaceuticals for Initial $6.3 Billion", cleaned.first().text)
    }

    @Test
    fun cleanupExtractedWebBlocks_stripsTrailingCopyrightFooterFromParagraph() {
        val blocks = listOf(
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "Eli Lilly agreed to buy clinical-stage company Centessa Pharmaceuticals for an initial payment of about $6.3 billion, expanding its neuroscience portfolio. The Wall Street Journal Copyright ©2026 Dow Jones & Company, Inc. All Rights Reserved. 87990cbe856818d5eddac44c7b1cdeb8"
            )
        )

        val cleaned = cleanupExtractedWebBlocksForHeuristics(
            blocks = blocks,
            titleText = "Example title"
        )

        assertEquals(1, cleaned.size)
        assertTrue(cleaned.first().text.contains("Centessa Pharmaceuticals"))
        assertFalse(cleaned.first().text.contains("All Rights Reserved"))
        assertFalse(cleaned.first().text.contains("87990cbe856818d5eddac44c7b1cdeb8"))
    }
}
