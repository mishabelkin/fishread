package org.read.mobile

import android.app.Application
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale

data class ReaderInitialState(
    val history: List<HistoryEntry>,
    val bookmarks: List<BookmarkEntry>,
    val readingList: List<ReadingListEntry>,
    val restoredDocument: ReaderDocument?
)

data class HistoryDeleteResult(
    val history: List<HistoryEntry>,
    val bookmarks: List<BookmarkEntry>
)

class ReaderStorageRepository(
    private val application: Application
) {
    companion object {
        private const val MAX_CACHE_BYTES = 96L * 1024L * 1024L
        private const val MAX_CACHE_FILE_COUNT = 24
        private const val DOCUMENT_CACHE_SCHEMA_VERSION = 15
    }

    private val storageLock = Any()
    private val historyPrefs = application.getSharedPreferences("pdf_reader_history", Context.MODE_PRIVATE)
    private val bookmarkPrefs = application.getSharedPreferences("pdf_reader_bookmarks", Context.MODE_PRIVATE)
    private val readingListPrefs = application.getSharedPreferences("pdf_reader_reading_list", Context.MODE_PRIVATE)
    private val progressPrefs = application.getSharedPreferences("pdf_reader_progress", Context.MODE_PRIVATE)
    private val cacheDir = application.filesDir.resolve("document_cache").apply { mkdirs() }

    fun loadInitialState(): ReaderInitialState {
        return withStorageLock {
            val history = loadHistory()
            val bookmarks = loadBookmarks()
            val readingList = loadReadingList()
            ReaderInitialState(
                history = history,
                bookmarks = bookmarks,
                readingList = readingList,
                restoredDocument = restoreLastOpenedDocument(history)
            )
        }
    }

    fun addBookmark(
        document: ReaderDocument,
        blockIndex: Int,
        charOffset: Int,
        snippet: String?
    ): List<BookmarkEntry> {
        return withStorageLock {
            val block = document.blocks.getOrNull(blockIndex) ?: return@withStorageLock loadBookmarks()
            val fallbackSnippet = block.text.replace(Regex("\\s+"), " ").trim().take(140)
            val normalizedSnippet = snippet
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                ?.take(140)
                ?.takeIf { it.isNotBlank() }
                ?: fallbackSnippet
            val newBookmark = BookmarkEntry(
                documentTitle = document.title,
                sourceLabel = document.sourceLabel,
                blockIndex = blockIndex.coerceAtLeast(0),
                charOffset = charOffset.coerceAtLeast(0),
                label = null,
                sectionHeading = resolveBookmarkSectionHeading(document, blockIndex),
                snippet = normalizedSnippet,
                createdAt = System.currentTimeMillis()
            )
            val updated = listOf(newBookmark) + loadBookmarks().filterNot {
                it.sourceLabel == newBookmark.sourceLabel &&
                    it.blockIndex == newBookmark.blockIndex &&
                    kotlin.math.abs(it.charOffset - newBookmark.charOffset) < 4
            }
            val trimmed = updated.take(200)
            persistBookmarks(trimmed)
            trimmed
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntry): List<BookmarkEntry> {
        return withStorageLock {
            val updated = loadBookmarks().filterNot {
                it.sourceLabel == bookmark.sourceLabel &&
                    it.blockIndex == bookmark.blockIndex &&
                    it.charOffset == bookmark.charOffset &&
                    it.createdAt == bookmark.createdAt
            }
            persistBookmarks(updated)
            pruneOrphanedCacheFiles()
            updated
        }
    }

    fun renameBookmark(bookmark: BookmarkEntry, label: String): List<BookmarkEntry> {
        return withStorageLock {
            val normalizedLabel = label.replace(Regex("\\s+"), " ").trim().take(80).takeIf { it.isNotBlank() }
            val updated = loadBookmarks().map {
                if (
                    it.sourceLabel == bookmark.sourceLabel &&
                    it.blockIndex == bookmark.blockIndex &&
                    it.charOffset == bookmark.charOffset &&
                    it.createdAt == bookmark.createdAt
                ) {
                    it.copy(label = normalizedLabel)
                } else {
                    it
                }
            }
            persistBookmarks(updated)
            updated
        }
    }

    fun addDocumentToReadingList(document: ReaderDocument): List<ReadingListEntry> {
        return withStorageLock {
            val existing = loadReadingList().firstOrNull { readingListDedupKey(it) == readingListDedupKey(document) }
            val entry = ReadingListEntry(
                title = document.title,
                summary = buildHistorySummary(document),
                kind = document.kind,
                sourceLabel = document.sourceLabel,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                isDone = existing?.isDone ?: false
            )
            val updated = dedupeReadingList(listOf(entry) + loadReadingList().filterNot {
                readingListDedupKey(it) == readingListDedupKey(entry)
            })
            persistReadingList(updated)
            updated
        }
    }

    fun addHistoryItemToReadingList(item: HistoryEntry): List<ReadingListEntry> {
        return withStorageLock {
            val existing = loadReadingList().firstOrNull { readingListDedupKey(it) == readingListDedupKey(item) }
            val entry = ReadingListEntry(
                title = item.title,
                summary = item.summary,
                kind = item.kind,
                sourceLabel = item.sourceLabel,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                isDone = existing?.isDone ?: false
            )
            val updated = dedupeReadingList(listOf(entry) + loadReadingList().filterNot {
                readingListDedupKey(it) == readingListDedupKey(entry)
            })
            persistReadingList(updated)
            updated
        }
    }

    fun removeReadingListItem(item: ReadingListEntry): List<ReadingListEntry> {
        return withStorageLock {
            val updated = loadReadingList().filterNot {
                it.sourceLabel == item.sourceLabel &&
                    it.addedAt == item.addedAt
            }
            persistReadingList(updated)
            pruneOrphanedCacheFiles()
            updated
        }
    }

    fun setReadingListDone(item: ReadingListEntry, isDone: Boolean): List<ReadingListEntry> {
        return withStorageLock {
            val updated = loadReadingList().map {
                if (it.sourceLabel == item.sourceLabel && it.addedAt == item.addedAt) {
                    it.copy(isDone = isDone)
                } else {
                    it
                }
            }
            persistReadingList(updated)
            updated
        }
    }

    fun deleteHistoryItem(item: HistoryEntry): HistoryDeleteResult {
        return withStorageLock {
            val updatedHistory = loadHistory().filterNot {
                it.title == item.title &&
                    it.sourceLabel == item.sourceLabel &&
                    it.openedAt == item.openedAt
            }
            persistHistory(updatedHistory)

            val readingList = loadReadingList()
            val preservedByReadingList = readingList.any { it.sourceLabel == item.sourceLabel }
            val updatedBookmarks = if (preservedByReadingList) {
                loadBookmarks()
            } else {
                loadBookmarks().filterNot { it.sourceLabel == item.sourceLabel }
            }

            if (!preservedByReadingList) {
                deleteCachedDocument(item.sourceLabel)
                clearReadingProgress(item.sourceLabel)
                persistBookmarks(updatedBookmarks)
            }

            if (lastOpenedSourceLabel() == item.sourceLabel) {
                setLastOpenedSourceLabel(
                    updatedHistory.firstOrNull()?.sourceLabel
                        ?: readingList.firstOrNull()?.sourceLabel
                )
            }

            pruneOrphanedCacheFiles()

            HistoryDeleteResult(
                history = updatedHistory,
                bookmarks = updatedBookmarks
            )
        }
    }

    fun saveHistoryForDocument(document: ReaderDocument): List<HistoryEntry> {
        return withStorageLock {
            val updated = dedupeHistory(
                listOf(
                    HistoryEntry(
                        title = document.title,
                        summary = buildHistorySummary(document),
                        kind = document.kind,
                        sourceLabel = document.sourceLabel,
                        openedAt = System.currentTimeMillis()
                    )
                ) + loadHistory()
            )

            val trimmed = updated.take(20)
            persistHistory(trimmed)
            pruneOrphanedCacheFiles()
            trimmed
        }
    }

    fun loadUsableCachedDocument(sourceLabel: String): ReaderDocument? {
        return withStorageLock {
            loadCachedDocument(sourceLabel)?.takeIf(::shouldUseCachedDocument)
        }
    }

    fun loadUsableCachedDocumentForSource(sourceLabel: String): ReaderDocument? {
        return withStorageLock {
            loadCachedDocument(sourceLabel)?.takeIf(::shouldUseCachedDocument)?.let { exactMatch ->
                return@withStorageLock exactMatch
            }

            val canonicalSource = canonicalHistorySource(sourceLabel)
            if (canonicalSource.isBlank()) {
                return@withStorageLock null
            }

            knownSourceLabels()
                .firstOrNull { candidate ->
                    candidate != sourceLabel && canonicalHistorySource(candidate) == canonicalSource
                }
                ?.let(::loadCachedDocument)
                ?.takeIf(::shouldUseCachedDocument)
        }
    }

    fun loadPreferredCachedDocumentForRemoteOpen(sourceLabel: String): ReaderDocument? {
        return withStorageLock {
            loadCachedDocument(sourceLabel)
                ?.takeIf(::shouldUseCachedDocument)
                ?.takeIf(::isStrongRemoteWebCacheCandidate)
        }
    }

    fun saveDocumentCache(document: ReaderDocument) {
        withStorageLock {
            val json = JSONObject()
                .put("schemaVersion", DOCUMENT_CACHE_SCHEMA_VERSION)
                .put("title", document.title)
                .put("sourceLabel", document.sourceLabel)
                .put("kind", document.kind.name)
                .put("pageCount", document.pageCount)
                .put("paragraphCount", document.paragraphCount)
                .put("headingCount", document.headingCount)
                .put("metadataBlocks", blocksToJson(document.metadataBlocks))
                .put("footnoteBlocks", blocksToJson(document.footnoteBlocks))
                .put("blocks", blocksToJson(document.blocks))
                .put("presentation", presentationToJson(document.presentation))
                .put("cleanupDiagnostics", cleanupDiagnosticsToJson(document.cleanupDiagnostics))
                .put("summary", summaryToJson(document.summary))

            val cacheFile = cacheFileFor(document.sourceLabel)
            cacheFile.writeText(json.toString())
            cacheFile.setLastModified(System.currentTimeMillis())
            evictCacheIfNeeded()
        }
    }

    fun cacheSummary(): CacheSummary {
        return withStorageLock {
            val files = cacheDir.listFiles().orEmpty().filter { it.isFile }
            CacheSummary(
                fileCount = files.size,
                totalBytes = files.sumOf { it.length().coerceAtLeast(0L) }
            )
        }
    }

    fun clearDocumentCache(): CacheClearResult {
        return withStorageLock {
            var removedFileCount = 0
            var freedBytes = 0L
            cacheDir.listFiles().orEmpty()
                .filter { it.isFile }
                .forEach { file ->
                    freedBytes += file.length().coerceAtLeast(0L)
                    if (file.delete()) {
                        removedFileCount += 1
                    }
                }
            CacheClearResult(
                removedFileCount = removedFileCount,
                freedBytes = freedBytes
            )
        }
    }

    fun rememberLastOpened(document: ReaderDocument) {
        withStorageLock {
            setLastOpenedSourceLabel(document.sourceLabel)
        }
    }

    fun saveReadingProgress(sourceLabel: String, blockIndex: Int, scrollOffset: Int) {
        withStorageLock {
            val json = JSONObject()
                .put("blockIndex", blockIndex)
                .put("scrollOffset", scrollOffset)
            val serialized = json.toString()
            val editor = progressPrefs.edit()
            progressKeysFor(sourceLabel).forEach { key ->
                editor.putString(key, serialized)
            }
            editor.commit()
        }
    }

    fun readingProgressFor(sourceLabel: String): ReadingProgress? {
        return withStorageLock {
            progressKeysFor(sourceLabel)
                .firstNotNullOfOrNull { key ->
                    val raw = progressPrefs.getString(key, null) ?: return@firstNotNullOfOrNull null
                    runCatching {
                        val json = JSONObject(raw)
                        ReadingProgress(
                            blockIndex = json.optInt("blockIndex", 0),
                            scrollOffset = json.optInt("scrollOffset", 0)
                        )
                    }.getOrNull()
                }
        }
    }

    private fun loadHistory(): List<HistoryEntry> {
        val raw = historyPrefs.getString("items", null) ?: return emptyList()
        return runCatching {
            val parsed = buildList {
                val array = JSONArray(raw)
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        HistoryEntry(
                            title = item.getString("title"),
                            summary = item.optString("summary").ifBlank {
                                fallbackHistorySummary(
                                    title = item.getString("title"),
                                    sourceLabel = item.getString("sourceLabel")
                                )
                            },
                            kind = item.optString("kind")
                                .takeIf { it.isNotBlank() }
                                ?.let { DocumentKind.valueOf(it) }
                                ?: inferDocumentKind(item.getString("sourceLabel")),
                            sourceLabel = item.getString("sourceLabel"),
                            openedAt = item.getLong("openedAt")
                        )
                    )
                }
            }
            dedupeHistory(parsed)
        }.getOrDefault(emptyList())
    }

    private fun loadBookmarks(): List<BookmarkEntry> {
        val raw = bookmarkPrefs.getString("items", null) ?: return emptyList()
        return runCatching {
            val cache = mutableMapOf<String, ReaderDocument?>()
            val parsed = buildList {
                val array = JSONArray(raw)
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    add(
                        BookmarkEntry(
                            documentTitle = item.optString("documentTitle").ifBlank {
                                application.getString(R.string.saved_document_fallback)
                            },
                            sourceLabel = item.getString("sourceLabel"),
                            blockIndex = item.optInt("blockIndex", 0).coerceAtLeast(0),
                            charOffset = item.optInt("charOffset", 0).coerceAtLeast(0),
                            label = item.optString("label", "").trim().takeIf { it.isNotBlank() },
                            sectionHeading = item.optString("sectionHeading", "").trim().takeIf { it.isNotBlank() },
                            snippet = item.optString("snippet").ifBlank {
                                application.getString(R.string.saved_location_fallback)
                            },
                            createdAt = item.getLong("createdAt")
                        )
                    )
                }
            }
            val enriched = parsed.map { bookmark ->
                if (!bookmark.sectionHeading.isNullOrBlank()) {
                    bookmark
                } else {
                    val document = cache.getOrPut(bookmark.sourceLabel) { loadCachedDocument(bookmark.sourceLabel) }
                    bookmark.copy(sectionHeading = resolveBookmarkSectionHeading(document, bookmark.blockIndex))
                }
            }
            if (enriched != parsed) {
                persistBookmarks(enriched)
            }
            enriched
        }.getOrDefault(emptyList())
    }

    private fun loadReadingList(): List<ReadingListEntry> {
        val raw = readingListPrefs.getString("items", null) ?: return emptyList()
        return runCatching {
            dedupeReadingList(
                buildList {
                    val array = JSONArray(raw)
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        add(
                            ReadingListEntry(
                                title = item.getString("title"),
                                summary = item.optString("summary").ifBlank {
                                    fallbackHistorySummary(
                                        title = item.getString("title"),
                                        sourceLabel = item.getString("sourceLabel")
                                    )
                                },
                                kind = item.optString("kind")
                                    .takeIf { it.isNotBlank() }
                                    ?.let { DocumentKind.valueOf(it) }
                                    ?: inferDocumentKind(item.getString("sourceLabel")),
                                sourceLabel = item.getString("sourceLabel"),
                                addedAt = item.optLong("addedAt", System.currentTimeMillis()),
                                isDone = item.optBoolean("isDone", false)
                            )
                        )
                    }
                }
            )
        }.getOrDefault(emptyList())
    }

    private fun persistHistory(items: List<HistoryEntry>) {
        val serialized = JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject()
                        .put("title", item.title)
                        .put("summary", item.summary)
                        .put("kind", item.kind.name)
                        .put("sourceLabel", item.sourceLabel)
                        .put("openedAt", item.openedAt)
                )
            }
        }
        historyPrefs.edit().putString("items", serialized.toString()).commit()
    }

    private fun persistBookmarks(items: List<BookmarkEntry>) {
        val serialized = JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject()
                        .put("documentTitle", item.documentTitle)
                        .put("sourceLabel", item.sourceLabel)
                        .put("blockIndex", item.blockIndex)
                        .put("charOffset", item.charOffset)
                        .put("label", item.label)
                        .put("sectionHeading", item.sectionHeading)
                        .put("snippet", item.snippet)
                        .put("createdAt", item.createdAt)
                )
            }
        }
        bookmarkPrefs.edit().putString("items", serialized.toString()).commit()
    }

    private fun persistReadingList(items: List<ReadingListEntry>) {
        val serialized = JSONArray().apply {
            items.forEach { item ->
                put(
                    JSONObject()
                        .put("title", item.title)
                        .put("summary", item.summary)
                        .put("kind", item.kind.name)
                        .put("sourceLabel", item.sourceLabel)
                        .put("addedAt", item.addedAt)
                        .put("isDone", item.isDone)
                )
            }
        }
        readingListPrefs.edit().putString("items", serialized.toString()).commit()
    }

    private fun loadCachedDocument(sourceLabel: String): ReaderDocument? {
        val cacheFile = cacheFileFor(sourceLabel)
        if (!cacheFile.exists()) {
            return null
        }

        return runCatching {
            val json = JSONObject(cacheFile.readText())
            if (json.optInt("schemaVersion", 0) != DOCUMENT_CACHE_SCHEMA_VERSION) {
                cacheFile.delete()
                return@runCatching null
            }
            ReaderDocument(
                title = json.getString("title"),
                sourceLabel = json.getString("sourceLabel"),
                kind = json.optString("kind")
                    .takeIf { it.isNotBlank() }
                    ?.let { DocumentKind.valueOf(it) }
                    ?: inferDocumentKind(sourceLabel),
                pageCount = json.getInt("pageCount"),
                paragraphCount = json.getInt("paragraphCount"),
                headingCount = json.getInt("headingCount"),
                metadataBlocks = jsonToBlocks(json.optJSONArray("metadataBlocks")),
                footnoteBlocks = jsonToBlocks(json.optJSONArray("footnoteBlocks")),
                blocks = jsonToBlocks(json.optJSONArray("blocks")),
                presentation = jsonToPresentation(json.optJSONObject("presentation")),
                cleanupDiagnostics = jsonToCleanupDiagnostics(json.optJSONObject("cleanupDiagnostics")),
                summary = jsonToSummary(json.optJSONObject("summary"))
            )
        }.getOrNull()
    }

    private fun deleteCachedDocument(sourceLabel: String) {
        cacheFileFor(sourceLabel).delete()
    }

    private fun restoreLastOpenedDocument(history: List<HistoryEntry>): ReaderDocument? {
        val preferred = lastOpenedSourceLabel()
            ?.let(::loadCachedDocument)
        if (preferred != null) {
            return preferred
        }

        return history.firstNotNullOfOrNull { item ->
            loadCachedDocument(item.sourceLabel)
        }
    }

    private fun lastOpenedSourceLabel(): String? =
        historyPrefs.getString("last_opened_source_label", null)

    private fun setLastOpenedSourceLabel(sourceLabel: String?) {
        historyPrefs.edit().putString("last_opened_source_label", sourceLabel).commit()
    }

    private fun clearReadingProgress(sourceLabel: String) {
        val editor = progressPrefs.edit()
        progressKeysFor(sourceLabel).forEach(editor::remove)
        editor.commit()
    }

    private fun progressKeysFor(sourceLabel: String): List<String> {
        val aliases = linkedSetOf<String>()
        aliases += progressKeyForIdentity(progressIdentityFor(sourceLabel))
        aliases += progressKeyForIdentity(sourceLabel)
        return aliases.toList()
    }

    private fun progressIdentityFor(sourceLabel: String): String {
        return if (sourceLabel.startsWith("http://") || sourceLabel.startsWith("https://")) {
            canonicalHistorySource(sourceLabel)
        } else {
            sourceLabel
        }
    }

    private fun progressKeyForIdentity(identity: String) = "progress_${stableCacheKey(identity)}"

    private fun cacheFileFor(sourceLabel: String) =
        cacheDir.resolve("${stableCacheKey(sourceLabel)}.json")

    private fun pruneOrphanedCacheFiles(): CacheClearResult {
        val protectedCacheKeys = buildSet {
            loadHistory().forEach { add(stableCacheKey(it.sourceLabel)) }
            loadBookmarks().forEach { add(stableCacheKey(it.sourceLabel)) }
            loadReadingList().forEach { add(stableCacheKey(it.sourceLabel)) }
            lastOpenedSourceLabel()?.let { add(stableCacheKey(it)) }
        }

        var removedFileCount = 0
        var freedBytes = 0L
        cacheDir.listFiles().orEmpty()
            .filter { it.isFile }
            .forEach { file ->
                val cacheKey = file.nameWithoutExtension
                if (cacheKey !in protectedCacheKeys) {
                    freedBytes += file.length().coerceAtLeast(0L)
                    if (file.delete()) {
                        removedFileCount += 1
                    }
                }
            }

        return CacheClearResult(
            removedFileCount = removedFileCount,
            freedBytes = freedBytes
        )
    }

    private fun evictCacheIfNeeded(): CacheClearResult {
        val protectedCacheKeys = buildSet {
            loadHistory().forEach { add(stableCacheKey(it.sourceLabel)) }
            loadBookmarks().forEach { add(stableCacheKey(it.sourceLabel)) }
            loadReadingList().forEach { add(stableCacheKey(it.sourceLabel)) }
            lastOpenedSourceLabel()?.let { add(stableCacheKey(it)) }
        }

        val cacheFiles = cacheDir.listFiles().orEmpty().filter { it.isFile }.toMutableList()
        var totalBytes = cacheFiles.sumOf { it.length().coerceAtLeast(0L) }
        var removedFileCount = 0
        var freedBytes = 0L

        val evictionCandidates = cacheFiles
            .filterNot { it.nameWithoutExtension in protectedCacheKeys }
            .sortedWith(compareBy<java.io.File> { it.lastModified() }.thenBy { it.name })
            .toMutableList()

        while (
            (cacheFiles.size - removedFileCount > MAX_CACHE_FILE_COUNT || totalBytes > MAX_CACHE_BYTES) &&
            evictionCandidates.isNotEmpty()
        ) {
            val file = evictionCandidates.removeAt(0)
            val fileBytes = file.length().coerceAtLeast(0L)
            if (file.delete()) {
                removedFileCount += 1
                freedBytes += fileBytes
                totalBytes -= fileBytes
            }
        }

        return CacheClearResult(
            removedFileCount = removedFileCount,
            freedBytes = freedBytes
        )
    }

    private fun blocksToJson(blocks: List<ReaderBlock>): JSONArray {
        return JSONArray().apply {
            blocks.forEach { block ->
                put(
                    JSONObject()
                        .put("type", block.type.name)
                        .put("text", block.text)
                )
            }
        }
    }

    private fun presentationToJson(presentation: ReaderPresentation?): JSONObject? {
        return presentation?.let {
            JSONObject()
                .put("modelId", it.modelId)
                .put("promptVersion", it.promptVersion)
                .put("executionBackendLabel", it.executionBackendLabel)
                .put("blocks", blocksToJson(it.blocks))
        }
    }

    private fun cleanupDiagnosticsToJson(diagnostics: CleanupRunDiagnostics?): JSONObject? {
        return diagnostics?.let {
            JSONObject()
                .put("modelId", it.modelId)
                .put("promptVersion", it.promptVersion)
                .put("executionBackendLabel", it.executionBackendLabel)
                .put("totalParagraphs", it.totalParagraphs)
                .put("eligibleParagraphs", it.eligibleParagraphs)
                .put("totalChunks", it.totalChunks)
                .put("attemptedChunks", it.attemptedChunks)
                .put("acceptedChunks", it.acceptedChunks)
                .put("rejectedChunks", it.rejectedChunks)
                .put("changedParagraphs", it.changedParagraphs)
                .put("droppedParagraphs", it.droppedParagraphs)
                .put("eligibleCharsBefore", it.eligibleCharsBefore)
                .put("eligibleCharsAfter", it.eligibleCharsAfter)
                .put("changeKindCounts", cleanupCountsToJson(it.changeKindCounts))
                .put("rejectionReasonCounts", cleanupCountsToJson(it.rejectionReasonCounts))
                .put("chunkReports", cleanupChunkReportsToJson(it.chunkReports))
        }
    }

    private fun summaryToJson(summary: ReaderSummary?): JSONObject? {
        return summary?.let {
            JSONObject()
                .put("modelId", it.modelId)
                .put("promptVersion", it.promptVersion)
                .put("executionBackendLabel", it.executionBackendLabel)
                .put("generationDurationMs", it.generationDurationMs)
                .put("sourceSignature", it.sourceSignature)
                .put("text", it.text)
        }
    }

    private fun cleanupCountsToJson(counts: List<CleanupDiagnosticsCount>): JSONArray {
        return JSONArray().apply {
            counts.forEach { count ->
                put(
                    JSONObject()
                        .put("key", count.key)
                        .put("count", count.count)
                )
            }
        }
    }

    private fun cleanupChunkReportsToJson(reports: List<CleanupChunkReport>): JSONArray {
        return JSONArray().apply {
            reports.forEach { report ->
                put(
                    JSONObject()
                        .put("firstBlockIndex", report.firstBlockIndex)
                        .put("lastBlockIndex", report.lastBlockIndex)
                        .put("status", report.status.name)
                        .put("rejectionReason", report.rejectionReason)
                        .put("failureSummary", report.failureSummary)
                        .put("targetParagraphCount", report.targetParagraphCount)
                        .put("contextBeforeChars", report.contextBeforeChars)
                        .put("contextAfterChars", report.contextAfterChars)
                        .put("originalChars", report.originalChars)
                        .put("cleanedChars", report.cleanedChars)
                        .put("changedParagraphs", report.changedParagraphs)
                        .put("droppedParagraphs", report.droppedParagraphs)
                        .put("paragraphReports", cleanupParagraphReportsToJson(report.paragraphReports))
                )
            }
        }
    }

    private fun cleanupParagraphReportsToJson(reports: List<CleanupParagraphReport>): JSONArray {
        return JSONArray().apply {
            reports.forEach { report ->
                put(
                    JSONObject()
                        .put("blockIndex", report.blockIndex)
                        .put("changeKinds", JSONArray(report.changeKinds))
                        .put("beforeText", report.beforeText)
                        .put("afterText", report.afterText)
                )
            }
        }
    }

    private fun jsonToBlocks(array: JSONArray?): List<ReaderBlock> {
        if (array == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(
                    ReaderBlock(
                        type = ReaderBlockType.valueOf(item.getString("type")),
                        text = item.getString("text")
                    )
                )
            }
        }
    }

    private fun jsonToPresentation(json: JSONObject?): ReaderPresentation? {
        if (json == null) {
            return null
        }

        val modelId = json.optString("modelId").takeIf { it.isNotBlank() } ?: return null
        val blocks = jsonToBlocks(json.optJSONArray("blocks"))
        if (blocks.isEmpty()) {
            return null
        }

        return ReaderPresentation(
            modelId = modelId,
            promptVersion = json.optInt("promptVersion", 0),
            executionBackendLabel = json.optString("executionBackendLabel").takeIf { it.isNotBlank() },
            blocks = blocks
        )
    }

    private fun jsonToCleanupDiagnostics(json: JSONObject?): CleanupRunDiagnostics? {
        if (json == null) {
            return null
        }

        val modelId = json.optString("modelId").takeIf { it.isNotBlank() } ?: return null
        return CleanupRunDiagnostics(
            modelId = modelId,
            promptVersion = json.optInt("promptVersion", 0),
            executionBackendLabel = json.optString("executionBackendLabel").takeIf { it.isNotBlank() },
            totalParagraphs = json.optInt("totalParagraphs", 0),
            eligibleParagraphs = json.optInt("eligibleParagraphs", 0),
            totalChunks = json.optInt("totalChunks", 0),
            attemptedChunks = json.optInt("attemptedChunks", 0),
            acceptedChunks = json.optInt("acceptedChunks", 0),
            rejectedChunks = json.optInt("rejectedChunks", 0),
            changedParagraphs = json.optInt("changedParagraphs", 0),
            droppedParagraphs = json.optInt("droppedParagraphs", 0),
            eligibleCharsBefore = json.optInt("eligibleCharsBefore", 0),
            eligibleCharsAfter = json.optInt("eligibleCharsAfter", 0),
            changeKindCounts = jsonToCleanupCounts(json.optJSONArray("changeKindCounts")),
            rejectionReasonCounts = jsonToCleanupCounts(json.optJSONArray("rejectionReasonCounts")),
            chunkReports = jsonToCleanupChunkReports(json.optJSONArray("chunkReports"))
        )
    }

    private fun jsonToSummary(json: JSONObject?): ReaderSummary? {
        if (json == null) {
            return null
        }

        val modelId = json.optString("modelId").takeIf { it.isNotBlank() } ?: return null
        val sourceSignature = json.optString("sourceSignature").takeIf { it.isNotBlank() } ?: return null
        val text = json.optString("text").trim()
        if (text.isBlank()) {
            return null
        }

        return ReaderSummary(
            modelId = modelId,
            promptVersion = json.optInt("promptVersion", 0),
            executionBackendLabel = json.optString("executionBackendLabel").takeIf { it.isNotBlank() },
            generationDurationMs = json.takeIf { it.has("generationDurationMs") }
                ?.optLong("generationDurationMs")
                ?.takeIf { it > 0L },
            sourceSignature = sourceSignature,
            text = text
        )
    }

    private fun jsonToCleanupCounts(array: JSONArray?): List<CleanupDiagnosticsCount> {
        if (array == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val key = item.optString("key").takeIf { it.isNotBlank() } ?: continue
                add(
                    CleanupDiagnosticsCount(
                        key = key,
                        count = item.optInt("count", 0)
                    )
                )
            }
        }
    }

    private fun jsonToCleanupChunkReports(array: JSONArray?): List<CleanupChunkReport> {
        if (array == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val status = item.optString("status")
                    .takeIf { it.isNotBlank() }
                    ?.let { CleanupChunkStatus.valueOf(it) }
                    ?: CleanupChunkStatus.AcceptedUnchanged
                add(
                    CleanupChunkReport(
                        firstBlockIndex = item.optInt("firstBlockIndex", 0),
                        lastBlockIndex = item.optInt("lastBlockIndex", 0),
                        status = status,
                        rejectionReason = item.optString("rejectionReason").takeIf { it.isNotBlank() },
                        failureSummary = item.optString("failureSummary").takeIf { it.isNotBlank() },
                        targetParagraphCount = item.optInt("targetParagraphCount", 0),
                        contextBeforeChars = item.optInt("contextBeforeChars", 0),
                        contextAfterChars = item.optInt("contextAfterChars", 0),
                        originalChars = item.optInt("originalChars", 0),
                        cleanedChars = item.takeIf { !it.isNull("cleanedChars") }?.optInt("cleanedChars"),
                        changedParagraphs = item.optInt("changedParagraphs", 0),
                        droppedParagraphs = item.optInt("droppedParagraphs", 0),
                        paragraphReports = jsonToCleanupParagraphReports(item.optJSONArray("paragraphReports"))
                    )
                )
            }
        }
    }

    private fun jsonToCleanupParagraphReports(array: JSONArray?): List<CleanupParagraphReport> {
        if (array == null) {
            return emptyList()
        }

        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val changeKinds = buildList {
                    val kindsArray = item.optJSONArray("changeKinds")
                    if (kindsArray != null) {
                        for (kindIndex in 0 until kindsArray.length()) {
                            val value = kindsArray.optString(kindIndex).takeIf { it.isNotBlank() } ?: continue
                            add(value)
                        }
                    }
                }
                add(
                    CleanupParagraphReport(
                        blockIndex = item.optInt("blockIndex", 0),
                        changeKinds = changeKinds,
                        beforeText = item.optString("beforeText").takeIf { it.isNotEmpty() || item.has("beforeText") },
                        afterText = item.optString("afterText").takeIf { it.isNotEmpty() || item.has("afterText") }
                    )
                )
            }
        }
    }

    private fun stableCacheKey(sourceLabel: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(sourceLabel.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun shouldUseCachedDocument(document: ReaderDocument): Boolean {
        val displayBlocks = document.displayBlocks
        val meaningfulBlocks = displayBlocks.count {
            it.type == ReaderBlockType.Paragraph || it.type == ReaderBlockType.Heading
        }
        val contentLength = displayBlocks
            .filter { it.type == ReaderBlockType.Paragraph || it.type == ReaderBlockType.Heading }
            .sumOf { it.text.length }
        if (ReaderAccessibilityIntents.isCapturedTextSourceLabel(document.sourceLabel)) {
            return meaningfulBlocks >= 1 && contentLength >= 80
        }

        if (document.kind == DocumentKind.PDF) {
            return meaningfulBlocks >= 1 && contentLength >= 200
        }

        val paragraphs = displayBlocks.count { it.type == ReaderBlockType.Paragraph }
        return paragraphs >= 3 && contentLength >= 800
    }

    private fun isStrongRemoteWebCacheCandidate(document: ReaderDocument): Boolean {
        if (document.kind != DocumentKind.WEB) {
            return true
        }
        if (!document.sourceLabel.startsWith("http://") && !document.sourceLabel.startsWith("https://")) {
            return true
        }

        val displayBlocks = document.displayBlocks
        val paragraphCount = displayBlocks.count { it.type == ReaderBlockType.Paragraph }
        val headingCount = displayBlocks.count { it.type == ReaderBlockType.Heading }
        val contentLength = displayBlocks
            .filter { it.type == ReaderBlockType.Paragraph || it.type == ReaderBlockType.Heading }
            .sumOf { it.text.length }

        return paragraphCount >= 6 && contentLength >= 1800 && (paragraphCount + headingCount) >= 6
    }

    private fun resolveBookmarkSectionHeading(document: ReaderDocument?, blockIndex: Int): String? {
        val blocks = document?.displayBlocks ?: return null
        if (blocks.isEmpty()) {
            return null
        }

        val safeIndex = blockIndex.coerceIn(0, blocks.lastIndex)
        for (index in safeIndex downTo 0) {
            val block = blocks[index]
            if (block.type == ReaderBlockType.Heading) {
                return block.text
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(120)
                    .takeIf { it.isNotBlank() }
            }
        }

        return document.title.takeIf { it.isNotBlank() }
    }

    private fun buildHistorySummary(document: ReaderDocument): String {
        val displayBlocks = document.displayBlocks
        val firstParagraph = displayBlocks
            .firstOrNull { it.type == ReaderBlockType.Paragraph }
            ?.text
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (firstParagraph != null) {
            return firstParagraph.take(140)
        }

        val firstHeading = displayBlocks
            .firstOrNull { it.type == ReaderBlockType.Heading && !it.text.equals(document.title, ignoreCase = true) }
            ?.text
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        if (firstHeading != null) {
            return firstHeading.take(140)
        }

        return fallbackHistorySummary(document.title, document.sourceLabel)
    }

    private fun fallbackHistorySummary(title: String, sourceLabel: String): String {
        val source = when {
            ReaderAccessibilityIntents.isCapturedTextSourceLabel(sourceLabel) -> {
                application.getString(R.string.captured_text_fallback)
            }

            sourceLabel.startsWith("http://") || sourceLabel.startsWith("https://") -> {
                runCatching {
                    val uri = Uri.parse(sourceLabel)
                    buildString {
                        append(uri.host ?: application.getString(R.string.web_paper_fallback))
                        uri.lastPathSegment
                            ?.takeIf { it.isNotBlank() && !it.equals(title, ignoreCase = true) }
                            ?.let {
                                append(" • ")
                                append(it)
                            }
                    }
                }.getOrDefault(application.getString(R.string.web_paper_fallback))
            }

            sourceLabel.startsWith("content://") -> application.getString(R.string.local_pdf_file_fallback)
            sourceLabel.startsWith("file://") -> {
                Uri.parse(sourceLabel).lastPathSegment ?: application.getString(R.string.local_pdf_file_fallback)
            }

            else -> sourceLabel.substringAfterLast('/')
                .ifBlank { application.getString(R.string.local_pdf_file_fallback) }
        }

        return source.take(140)
    }

    private fun inferDocumentKind(sourceLabel: String): DocumentKind {
        return if (
            sourceLabel.startsWith("content://") ||
            sourceLabel.startsWith("file://") ||
            sourceLabel.lowercase(Locale.US).endsWith(".pdf")
        ) {
            DocumentKind.PDF
        } else {
            DocumentKind.WEB
        }
    }

    private fun dedupeHistory(items: List<HistoryEntry>): List<HistoryEntry> {
        return items
            .sortedByDescending { it.openedAt }
            .distinctBy(::historyDedupKey)
    }

    private fun dedupeReadingList(items: List<ReadingListEntry>): List<ReadingListEntry> {
        return items
            .sortedByDescending { it.addedAt }
            .distinctBy(::readingListDedupKey)
    }

    private fun historyDedupKey(item: HistoryEntry): String {
        return when (item.kind) {
            DocumentKind.PDF -> {
                buildString {
                    append("pdf|")
                    append(normalizeHistoryToken(item.title))
                    append('|')
                    append(normalizeHistoryToken(item.summary.take(100)))
                }
            }

            DocumentKind.WEB -> {
                buildString {
                    append("web|")
                    append(canonicalHistorySource(item.sourceLabel))
                }
            }
        }
    }

    private fun readingListDedupKey(item: ReadingListEntry): String {
        return when (item.kind) {
            DocumentKind.PDF -> {
                buildString {
                    append("pdf|")
                    append(normalizeHistoryToken(item.title))
                    append('|')
                    append(normalizeHistoryToken(item.summary.take(100)))
                }
            }

            DocumentKind.WEB -> {
                buildString {
                    append("web|")
                    append(canonicalHistorySource(item.sourceLabel))
                }
            }
        }
    }

    private fun readingListDedupKey(document: ReaderDocument): String {
        return readingListDedupKey(
            ReadingListEntry(
                title = document.title,
                summary = buildHistorySummary(document),
                kind = document.kind,
                sourceLabel = document.sourceLabel,
                addedAt = 0L,
                isDone = false
            )
        )
    }

    private fun readingListDedupKey(item: HistoryEntry): String {
        return when (item.kind) {
            DocumentKind.PDF -> {
                buildString {
                    append("pdf|")
                    append(normalizeHistoryToken(item.title))
                    append('|')
                    append(normalizeHistoryToken(item.summary.take(100)))
                }
            }

            DocumentKind.WEB -> {
                buildString {
                    append("web|")
                    append(canonicalHistorySource(item.sourceLabel))
                }
            }
        }
    }

    private fun normalizeHistoryToken(text: String): String {
        return text
            .lowercase(Locale.US)
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""[^a-z0-9 ]"""), "")
            .trim()
    }

    private fun canonicalHistorySource(sourceLabel: String): String {
        return runCatching {
            val uri = Uri.parse(sourceLabel)
            if (uri.scheme.isNullOrBlank()) {
                normalizeHistoryToken(sourceLabel)
            } else {
                buildString {
                    append(uri.scheme?.lowercase(Locale.US))
                    append("://")
                    append(uri.host?.lowercase(Locale.US) ?: "")
                    val path = uri.path.orEmpty().trimEnd('/')
                    if (path.isNotBlank()) {
                        append(path.lowercase(Locale.US))
                    }
                }
            }
        }.getOrElse {
            normalizeHistoryToken(sourceLabel)
        }
    }

    private fun knownSourceLabels(): Set<String> {
        return buildSet {
            loadHistory().forEach { add(it.sourceLabel) }
            loadBookmarks().forEach { add(it.sourceLabel) }
            loadReadingList().forEach { add(it.sourceLabel) }
            lastOpenedSourceLabel()?.let(::add)
        }
    }

    private inline fun <T> withStorageLock(action: () -> T): T = synchronized(storageLock) { action() }
}
