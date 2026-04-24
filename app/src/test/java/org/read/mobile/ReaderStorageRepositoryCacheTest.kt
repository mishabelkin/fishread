package org.read.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ReaderStorageRepositoryCacheTest {

    @Test
    fun loadPreferredCachedDocumentForRemoteOpen_returnsStrongExactRemoteCache() {
        val repository = ReaderStorageRepository(RuntimeEnvironment.getApplication())
        repository.clearDocumentCache()

        val sourceLabel = "https://www.wsj.com/tech/ai/example-article?mod=hp_lead_pos7"
        val cached = remoteWebDocument(
            sourceLabel = sourceLabel,
            paragraphCount = 6,
            charsPerParagraph = 340
        )
        repository.saveDocumentCache(cached)

        val restored = repository.loadPreferredCachedDocumentForRemoteOpen(sourceLabel)

        assertNotNull(restored)
        assertEquals(sourceLabel, restored?.sourceLabel)
        assertEquals(cached.blocks.map { it.text }, restored?.blocks?.map { it.text })
    }

    @Test
    fun loadPreferredCachedDocumentForRemoteOpen_rejectsWeakExactCacheAndSkipsCanonicalFallback() {
        val repository = ReaderStorageRepository(RuntimeEnvironment.getApplication())
        repository.clearDocumentCache()

        val requestedUrl = "https://www.wsj.com/tech/ai/example-article?mod=hp_lead_pos7"
        repository.saveDocumentCache(
            remoteWebDocument(
                sourceLabel = requestedUrl,
                paragraphCount = 3,
                charsPerParagraph = 320
            )
        )
        repository.saveDocumentCache(
            remoteWebDocument(
                sourceLabel = "https://www.wsj.com/tech/ai/example-article",
                paragraphCount = 7,
                charsPerParagraph = 360
            )
        )

        val restored = repository.loadPreferredCachedDocumentForRemoteOpen(requestedUrl)

        assertNull(restored)
    }

    @Test
    fun loadUsableCachedDocumentForSource_stillReturnsUsableWeakExactCacheForHistoryOpen() {
        val repository = ReaderStorageRepository(RuntimeEnvironment.getApplication())
        repository.clearDocumentCache()

        val sourceLabel = "https://example.com/short-but-readable-article"
        val cached = remoteWebDocument(
            sourceLabel = sourceLabel,
            paragraphCount = 3,
            charsPerParagraph = 300
        )
        repository.saveDocumentCache(cached)

        val restored = repository.loadUsableCachedDocumentForSource(sourceLabel)

        assertNotNull(restored)
        assertEquals(sourceLabel, restored?.sourceLabel)
    }

    private fun remoteWebDocument(
        sourceLabel: String,
        paragraphCount: Int,
        charsPerParagraph: Int
    ): ReaderDocument {
        val blocks = buildList {
            add(ReaderBlock(ReaderBlockType.Heading, "Example heading"))
            repeat(paragraphCount) { index ->
                add(
                    ReaderBlock(
                        ReaderBlockType.Paragraph,
                        "Paragraph ${index + 1}: " + "forest ".repeat(charsPerParagraph / 7 + 1).trim()
                    )
                )
            }
        }

        return ReaderDocument(
            title = "Example remote document",
            sourceLabel = sourceLabel,
            kind = DocumentKind.WEB,
            pageCount = 1,
            paragraphCount = paragraphCount,
            headingCount = 1,
            metadataBlocks = emptyList(),
            footnoteBlocks = emptyList(),
            blocks = blocks
        )
    }
}
