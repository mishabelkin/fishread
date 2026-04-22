package org.read.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ReaderLocalCleanupTest {

    @Test
    fun readerCleanupAccelerationMode_defaultsToCpu() {
        assertEquals(ReaderCleanupAccelerationMode.Cpu, ReaderCleanupAccelerationMode.fromStorage(null))
        assertEquals(ReaderCleanupAccelerationMode.Cpu, ReaderCleanupAccelerationMode.fromStorage("unknown"))
    }

    @Test
    fun liteRtBackendKeysForMode_usesCpuOnlyFallbackOrder() {
        assertEquals(
            listOf("cpu"),
            liteRtBackendKeysForMode(ReaderCleanupAccelerationMode.Auto)
        )
        assertEquals(
            listOf("cpu"),
            liteRtBackendKeysForMode(ReaderCleanupAccelerationMode.Cpu)
        )
        assertEquals(
            listOf("cpu"),
            liteRtBackendKeysForMode(ReaderCleanupAccelerationMode.Gpu)
        )
        assertEquals(
            listOf("cpu"),
            liteRtBackendKeysForMode(ReaderCleanupAccelerationMode.Npu)
        )
    }

    @Test
    fun liteRtBackendKeysForMode_blocksGpuAndNpuForGemma3nBundles() {
        assertEquals(
            listOf("cpu"),
            liteRtBackendKeysForMode(
                ReaderCleanupAccelerationMode.Auto,
                "gemma-3n-E2B-it-int4.litertlm"
            )
        )
        assertEquals(
            listOf("cpu"),
            liteRtBackendKeysForMode(
                ReaderCleanupAccelerationMode.Npu,
                "gemma-3n-E2B-it-int4.litertlm"
            )
        )
        assertEquals(
            listOf("cpu"),
            liteRtBackendKeysForMode(
                ReaderCleanupAccelerationMode.Gpu,
                "gemma-3n-E2B-it-int4.litertlm"
            )
        )
    }

    @Test
    fun resolveInstalledCleanupModel_prefersExactCompatibleStorageKey() {
        val older = InstalledCleanupModelInfo(
            modelId = "content://models/qwen2.5-1.5b.litertlm",
            fileName = "qwen2.5-1.5b.litertlm",
            sourceUri = "content://models/qwen2.5-1.5b.litertlm",
            absolutePath = "C:/tmp/qwen2.5-1.5b.litertlm",
            sizeBytes = 1,
            lastModified = 1
        )
        val preferred = InstalledCleanupModelInfo(
            modelId = "content://models/gemma3-270m-it-q8.litertlm",
            fileName = "gemma3-270m-it-q8.litertlm",
            sourceUri = "content://models/gemma3-270m-it-q8.litertlm",
            absolutePath = "C:/tmp/gemma3-270m-it-q8.litertlm",
            sizeBytes = 1,
            lastModified = 2
        )

        val resolved = resolveInstalledCleanupModel(
            installedInfos = listOf(older, preferred),
            preferredModelId = preferred.modelId
        )

        assertEquals(preferred.modelId, resolved?.info?.modelId)
    }

    @Test
    fun findInstalledCleanupModelInfo_returnsExactMatchOnly() {
        val first = InstalledCleanupModelInfo(
            modelId = "content://models/gemma3-270m-it-q8.litertlm",
            fileName = "gemma3-270m-it-q8.litertlm",
            sourceUri = "content://models/gemma3-270m-it-q8.litertlm",
            absolutePath = "C:/tmp/gemma3-270m-it-q8.litertlm",
            sizeBytes = 1,
            lastModified = 2
        )
        val second = InstalledCleanupModelInfo(
            modelId = "content://models/qwen2.5-1.5b.litertlm",
            fileName = "qwen2.5-1.5b.litertlm",
            sourceUri = "content://models/qwen2.5-1.5b.litertlm",
            absolutePath = "C:/tmp/qwen2.5-1.5b.litertlm",
            sizeBytes = 1,
            lastModified = 1
        )

        val resolved = findInstalledCleanupModelInfo(
            installedInfos = listOf(first, second),
            modelId = second.modelId
        )

        assertEquals(second.modelId, resolved?.modelId)
    }

    @Test
    fun findInstalledCleanupModelInfo_doesNotFallBackWhenRequestedModelMissing() {
        val first = InstalledCleanupModelInfo(
            modelId = "content://models/gemma3-270m-it-q8.litertlm",
            fileName = "gemma3-270m-it-q8.litertlm",
            sourceUri = "content://models/gemma3-270m-it-q8.litertlm",
            absolutePath = "C:/tmp/gemma3-270m-it-q8.litertlm",
            sizeBytes = 1,
            lastModified = 2
        )

        val resolved = findInstalledCleanupModelInfo(
            installedInfos = listOf(first),
            modelId = "content://models/missing.litertlm"
        )

        assertNull(resolved)
    }

    @Test
    fun resolveInstalledCleanupModel_fallsBackToFirstCompatibleWhenPreferredMissing() {
        val first = InstalledCleanupModelInfo(
            modelId = "content://models/gemma3-270m-it-q8.litertlm",
            fileName = "gemma3-270m-it-q8.litertlm",
            sourceUri = "content://models/gemma3-270m-it-q8.litertlm",
            absolutePath = "C:/tmp/gemma3-270m-it-q8.litertlm",
            sizeBytes = 1,
            lastModified = 2
        )
        val second = InstalledCleanupModelInfo(
            modelId = "content://models/qwen2.5-1.5b.litertlm",
            fileName = "qwen2.5-1.5b.litertlm",
            sourceUri = "content://models/qwen2.5-1.5b.litertlm",
            absolutePath = "C:/tmp/qwen2.5-1.5b.litertlm",
            sizeBytes = 1,
            lastModified = 1
        )

        val resolved = resolveInstalledCleanupModel(
            installedInfos = listOf(first, second),
            preferredModelId = "content://models/missing.litertlm"
        )

        assertEquals(first.modelId, resolved?.info?.modelId)
    }

    @Test
    fun buildCleanupChunks_groupsNearbyParagraphsOnly() {
        val chunks = buildCleanupChunks(
            listOf(
                ReaderBlock(ReaderBlockType.Heading, "Heading"),
                ReaderBlock(ReaderBlockType.Paragraph, sampleParagraph("First")),
                ReaderBlock(ReaderBlockType.Paragraph, sampleParagraph("Second")),
                ReaderBlock(ReaderBlockType.Paragraph, sampleParagraph("Third")),
                ReaderBlock(ReaderBlockType.Metadata, "By Author"),
                ReaderBlock(ReaderBlockType.Paragraph, sampleParagraph("Fourth"))
            )
        )

        assertEquals(2, chunks.size)
        assertEquals(3, chunks[0].paragraphs.size)
        assertEquals(1, chunks[1].paragraphs.size)
        assertNull(chunks[0].contextBefore)
        assertNull(chunks[0].contextAfter)
        assertNull(chunks[1].contextBefore)
    }

    @Test
    fun buildDocumentSummarySource_usesWholeVisibleDocument() {
        val document = ReaderDocument(
            title = "Peer Preservation in Frontier Models",
            sourceLabel = "https://example.com/paper.pdf",
            kind = DocumentKind.PDF,
            pageCount = 12,
            paragraphCount = 3,
            headingCount = 1,
            metadataBlocks = listOf(
                ReaderBlock(ReaderBlockType.Metadata, "Yujin Potter, Nicholas Crispino, Dawn Song"),
                ReaderBlock(ReaderBlockType.Metadata, "University of California, Berkeley")
            ),
            footnoteBlocks = listOf(
                ReaderBlock(ReaderBlockType.Footnote, "*Equal contribution")
            ),
            blocks = listOf(
                ReaderBlock(ReaderBlockType.Heading, "Abstract"),
                ReaderBlock(ReaderBlockType.Paragraph, "Recently, it has been found that frontier AI models can resist their own shutdown."),
                ReaderBlock(ReaderBlockType.Paragraph, "We extend this concept to the behavior of resisting the shutdown of other models."),
                ReaderBlock(ReaderBlockType.Paragraph, "This represents an emergent and underexplored AI safety risk.")
            )
        )

        val source = buildDocumentSummarySource(document)

        assertFalse(source.contains("Title: Peer Preservation in Frontier Models"))
        assertFalse(source.contains("Metadata:"))
        assertFalse(source.contains("Yujin Potter, Nicholas Crispino, Dawn Song"))
        assertTrue(source.contains("[Abstract] Recently, it has been found"))
        assertTrue(source.contains("Recently, it has been found"))
        assertTrue(source.contains("This represents an emergent and underexplored AI safety risk."))
        assertFalse(source.contains("Equal contribution"))
    }

    @Test
    fun buildDocumentSummarySource_keepsAllParagraphs() {
        val paragraphs = (1..20).map { index ->
            ReaderBlock(
                ReaderBlockType.Paragraph,
                "Paragraph $index " + "content ".repeat(120)
            )
        }
        val document = ReaderDocument(
            title = "Long Document",
            sourceLabel = "https://example.com/long.pdf",
            kind = DocumentKind.PDF,
            pageCount = 20,
            paragraphCount = paragraphs.size,
            headingCount = 0,
            metadataBlocks = emptyList(),
            footnoteBlocks = emptyList(),
            blocks = paragraphs
        )

        val source = buildDocumentSummarySource(document)

        assertTrue(source.contains("Paragraph 1"))
        assertTrue(source.contains("Paragraph 10"))
        assertTrue(source.contains("Paragraph 20"))
        assertFalse(source.endsWith(" "))
    }

    @Test
    fun buildDocumentSummaryPrompt_requestsWholeDocumentSummaryFormat() {
        val prompt = buildDocumentSummaryPrompt(
            title = "A Document",
            kind = DocumentKind.WEB,
            sourceText = "First paragraph.\n\nSecond paragraph."
        )

        assertTrue(prompt.contains("Summarize this article for later reading."))
        assertTrue(prompt.contains("Overview:"))
        assertTrue(prompt.contains("Key points:"))
        assertTrue(prompt.contains("Title: A Document"))
        assertTrue(prompt.contains("Text:"))
        assertTrue(prompt.contains("First paragraph."))
        assertTrue(prompt.contains("Second paragraph."))
        assertFalse(prompt.contains("<READ_PARAGRAPH_BREAK>"))
    }

    @Test
    fun isCleanupOutputValid_rejects_paragraph_count_changes() {
        assertFalse(
            isCleanupOutputValid(
                originalParagraphs = listOf(sampleParagraph("One"), sampleParagraph("Two")),
                cleanedParagraphs = listOf(sampleParagraph("Merged"))
            )
        )
    }

    @Test
    fun isCleanupOutputValid_accepts_light_cleanup() {
        assertTrue(
            isCleanupOutputValid(
                originalParagraphs = listOf(
                    "This is a long article paragraph with Share buttons accidentally captured nearby and enough detail to be realistic."
                ),
                cleanedParagraphs = listOf(
                    "This is a long article paragraph with buttons accidentally captured nearby and enough detail to be realistic."
                )
            )
        )
    }

    @Test
    fun document_displayBlocks_prefersPresentationWhenPresent() {
        val document = ReaderDocument(
            title = "Title",
            sourceLabel = "https://example.com",
            kind = DocumentKind.WEB,
            pageCount = 1,
            paragraphCount = 1,
            headingCount = 0,
            metadataBlocks = emptyList(),
            footnoteBlocks = emptyList(),
            blocks = listOf(ReaderBlock(ReaderBlockType.Paragraph, "Raw text")),
            presentation = ReaderPresentation(
                modelId = "library/gemma-3n-E2B-it-int4.litertlm",
                promptVersion = READER_CLEANUP_PROMPT_VERSION,
                blocks = listOf(ReaderBlock(ReaderBlockType.Paragraph, "Cleaned text"))
            )
        )

        assertEquals("Cleaned text", document.displayBlocks.single().text)
    }

    @Test
    fun buildCleanupPrompt_requiresExplicitParagraphSeparator() {
        val prompt = buildCleanupPrompt(
            paragraphs = listOf(sampleParagraph("One"), sampleParagraph("Two")),
            contextBefore = sampleParagraph("Before"),
            contextAfter = sampleParagraph("After"),
            instructions = "Preserve meaning exactly."
        )

        assertTrue(prompt.contains("<READ_PARAGRAPH_BREAK>"))
        assertTrue(prompt.contains("[Target Paragraph 1]"))
        assertTrue(prompt.contains("[/Target Paragraph 2]"))
        assertTrue(prompt.contains("[Context Before]"))
        assertTrue(prompt.contains("[Context After]"))
    }

    @Test
    fun buildCleanupPrompt_mentionsCopyrightFootersAndTrackingHashes() {
        val prompt = buildCleanupPrompt(
            paragraphs = listOf(sampleParagraph("One")),
            contextBefore = null,
            contextAfter = null,
            instructions = buildCleanupInstructions()
        )

        assertTrue(prompt.contains("All Rights Reserved"))
        assertTrue(prompt.contains("tracking or hash-like strings"))
        assertTrue(prompt.contains("Preserve meaningful dates, prices, statistics, identifiers, and article numbers"))
    }

    @Test
    fun buildWebCleanupInstructions_mentionsDuplicatedBoilerplate() {
        val instructions = buildCleanupInstructions(CleanupProfile.WEB)

        assertTrue(instructions.contains("duplicated boilerplate lines"))
        assertTrue(instructions.contains("related-links fragments"))
        assertTrue(instructions.contains("Preserve inline linked text exactly"))
    }

    @Test
    fun isSupportedMediaPipeTaskCleanupFile_rejectsGemma3nTaskBundle() {
        assertFalse(isSupportedMediaPipeTaskCleanupFile("gemma-3n-E2B-it-int4.task"))
        assertFalse(isSupportedMediaPipeTaskCleanupFile("gemma3-4b-it-int4-web.task"))
    }

    @Test
    fun isSupportedLiteRtLmCleanupFile_acceptsGemma3nLitertlmBundle() {
        assertTrue(isSupportedLiteRtLmCleanupFile("gemma-3n-E2B-it-int4.litertlm"))
        assertTrue(isSupportedCleanupBackendFile("gemma-3n-E2B-it-int4.litertlm"))
    }

    @Test
    fun isSupportedLiteRtLmCleanupFile_rejectsWebBundleWithCopySuffix() {
        assertFalse(isSupportedLiteRtLmCleanupFile("gemma-3n-E2B-it-int4-Web (1).litertlm"))
        assertFalse(isSupportedCleanupBackendFile("gemma-3n-E2B-it-int4-Web (1).litertlm"))
    }

    @Test
    fun isSupportedMediaPipeTaskCleanupFile_acceptsGemma31BTaskBundle() {
        assertTrue(isSupportedMediaPipeTaskCleanupFile("gemma3-1b-it-int4.task"))
        assertTrue(isSupportedCleanupBackendFile("gemma-3-1b-it-int4.task"))
    }

    @Test
    fun isSupportedLiteRtLmCleanupFile_acceptsGemma31BLitertlmBundle() {
        assertTrue(isSupportedLiteRtLmCleanupFile("gemma-3-1b-it-int4.litertlm"))
        assertTrue(isSupportedCleanupBackendFile("gemma-3-1b-it-int4.litertlm"))
    }

    @Test
    fun isSupportedLiteRtLmCleanupFile_acceptsQwenLitertlmBundle() {
        assertTrue(isSupportedLiteRtLmCleanupFile("qwen2.5-1.5b-instruct-int8.litertlm"))
        assertTrue(isSupportedCleanupBackendFile("qwen2.5-1.5b-instruct-int8.litertlm"))
    }

    @Test
    fun isSupportedCleanupBackendFile_rejectsWebTaskBundle() {
        assertFalse(isSupportedCleanupBackendFile("gemma3-4b-it-int4-web.task"))
    }

    @Test
    fun cleanupModelDisplayName_formatsFileName() {
        assertEquals(
            "qwen2.5 1.5b instruct int8",
            cleanupModelDisplayName("qwen2.5-1.5b-instruct-int8.litertlm")
        )
    }

    @Test
    fun discoverCleanupModel_prefersArchiveMetadataWhenPresent() {
        val tempFile = File.createTempFile("cleanup-model-test", ".litertlm")
        ZipOutputStream(tempFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write("""{"display_name":"Qwen 2.5 1.5B Instruct","runtime":"litertlm"}""".toByteArray())
            zip.closeEntry()
        }

        val discovered = discoverCleanupModel(
            InstalledCleanupModelInfo(
                modelId = "content://models/${tempFile.name}",
                fileName = tempFile.name,
                sourceUri = "content://models/${tempFile.name}",
                absolutePath = tempFile.absolutePath,
                sizeBytes = tempFile.length(),
                lastModified = tempFile.lastModified()
            )
        )

        assertEquals("Qwen 2.5 1.5B Instruct", discovered?.displayName)
        assertEquals(CleanupBackendKind.LiteRtLm, discovered?.runtimeSpec?.backendKind)

        tempFile.delete()
    }

    @Test
    fun mergeStreamingText_accumulatesTokenAndCumulativeUpdates() {
        assertEquals("Hello world", mergeStreamingText("Hello", " world"))
        assertEquals("Hello world", mergeStreamingText("Hello", "Hello world"))
        assertEquals("Hello world", mergeStreamingText("Hello wor", "world"))
        assertEquals("Hello world", mergeStreamingText("Hello world", "world"))
        assertEquals("Hello, world.", mergeStreamingText("Hello", ", world."))
        assertEquals("Hello, world.", mergeStreamingText("Hello,", " world."))
    }

    @Test
    fun parseCleanupParagraphs_prefersExplicitSeparator() {
        val parsed = parseCleanupParagraphs(
            response = """
                Cleaned first paragraph.
                <READ_PARAGRAPH_BREAK>
                Cleaned second paragraph.
            """.trimIndent(),
            expectedParagraphCount = 2
        )

        assertEquals(listOf("Cleaned first paragraph.", "Cleaned second paragraph."), parsed)
    }

    @Test
    fun isCleanupOutputValid_acceptsDroppedChromeParagraph() {
        assertTrue(
            isCleanupOutputValid(
                originalParagraphs = listOf(
                    "Share on Facebook and email this article to a friend.",
                    sampleParagraph("Body")
                ),
                cleanedParagraphs = listOf(
                    "<READ_DROP_PARAGRAPH>",
                    sampleParagraph("Body")
                )
            )
        )
    }

    @Test
    fun isCleanupOutputValid_acceptsStrongerWebCleanup() {
        assertTrue(
            isCleanupOutputValid(
                originalParagraphs = listOf(
                    "Safeframe Container Share on Facebook Print Email. The markets fell sharply after the announcement and investors reacted throughout the afternoon."
                ),
                cleanedParagraphs = listOf(
                    "The markets fell sharply after the announcement and investors reacted throughout the afternoon."
                ),
                cleanupProfile = CleanupProfile.WEB
            )
        )
    }

    @Test
    fun isCleanupOutputValid_rejectsOmissionOfLinkedBodyPhrase() {
        assertFalse(
            isCleanupOutputValid(
                originalParagraphs = listOf(
                    "529 plans can also be rolled into a Roth IRA, subject to limitations and annual contribution rules."
                ),
                cleanedParagraphs = listOf(
                    "529 plans can also be rolled into, subject to limitations and annual contribution rules."
                ),
                cleanupProfile = CleanupProfile.WEB
            )
        )
    }

    @Test
    fun validateCleanupOutput_rejectsDroppedLinkedNamesAndOrganizations() {
        val result = validateCleanupOutput(
            originalParagraphs = listOf(
                "According to Terence Tao, researchers at OpenAI and Google DeepMind are testing whether AlphaEvolve can help mathematicians solve hard proofs."
            ),
            cleanedParagraphs = listOf(
                "According to researchers, labs are testing whether it can help mathematicians solve hard proofs."
            ),
            cleanupProfile = CleanupProfile.WEB
        )

        assertTrue(result is CleanupOutputValidationResult.Rejected)
    }

    @Test
    fun validateCleanupOutput_reportsSpecificRejectionReason() {
        val result = validateCleanupOutput(
            originalParagraphs = listOf(
                "savers should compare rollover penalties, age limits, and residency requirements before transferring assets."
            ),
            cleanedParagraphs = listOf(
                "savers should compare and before transferring assets."
            ),
            cleanupProfile = CleanupProfile.WEB
        )

        assertEquals(
            CleanupOutputValidationResult.Rejected(CleanupValidationFailureReason.ParagraphContentLoss),
            result
        )
    }

    @Test
    fun classifyCleanupChangeKinds_detectsDehyphenationAndBoilerplateRemoval() {
        val changeKinds = classifyCleanupChangeKinds(
            original = "Share on Facebook. A co- operative system improved quickly after the release.",
            cleaned = "A cooperative system improved quickly after the release.",
            cleanupProfile = CleanupProfile.WEB
        )

        assertTrue(changeKinds.contains(CleanupChangeKind.Dehyphenation.storageKey))
        assertTrue(changeKinds.contains(CleanupChangeKind.BoilerplateRemoval.storageKey))
    }

    @Test
    fun diagnosticsCounts_sortsByCountThenKey() {
        assertEquals(
            listOf(
                CleanupDiagnosticsCount(key = "boilerplate_removal", count = 3),
                CleanupDiagnosticsCount(key = "dehyphenation", count = 3),
                CleanupDiagnosticsCount(key = "minor_rewrite", count = 1)
            ),
            diagnosticsCounts(
                mapOf(
                    "minor_rewrite" to 1,
                    "dehyphenation" to 3,
                    "boilerplate_removal" to 3
                )
            )
        )
    }

    @Test
    fun summarizeCleanupModelFailure_includesRootCause() {
        val summary = summarizeCleanupModelFailure(
            IllegalStateException(
                "Cleanup request failed",
                IllegalArgumentException("Model output could not be parsed")
            )
        )

        assertEquals(
            "IllegalStateException: Cleanup request failed | caused by IllegalArgumentException: Model output could not be parsed",
            summary
        )
    }

    @Test
    fun buildCleanupChunks_includesShortNoisyParagraphs() {
        val chunks = buildCleanupChunks(
            listOf(
                ReaderBlock(ReaderBlockType.Paragraph, "Safeframe Container"),
                ReaderBlock(ReaderBlockType.Paragraph, sampleParagraph("Body"))
            )
        )

        assertEquals(1, chunks.size)
        assertEquals(2, chunks.single().paragraphs.size)
    }

    @Test
    fun buildCleanupChunks_addsNeighborParagraphsAsReadOnlyContext() {
        val chunks = buildCleanupChunks(
            listOf(
                ReaderBlock(ReaderBlockType.Paragraph, sampleParagraph("Context before")),
                ReaderBlock(ReaderBlockType.Paragraph, sampleParagraph("Owned one")),
                ReaderBlock(ReaderBlockType.Paragraph, sampleParagraph("Owned two")),
                ReaderBlock(ReaderBlockType.Paragraph, sampleParagraph("Context after"))
            )
        )

        assertEquals(2, chunks.size)
        assertNull(chunks[0].contextBefore)
        assertEquals(sampleParagraph("Context after"), chunks[0].contextAfter)
        assertEquals(sampleParagraph("Owned two"), chunks[1].contextBefore)
        assertNull(chunks[1].contextAfter)
    }

    @Test
    fun buildCleanupChunks_pdf_skipsFigureCaptionStyleParagraphs() {
        val chunks = buildCleanupChunks(
            blocks = listOf(
                ReaderBlock(ReaderBlockType.Paragraph, "Figure 2 Example architecture overview."),
                ReaderBlock(ReaderBlockType.Paragraph, sampleParagraph("Body one")),
                ReaderBlock(ReaderBlockType.Paragraph, sampleParagraph("Body two")),
                ReaderBlock(ReaderBlockType.Paragraph, sampleParagraph("Body three"))
            ),
            cleanupProfile = CleanupProfile.PDF
        )

        assertEquals(1, chunks.size)
        assertEquals(
            listOf(
                sampleParagraph("Body one"),
                sampleParagraph("Body two"),
                sampleParagraph("Body three")
            ),
            chunks.single().paragraphs
        )
    }

    @Test
    fun buildPdfCleanupInstructions_mentionsFormattingRepair() {
        val instructions = buildCleanupInstructions(CleanupProfile.PDF)

        assertTrue(instructions.contains("Repair broken line wraps"))
        assertTrue(instructions.contains("line-break hyphenation"))
        assertTrue(instructions.contains("Do not drop paragraphs"))
        assertTrue(instructions.contains("numeric citation markers"))
        assertTrue(instructions.contains("arXiv identifiers"))
    }

    @Test
    fun isCleanupOutputValid_rejectsDroppedPdfParagraph() {
        assertFalse(
            isCleanupOutputValid(
                originalParagraphs = listOf(sampleParagraph("PDF body")),
                cleanedParagraphs = listOf(CLEANUP_DROP_PARAGRAPH),
                cleanupProfile = CleanupProfile.PDF
            )
        )
    }

    @Test
    fun isCleanupOutputValid_acceptsPdfCitationRemoval() {
        assertTrue(
            isCleanupOutputValid(
                originalParagraphs = listOf(
                    "These findings were confirmed in multiple studies [12, 13] and later replicated (14) in follow-up work."
                ),
                cleanedParagraphs = listOf(
                    "These findings were confirmed in multiple studies and later replicated in follow-up work."
                ),
                cleanupProfile = CleanupProfile.PDF
            )
        )
    }

    @Test
    fun isCleanupOutputValid_acceptsPdfDehyphenationAndCitationRemoval() {
        assertTrue(
            isCleanupOutputValid(
                originalParagraphs = listOf(
                    "This conver-\nsational benchmark matched prior work [7] across several datasets."
                ),
                cleanedParagraphs = listOf(
                    "This conversational benchmark matched prior work across several datasets."
                ),
                cleanupProfile = CleanupProfile.PDF
            )
        )
    }

    @Test
    fun isCleanupOutputValid_acceptsPdfRunningHeaderRemoval() {
        assertTrue(
            isCleanupOutputValid(
                originalParagraphs = listOf(
                    "arXiv:2603.26524v1 [math.HO] 27 Mar 2026 2 TANYA KLOWDEN AND TERENCE TAO This follow-on paragraph should stay in the paper body without the arXiv banner being read aloud."
                ),
                cleanedParagraphs = listOf(
                    "This follow-on paragraph should stay in the paper body without the arXiv banner being read aloud."
                ),
                cleanupProfile = CleanupProfile.PDF
            )
        )
    }

    @Test
    fun supportsDocumentCleanupBackend_onlyAllowsMediaPipeTask() {
        assertTrue(supportsDocumentCleanupBackend(CleanupBackendKind.MediaPipeTask))
        assertFalse(supportsDocumentCleanupBackend(CleanupBackendKind.LiteRtLm))
    }

    @Test
    fun isCleanupOutputValid_rejectsUnexpectedDuplicationAcrossParagraphs() {
        assertFalse(
            isCleanupOutputValid(
                originalParagraphs = listOf(
                    "First paragraph about one argument with enough content to be realistic and distinct from the next paragraph.",
                    "Second paragraph about another argument with enough content to be realistic and distinct from the first paragraph."
                ),
                cleanedParagraphs = listOf(
                    "First paragraph about one argument with enough content to be realistic and distinct from the next paragraph.",
                    "First paragraph about one argument with enough content to be realistic and distinct from the next paragraph."
                )
            )
        )
    }

    private fun sampleParagraph(label: String): String {
        return "$label paragraph with enough content to be eligible for cleanup and long enough to look like real article prose in the pipeline."
    }
}
