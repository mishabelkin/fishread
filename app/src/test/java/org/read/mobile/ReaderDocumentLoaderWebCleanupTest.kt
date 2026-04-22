package org.read.mobile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderDocumentLoaderWebCleanupTest {

    @Test
    fun loadFromHtml_stripsShareCommentsAndSiteInfoChrome() {
        val html = """
            <html>
              <head><title>Example article</title></head>
              <body>
                <article class="story-body">
                  <div class="safeframe-container">
                    <p>Safeframe Container</p>
                  </div>
                  <div class="share-tools" aria-label="share tools">
                    <p>Share</p>
                    <p>Copy link</p>
                  </div>
                  <div class="comments-panel" aria-label="comments">
                    <h2>Comments</h2>
                    <p>Leave a comment</p>
                  </div>
                  <div class="site-info">
                    <p>Privacy Policy Terms of Service Contact Us Accessibility</p>
                  </div>
                  <p>This is the first real paragraph of the article with enough substance to keep.</p>
                  <p>This is the second real paragraph of the article and it should also remain.</p>
                </article>
              </body>
            </html>
        """.trimIndent()

        val loader = ReaderDocumentLoader(RuntimeEnvironment.getApplication())
        val document = loader.loadFromHtml(html, sourceLabel = "https://example.com/article")
        val joinedBody = document.blocks.joinToString("\n") { it.text.lowercase() }

        assertTrue(joinedBody.contains("first real paragraph"))
        assertTrue(joinedBody.contains("second real paragraph"))
        assertFalse(joinedBody.contains("share"))
        assertFalse(joinedBody.contains("copy link"))
        assertFalse(joinedBody.contains("safeframe container"))
        assertFalse(joinedBody.contains("leave a comment"))
        assertFalse(joinedBody.contains("privacy policy"))
    }

    @Test
    fun loadFromHtml_preservesInlineLinkedTextInsideParagraphs() {
        val html = """
            <html>
              <head><title>Example article</title></head>
              <body>
                <article class="story-body">
                  <p>
                    529 plans have expanded beyond higher education. They can be used to cover room and board,
                    trade schools, professional licensing, K-12 education and student-loan repayment.
                    Up to $35,000 of 529 money can also be rolled into a
                    <a href="/explainers/roth-ira" role="button">Roth IRA</a>,
                    subject to limitations. Tax benefits vary by state.
                  </p>
                </article>
              </body>
            </html>
        """.trimIndent()

        val loader = ReaderDocumentLoader(RuntimeEnvironment.getApplication())
        val document = loader.loadFromHtml(html, sourceLabel = "https://example.com/article")
        val joinedBody = document.blocks.joinToString("\n") { it.text }

        assertTrue(joinedBody.contains("rolled into a Roth IRA, subject to limitations"))
    }

    @Test
    fun loadFromHtml_preservesLinkedBodyTextInsideCommentaryContainer() {
        val html = """
            <html>
              <head><title>Example article</title></head>
              <body>
                <article class="commentary-body">
                  <p>
                    The company said the provision would also cover workers who choose a
                    <a href="/benefits/flexible-plan">flexible retirement option</a>
                    during the first enrollment window.
                  </p>
                  <p>
                    A second paragraph remains here so the extractor still sees this as normal article content.
                  </p>
                </article>
              </body>
            </html>
        """.trimIndent()

        val loader = ReaderDocumentLoader(RuntimeEnvironment.getApplication())
        val document = loader.loadFromHtml(html, sourceLabel = "https://example.com/article")
        val joinedBody = document.blocks.joinToString("\n") { it.text }

        assertTrue(joinedBody.contains("flexible retirement option"))
        assertTrue(joinedBody.contains("A second paragraph remains here"))
    }

    @Test
    fun loadFromHtml_stripsInlineShareButtonsFromOtherwiseValidParagraph() {
        val html = """
            <html>
              <head><title>Example article</title></head>
              <body>
                <article class="story-body">
                  <p>
                    Researchers say the latest model behavior still needs careful evaluation before it is used in
                    classrooms and public institutions.
                    <span class="share-tools">
                      <a href="/share/facebook">Share</a>
                      <button type="button">Copy link</button>
                      <button type="button">Print</button>
                    </span>
                    They added that the results should be reproduced across multiple labs before any broader rollout.
                  </p>
                  <p>
                    A second paragraph remains in place so the loader still treats the page as ordinary article prose.
                  </p>
                </article>
              </body>
            </html>
        """.trimIndent()

        val loader = ReaderDocumentLoader(RuntimeEnvironment.getApplication())
        val document = loader.loadFromHtml(html, sourceLabel = "https://example.com/article")
        val joinedBody = document.blocks.joinToString("\n") { it.text }

        assertTrue(joinedBody.contains("Researchers say the latest model behavior still needs careful evaluation"))
        assertTrue(joinedBody.contains("They added that the results should be reproduced across multiple labs"))
        assertTrue(joinedBody.contains("A second paragraph remains in place"))
        assertFalse(joinedBody.contains("Share"))
        assertFalse(joinedBody.contains("Copy link"))
        assertFalse(joinedBody.contains("Print"))
    }

    @Test
    fun loadFromHtml_preservesLinkHeavyArticleProseWithNamedInlineAnchors() {
        val html = """
            <html>
              <head><title>Example article</title></head>
              <body>
                <article class="story-body">
                  <div class="article-paragraph">
                    <a href="/people/terence-tao">Terence Tao</a>,
                    <a href="/companies/openai">OpenAI</a>,
                    <a href="/companies/google-deepmind">Google DeepMind</a>,
                    and <a href="/products/alphaevolve">AlphaEvolve</a>
                    all appear in this sentence because the publisher links nearly every proper name, but the block is
                    still real article prose and should survive extraction intact.
                  </div>
                  <div class="article-paragraph">
                    A second article paragraph remains here so the loader continues to see the page as normal long-form
                    content rather than treating it as a navigation or related-links rail.
                  </div>
                </article>
              </body>
            </html>
        """.trimIndent()

        val loader = ReaderDocumentLoader(RuntimeEnvironment.getApplication())
        val document = loader.loadFromHtml(html, sourceLabel = "https://example.com/article")
        val joinedBody = document.blocks.joinToString("\n") { it.text }

        assertTrue(joinedBody.contains("Terence Tao"))
        assertTrue(joinedBody.contains("OpenAI"))
        assertTrue(joinedBody.contains("Google DeepMind"))
        assertTrue(joinedBody.contains("AlphaEvolve"))
        assertTrue(joinedBody.contains("still real article prose and should survive extraction intact"))
        assertTrue(joinedBody.contains("second article paragraph remains here"))
    }

    @Test
    fun loadFromHtml_extractsParagraphLikeDivBlocks() {
        val html = """
            <html>
              <head><title>Example article</title></head>
              <body>
                <article class="story-body">
                  <div class="article-paragraph">
                    This is the first article paragraph rendered in a div rather than a p tag, and it still has enough
                    substance to be treated as real reading content for the document loader.
                  </div>
                  <div class="article-paragraph">
                    This is the second article paragraph rendered in the same div-based structure, and it should also
                    survive extraction instead of disappearing from the reading view.
                  </div>
                  <div class="article-paragraph">
                    This is the third article paragraph, which gives the loader enough body text to treat the article
                    as complete even though the publisher did not use semantic paragraph tags.
                  </div>
                </article>
              </body>
            </html>
        """.trimIndent()

        val loader = ReaderDocumentLoader(RuntimeEnvironment.getApplication())
        val document = loader.loadFromHtml(html, sourceLabel = "https://example.com/article")
        val paragraphTexts = document.blocks
            .filter { it.type == ReaderBlockType.Paragraph }
            .map { it.text }

        assertTrue(paragraphTexts.any { it.contains("first article paragraph rendered in a div") })
        assertTrue(paragraphTexts.any { it.contains("second article paragraph rendered in the same div-based structure") })
        assertTrue(paragraphTexts.any { it.contains("third article paragraph") })
    }

    @Test
    fun loadFromHtml_usesSentenceFallbackWhenOnlySmallSliceWasExtracted() {
        val html = """
            <html>
              <head><title>Example article</title></head>
              <body>
                <article class="story-body">
                  <p>This is the short opening paragraph that was already being extracted correctly by the loader.</p>
                  <div class="article-paragraph">
                    This is the much longer second paragraph, but it is stored in a div and used to be missed by the
                    extractor, leaving the document looking much shorter than the original page.
                  </div>
                  <div class="article-paragraph">
                    This is the third substantive paragraph, which should also make it into the reading document even
                    when the publisher mixes p tags and div tags inside the same article body.
                  </div>
                </article>
              </body>
            </html>
        """.trimIndent()

        val loader = ReaderDocumentLoader(RuntimeEnvironment.getApplication())
        val document = loader.loadFromHtml(html, sourceLabel = "https://example.com/article")
        val joinedBody = document.blocks.joinToString("\n") { it.text }

        assertTrue(joinedBody.contains("short opening paragraph"))
        assertTrue(joinedBody.contains("much longer second paragraph"))
        assertTrue(joinedBody.contains("third substantive paragraph"))
    }

    @Test
    fun loadFromHtml_extractsSubstackParagraphDivsFromModernPostContentContainer() {
        val html = """
            <html>
              <head><title>Example Substack post</title></head>
              <body>
                <article>
                  <div data-testid="post-content">
                    <div class="body markup">
                      <div>
                        This is the first Substack paragraph rendered as a div inside the modern post-content container,
                        and it should be treated as normal reading content rather than silently skipped by the loader.
                      </div>
                      <div>
                        This is the second Substack paragraph in the same div-based structure, which previously risked
                        disappearing because the dedicated extractor only appended p tags and recursed through divs.
                      </div>
                      <div>
                        This is the third substantive paragraph, giving the extractor enough body text to recognize the
                        post as complete even when Substack uses paragraph divs instead of semantic paragraph tags.
                      </div>
                    </div>
                  </div>
                </article>
              </body>
            </html>
        """.trimIndent()

        val loader = ReaderDocumentLoader(RuntimeEnvironment.getApplication())
        val document = loader.loadFromHtml(html, sourceLabel = "https://example.substack.com/p/test-post")
        val joinedBody = document.blocks.joinToString("\n") { it.text }

        assertTrue(joinedBody.contains("first Substack paragraph rendered as a div"))
        assertTrue(joinedBody.contains("second Substack paragraph in the same div-based structure"))
        assertTrue(joinedBody.contains("third substantive paragraph"))
    }

    @Test
    fun loadFromHtml_extractsSubstackPostContentMarkupContainer() {
        val html = """
            <html>
              <head><title>Example Substack post</title></head>
              <body>
                <div class="post-content">
                  <div class="markup">
                    <p>This is the first Substack paragraph inside a post-content markup container, and it should remain readable after extraction.</p>
                    <p>This is the second Substack paragraph in the same container, confirming that we still handle the non data-testid shape correctly.</p>
                    <p>This is the third paragraph, giving the loader enough body text to treat the page as a full Substack article.</p>
                  </div>
                </div>
              </body>
            </html>
        """.trimIndent()

        val loader = ReaderDocumentLoader(RuntimeEnvironment.getApplication())
        val document = loader.loadFromHtml(html, sourceLabel = "https://example.substack.com/p/post-content-markup")
        val texts = document.blocks.map { it.text }

        assertTrue(texts.any { it.contains("first Substack paragraph inside a post-content markup container") })
        assertTrue(texts.any { it.contains("second Substack paragraph in the same container") })
        assertTrue(texts.any { it.contains("third paragraph, giving the loader enough body text") })
    }

    @Test
    fun loadFromHtml_extractsShortCustomDomainSubstackPost() {
        val html = """
            <html>
              <head><title>Popular by Design</title></head>
              <body>
                <article>
                  <div class="available-content">
                    <div class="body markup">
                      <div>
                        <a href="https://example.com/person">Linked name</a> should remain in the extracted text even when
                        the Substack post is short and hosted on a custom domain.
                      </div>
                      <div>
                        A second compact paragraph should still be enough for the dedicated Substack path instead of
                        falling back to the generic extractor and dropping the body.
                      </div>
                    </div>
                  </div>
                </article>
              </body>
            </html>
        """.trimIndent()

        val loader = ReaderDocumentLoader(RuntimeEnvironment.getApplication())
        val document = loader.loadFromHtml(html, sourceLabel = "https://www.popularbydesign.org/p/test-post")
        val joinedBody = document.blocks.joinToString("\n") { it.text }

        assertTrue(joinedBody.contains("Linked name should remain in the extracted text"))
        assertTrue(joinedBody.contains("the Substack post is short and hosted on a custom domain"))
        assertTrue(joinedBody.contains("A second compact paragraph should still be enough"))
    }

    @Test
    fun loadFromHtml_fallbackPathStripsLeadingCopyrightPrefix() {
        val html = """
            <html>
              <head><title>Example article</title></head>
              <body>
                Copyright ©2026 Dow Jones & Company, Inc. All Rights Reserved.
                87990cbe856818d5eddac44c7b1cdeb8 The Wall Street Journal
                Markets were rattled by the overnight announcement, but traders recovered some confidence by midday as more details emerged.
              </body>
            </html>
        """.trimIndent()

        val loader = ReaderDocumentLoader(RuntimeEnvironment.getApplication())
        val document = loader.loadFromHtml(html, sourceLabel = "https://example.com/article")
        val joinedBody = document.blocks.joinToString("\n") { it.text }

        assertTrue(joinedBody.contains("Markets were rattled by the overnight announcement"))
        assertFalse(joinedBody.contains("All Rights Reserved"))
        assertFalse(joinedBody.contains("Wall Street Journal"))
    }
}
