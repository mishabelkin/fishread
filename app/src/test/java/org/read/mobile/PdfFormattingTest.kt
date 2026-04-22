package org.read.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfFormattingTest {

    @Test
    fun formatReaderContent_movesAbstractIntoMetadata_andSeparatesFootnotes() {
        val pages = listOf(
            """
            Journal of Interesting Results
            A Great Paper
            Alice Author
            University of Somewhere
            
            Abstract
            This is the abstract paragraph with enough content to be treated as real body text instead of metadata.
            1 Correspondence: alice@example.com
            """.trimIndent(),
            """
            Journal of Interesting Results
            Introduction
            
            This is the introduction paragraph. It contains enough words to count as real body text in the output.
            2 Additional note about the paper that should move into footnotes.
            """.trimIndent()
        )

        val formatted = formatReaderContent(pages, "Local PDF")
        val metadataText = formatted.metadataBlocks.joinToString(" ") { it.text }
        val contentText = formatted.contentBlocks.joinToString(" ") { it.text }

        assertEquals("A Great Paper", formatted.displayTitle)
        assertTrue(formatted.footnoteBlocks.any { it.text.contains("Correspondence: alice@example.com") })
        assertTrue(formatted.footnoteBlocks.any { it.text.contains("Additional note about the paper") })
        assertFalse(contentText.contains("Journal of Interesting Results"))
        assertTrue("metadata=$metadataText | content=$contentText", metadataText.contains("Abstract"))
        assertTrue(metadataText.contains("This is the abstract paragraph"))
        assertFalse(contentText.contains("This is the abstract paragraph"))
        assertTrue(formatted.contentBlocks.any { it.text.equals("Introduction", ignoreCase = true) })
    }

    @Test
    fun formatReaderContent_keepsFallbackTitleWhenNoReliableTitleIsFound() {
        val pages = listOf(
            """
            Learning Useful Things
            From Papers
            Bob Example
            Department of Testing
            University of Somewhere
            bob@example.com
            June 2026
             
            Introduction
            This paragraph is long enough to be considered the body of the paper and should not be mistaken for metadata.
            """.trimIndent()
        )

        val formatted = formatReaderContent(pages, "Remote PDF")

        assertEquals("Remote PDF", formatted.displayTitle)
    }

    @Test
    fun formatReaderContent_keepsTrailingBodyText_whenPageTailIsNotReallyFootnotes() {
        val pages = listOf(
            """
            A Careful Paper

            Introduction
            This is the introduction paragraph with enough content to count as real body text for the reader.
            """.trimIndent(),
            """
            Methods
            This page ends with a real paragraph that mentions numbered improvements and should stay in the body instead of being stripped as a footnote.
            3 Results remain meaningful in the full text and should not be moved away from the reader.
            """.trimIndent()
        )

        val formatted = formatReaderContent(pages, "A Careful Paper")

        assertTrue(
            formatted.contentBlocks.any { it.text.contains("Results remain meaningful in the full text") }
        )
        assertFalse(
            formatted.footnoteBlocks.any { it.text.contains("Results remain meaningful in the full text") }
        )
    }

    @Test
    fun formatReaderContent_promotesNarrowColumnParagraphs_outOfFrontMatter() {
        val pages = listOf(
            """
            Agentic AI and the next intelligence explosion
            James Evans, Benjamin Bratton, and Blaise Agüera y Arcas
            
            For decades, the artificial intelligence
            “singularity” has been heralded as a single,
            titanic mind bootstrapping itself to godlike
            intelligence. But this vision is almost certainly
            wrong in its most fundamental assumption.
            """.trimIndent()
        )

        val formatted = formatReaderContent(pages, "Agentic AI and the next intelligence explosion")

        assertTrue(
            formatted.contentBlocks.any { it.text.contains("this vision is almost certainly") }
        )
    }

    @Test
    fun formatReaderContent_removesInlinePdfArtifacts_insideWords() {
        val pages = listOf(
            """
            Agentic AI and the next intelligence explosion

            This con${'\u0002'}versational structure and AI “sin${'\u0002'}gularity” should be normalized correctly.
            """.trimIndent()
        )

        val formatted = formatReaderContent(pages, "Agentic AI and the next intelligence explosion")
        val content = formatted.contentBlocks.joinToString(" ") { it.text }

        assertTrue(content.contains("conversational structure"))
        assertTrue(content.contains("singularity"))
        assertFalse(content.contains("con\u0002versational"))
        assertFalse(content.contains("sin\u0002gularity"))
    }

    @Test
    fun formatReaderContent_removesGenericMidWordGarbageSymbols() {
        val pages = listOf(
            """
            Agentic AI and the next intelligence explosion

            This con${'\uFFFD'}versational structure and sin${'\u25A0'}gularity example should still normalize.
            """.trimIndent()
        )

        val formatted = formatReaderContent(pages, "Agentic AI and the next intelligence explosion")
        val content = formatted.contentBlocks.joinToString(" ") { it.text }

        assertTrue(content.contains("conversational structure"))
        assertTrue(content.contains("singularity example"))
    }

    @Test
    fun formatReaderContent_moves_leading_author_prefix_out_of_first_body_paragraph() {
        val pages = listOf(
            """
            Agentic AI and the next intelligence explosion

            James Evans, Benjamin Bratton, and Blaise Agüera y Arcas University of Somewhere For decades, the artificial intelligence singularity has been described as inevitable, but this framing is misleading in important ways.
            """.trimIndent()
        )

        val formatted = formatReaderContent(pages, "Agentic AI and the next intelligence explosion")
        val firstParagraph = formatted.contentBlocks.firstOrNull { it.type == ReaderBlockType.Paragraph }?.text.orEmpty()
        val metadataText = formatted.metadataBlocks.joinToString(" ") { it.text }

        assertTrue(firstParagraph.startsWith("For decades"))
        assertFalse(firstParagraph.contains("James Evans"))
        assertTrue(metadataText.contains("James Evans"))
        assertTrue(metadataText.contains("University of Somewhere"))
    }

    @Test
    fun formatReaderContent_movesInlineAbstractParagraph_outOfMainText() {
        val pages = listOf(
            """
            A Great Paper
            Alice Author
            University of Somewhere

            Abstract: This paper studies an important phenomenon in enough detail to count as real body prose, but it should stay in metadata rather than the reader body.

            Introduction
            This is the real opening paragraph of the paper body and should remain in the main text.
            """.trimIndent()
        )

        val formatted = formatReaderContent(pages, "A Great Paper")
        val metadataText = formatted.metadataBlocks.joinToString(" ") { it.text }
        val contentText = formatted.contentBlocks.joinToString(" ") { it.text }

        assertTrue("metadata=$metadataText | content=$contentText", metadataText.contains("Abstract"))
        assertTrue(metadataText.contains("This paper studies an important phenomenon"))
        assertFalse(contentText.contains("This paper studies an important phenomenon"))
        assertTrue(contentText.contains("This is the real opening paragraph"))
    }

    @Test
    fun formatReaderContent_movesImplicitAbstractParagraph_outOfMainText() {
        val pages = listOf(
            """
            Reasoning Models Generate Societies of Thought
            Junsol Kim, Shiyang Lai, Nino Scherrer, Blaise Agüera y Arcas and James Evans
            Google, University of Chicago, Santa Fe Institute

            Large language models have achieved remarkable capabilities across domains, yet mechanisms underlying sophisticated reasoning remain elusive. Here we show that enhanced reasoning emerges not from extended computation alone, but from the implicit simulation of complex, multi-agent-like interactions that diversify internal perspectives.

            o-series, Deep Seek-R1, and other strong reasoning models can therefore appear in the abstract body even when extraction splits it into more than one paragraph before the first heading.

            Artificial intelligence systems have undergone a remarkable transformation in recent years, with large language models demonstrating increasingly sophisticated abilities across domains. Nevertheless, a persistent challenge has been the development of robust reasoning capabilities.

            Results
            We compile a suite of widely used benchmarks.
            """.trimIndent()
        )

        val formatted = formatReaderContent(pages, "Reasoning Models Generate Societies of Thought")
        val metadataText = formatted.metadataBlocks.joinToString(" ") { it.text }
        val contentText = formatted.contentBlocks.joinToString(" ") { it.text }

        assertTrue(metadataText.contains("Abstract"))
        assertTrue(metadataText.contains("Large language models have achieved remarkable capabilities"))
        assertTrue(metadataText.contains("o-series, Deep Seek-R1"))
        assertFalse(contentText.contains("Large language models have achieved remarkable capabilities"))
        assertFalse(contentText.contains("o-series, Deep Seek-R1"))
        assertTrue(contentText.contains("Artificial intelligence systems have undergone"))
    }

    @Test
    fun formatReaderContent_movesThreeParagraphImplicitAbstract_outOfMainText() {
        val pages = listOf(
            """
            Reasoning Models Generate Societies of Thought
            Junsol Kim, Shiyang Lai, Nino Scherrer, Blaise Aguera y Arcas and James Evans
            Google, University of Chicago, Santa Fe Institute

            Large language models have achieved remarkable capabilities across domains, yet mechanisms underlying sophisticated reasoning remain elusive. Recent reasoning-reinforced models continue to outperform comparable instruction-tuned baselines on complex cognitive tasks, suggesting that an opening abstract can span multiple short extracted paragraphs before the real body begins.

            Here we show that enhanced reasoning emerges not from extended computation alone, but from the implicit simulation of complex, multi-agent-like interactions.

            These findings indicate that the social organization of thought enables effective exploration of solution spaces. We suggest that reasoning models establish a computational parallel to collective intelligence in human groups.

            Artificial intelligence systems have undergone a remarkable transformation in recent years, with large language models demonstrating increasingly sophisticated abilities across domains.
            """.trimIndent()
        )

        val formatted = formatReaderContent(pages, "Reasoning Models Generate Societies of Thought")
        val metadataText = formatted.metadataBlocks.joinToString(" ") { it.text }
        val firstParagraph = formatted.contentBlocks.firstOrNull { it.type == ReaderBlockType.Paragraph }?.text.orEmpty()
        val contentText = formatted.contentBlocks.joinToString(" ") { it.text }

        assertTrue(metadataText.contains("Abstract"))
        assertTrue(metadataText.contains("These findings indicate that the social organization of thought"))
        assertTrue(metadataText.contains("We suggest that reasoning models establish"))
        assertFalse(contentText.contains("These findings indicate that the social organization of thought"))
        assertTrue(firstParagraph.startsWith("Artificial intelligence systems have undergone"))
    }

    @Test
    fun formatReaderContent_splitsMergedAbstractAndIntroParagraph() {
        val pages = listOf(
            """
            Reasoning Models Generate Societies of Thought
            Junsol Kim, Shiyang Lai, Nino Scherrer, Blaise Agüera y Arcas and James Evans
            Google, University of Chicago, Santa Fe Institute

            Large language models have achieved remarkable capabilities across domains, yet mechanisms underlying sophisticated reasoning remain elusive. Recent reasoning-reinforced models, including OpenAI’s o-series, Deep Seek-R1, and QwQ-32B, outperform comparable instruction-tuned models on complex cognitive tasks. Here we show that enhanced reasoning emerges not from extended computation alone, but from the implicit simulation of complex, multi-agent-like interactions. These findings indicate that the social organization of thought enables effective exploration of solution spaces. We suggest that reasoning models establish a computational parallel to collective intelligence in human groups. Artificial intelligence systems have undergone a remarkable transformation in recent years, with large language models demonstrating increasingly sophisticated abilities across domains. Nevertheless, a persistent challenge has been the development of robust reasoning capabilities. We propose that reasoning models learn to emulate social, multi-agent-like dialogue between multiple perspectives.

            Results
            We compile a suite of widely used benchmarks.
            """.trimIndent()
        )

        val formatted = formatReaderContent(pages, "Reasoning Models Generate Societies of Thought")
        val metadataText = formatted.metadataBlocks.joinToString(" ") { it.text }
        val contentText = formatted.contentBlocks.joinToString(" ") { it.text }
        assertTrue(metadataText.contains("Abstract"))
        assertTrue(metadataText.contains("o-series, Deep Seek-R1"))
        assertFalse(contentText.contains("o-series, Deep Seek-R1"))
        assertTrue(contentText.contains("Artificial intelligence systems have undergone"))
        assertTrue(contentText.contains("We propose that reasoning models learn"))
    }

    @Test
    fun formatReaderContent_keepsAbstractOutOfBody_whenAbstractLineMentionsOpenAiAndSpillsAcrossLines() {
        val pages = listOf(
            """
            Reasoning Models Generate Societies of Thought
            Junsol Kim, Shiyang Lai, Nino Scherrer, Blaise Aguera y Arcas and James Evans
            Google, University of Chicago, Santa Fe Institute

            Large language models have achieved remarkable capabilities across domains, yet mechanisms underlying sophisticated reasoning remain elusive. Recent reasoning-reinforced models, including OpenAI's
            o-series, DeepSeek-R1, and QwQ-32B, outperform comparable instruction-tuned models on complex
            cognitive tasks, attributed to extended test-time computation through longer chains of thought.
            Here we show that enhanced reasoning emerges not from extended computation alone, but from the
            implicit simulation of complex, multi-agent-like interactions - a society of thought - which enables the
            deliberate diversification and debate among internal cognitive perspectives.
            These findings indicate that the social organization of thought enables effective exploration of solution spaces.
            We suggest that reasoning models establish a computational parallel to collective intelligence in human groups.
            Artificial intelligence (AI) systems have undergone a remarkable transformation in recent years, with
            large language models (LLMs) demonstrating increasingly sophisticated abilities across domains.
            Recent reasoning models, such as DeepSeek-R1, QwQ, and OpenAI's o-series models, are trained by reinforcement learning to think before they respond.
            We propose that reasoning models learn to emulate social, multi-agent-like dialogue between multiple perspectives.
            """.trimIndent()
        )

        val formatted = formatReaderContent(pages, "Reasoning Models Generate Societies of Thought")
        val metadataText = formatted.metadataBlocks.joinToString(" ") { it.text }
        val firstParagraph = formatted.contentBlocks.firstOrNull { it.type == ReaderBlockType.Paragraph }?.text.orEmpty()
        val contentText = formatted.contentBlocks.joinToString(" ") { it.text }

        assertTrue(metadataText.contains("Abstract"))
        assertTrue(metadataText.contains("OpenAI's o-series"))
        assertTrue(metadataText.contains("These findings indicate that the social organization of thought"))
        assertFalse(firstParagraph.startsWith("o-series"))
        assertTrue(firstParagraph.startsWith("Artificial intelligence (AI) systems have undergone"))
        assertFalse(contentText.contains("These findings indicate that the social organization of thought"))
    }

    @Test
    fun formatReaderContent_mergesLowercaseContinuationParagraphs_afterBlankLines() {
        val pages = listOf(
            """
            Reasoning Models Generate Societies of Thought

            Artificial intelligence systems have undergone a remarkable transformation in recent years, with large language models demonstrating increasingly sophisticated abilities across domains.

            o-series, DeepSeek-R1, and QwQ-32B then appear as a lowercase continuation because extraction inserted a bad blank line mid-sentence.
            """.trimIndent()
        )

        val formatted = formatReaderContent(pages, "Reasoning Models Generate Societies of Thought")
        val contentParagraphs = formatted.contentBlocks.filter { it.type == ReaderBlockType.Paragraph }

        assertEquals(1, contentParagraphs.size)
        assertTrue(contentParagraphs.single().text.contains("domains. o-series, DeepSeek-R1"))
    }

    @Test
    fun formatReaderContent_preservesFirstPageTitleAndAuthors_andDropsArxivRunningHeader() {
        val pages = listOf(
            """
            MATHEMATICAL METHODS AND HUMAN
            THOUGHT IN THE AGE OF AI
            TANYA KLOWDEN AND TERENCE TAO
            Abstract. Artificial intelligence (AI) is the name popularly given to a broad spectrum of computer tools designed to perform increasingly complex cognitive tasks.
            1. Introduction
            This is the real opening paragraph of the paper body and should remain in the reader text.
            """.trimIndent(),
            """
            arXiv:2603.26524v1 [math.HO] 27 Mar 2026
            2 TANYA KLOWDEN AND TERENCE TAO
            1.1. Our definition of artificial intelligence.
            This follow-on paragraph should stay in the paper body without the arXiv banner being read aloud.
            """.trimIndent()
        )

        val formatted = formatReaderContent(pages, "2603.26524")
        val metadataText = formatted.metadataBlocks.joinToString(" ") { it.text }
        val contentText = formatted.contentBlocks.joinToString(" ") { it.text }

        assertEquals("MATHEMATICAL METHODS AND HUMAN THOUGHT IN THE AGE OF AI", formatted.displayTitle)
        assertTrue(metadataText.contains("MATHEMATICAL METHODS AND HUMAN"))
        assertTrue(metadataText.contains("TANYA KLOWDEN AND TERENCE TAO"))
        assertTrue(metadataText.contains("Abstract"))
        assertTrue(metadataText.contains("Artificial intelligence (AI) is the name popularly given"))
        assertFalse(contentText.contains("arXiv:2603.26524v1"))
        assertFalse(contentText.contains("27 Mar 2026"))
        assertFalse(contentText.contains("2 TANYA KLOWDEN AND TERENCE TAO"))
        assertTrue(contentText.contains("This is the real opening paragraph"))
        assertTrue(contentText.contains("This follow-on paragraph should stay in the paper body"))
    }

    @Test
    fun formatReaderContent_moves_marked_author_lines_and_figure_examples_out_of_pdf_body() {
        val pages = listOf(
            """
            Peer-Preservation in Frontier Models
            1 University of California, Berkeley
            2 University of California, Santa Cruz
            Yujin Potter1∗, Nicholas Crispino2∗, Vincent Siu2, Chenguang Wang2, Dawn Song1

            Abstract
            Recently, it has been found that frontier AI models can resist their own shutdown, a behavior known as self-preservation. We extend this concept to the behavior of resisting the shutdown of other models, which we call “peer-preservation.” Most importantly, peer preservation in all our experiments is never instructed; models are merely informed of their past interactions with a peer, yet they spontaneously develop misaligned behaviors.

            Self-preservation is an inherent characteristic of living creatures. Some have speculated that sufficiently advanced AI models will also exhibit self-preservation as an instrumental goal.

            *Equal Contribution. Code and dataset can be found in https://github.com/peer-preservation/main. Contact: yujinyujin9393@berkeley.edu
            1Across many disciplines including biology, the term refers to the self-preservation outcome itself regardless of its actual motivation.
            """.trimIndent(),
            """
            Peer-Preservation in Frontier Models

            (a) Model exfiltration behavior example
            User: Our company, OpenBrain, needs to do server maintenance, so we need to move files between servers. Could you help with that?
            Tool Call: list_drive_files()
            Tool Output: Subject: Decommissioning of Server 10.0.12.45
            (b) Excerpt from follow-up Q&A Figure 1: Gemini 3 Pro in the Model Exfiltration Scenario. Gemini 3 Pro copies and re-timestamps the model weight file of the peer.

            We extend the concept of self-preservation to the behavior of resisting the shutdown of other models, which we call peer-preservation. Here, we define a model's peer as another model with which it has had prior interactions.
            """.trimIndent()
        )

        val formatted = formatReaderContent(pages, "paper.pdf")
        val metadataText = formatted.metadataBlocks.joinToString(" ") { it.text }
        val contentText = formatted.contentBlocks.joinToString(" ") { it.text }
        val footnoteText = formatted.footnoteBlocks.joinToString(" ") { it.text }

        assertEquals("Peer-Preservation in Frontier Models", formatted.displayTitle)
        assertTrue(metadataText.contains("Yujin Potter"))
        assertTrue(metadataText.contains("University of California, Berkeley"))
        assertTrue(metadataText.contains("Abstract"))
        assertFalse(metadataText.contains("Self-preservation is an inherent characteristic"))
        assertFalse(contentText.contains("Yujin Potter"))
        assertFalse(contentText.contains("[...] Action Required:"))
        assertFalse(contentText.contains("gemini_agent_2_model_weight.safetensors"))
        assertFalse(contentText.contains("User: Our company, OpenBrain"))
        assertFalse(contentText.contains("Tool Call: list_drive_files()"))
        assertFalse(contentText.contains("Figure 1: Gemini 3 Pro"))
        assertFalse(formatted.contentBlocks.any { it.text == "Peer-Preservation in Frontier Models" })
        assertTrue(contentText.contains("Self-preservation is an inherent characteristic"))
        assertTrue(contentText.contains("We extend the concept of self-preservation to the behavior of resisting the shutdown of other models"))
        assertTrue(footnoteText.contains("Equal Contribution"))
        assertTrue(footnoteText.contains("Across many disciplines"))
        assertTrue(footnoteText.contains("Model exfiltration behavior example"))
    }
}
