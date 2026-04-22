package org.read.mobile

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.text.format.Formatter
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

private const val SUMMARY_PROGRESS_ESTIMATE_MS = 120_000L
private const val SUMMARY_PREPARING_PHASE_MS = 2_500L

class PdfReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val repository = ReaderStorageRepository(application)
    private val cleanupModelRepository = LocalCleanupModelRepository(application)
    private val documentLoader = ReaderDocumentLoader(application)
    private val cleanupPipeline = ReaderDocumentCleanupPipeline(application)
    private val summaryPipeline = ReaderDocumentSummaryPipeline(application)
    private val cleanupSettingsRepository = ReaderCleanupSettingsRepository(application)

    private val _uiState = MutableStateFlow(PdfReaderUiState())
    val uiState: StateFlow<PdfReaderUiState> = _uiState

    init {
        val initialState = repository.loadInitialState()
        _uiState.update {
            it.copy(
                history = initialState.history,
                bookmarks = initialState.bookmarks,
                readingList = initialState.readingList,
                document = initialState.restoredDocument,
                urlInput = ""
            )
        }
        initialState.restoredDocument?.let(::maybeStartBackgroundCleanup)
    }

    fun setUrl(url: String) {
        _uiState.update { it.copy(urlInput = url.trim()) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun requestOpenReaderScreen() {
        _uiState.update { state ->
            state.copy(openReaderScreenRequestId = state.openReaderScreenRequestId + 1L)
        }
    }

    fun handleSharedText(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return
        }

        val sharedUrl = Regex("""https?://\S+""").find(normalized)?.value?.trim()
        if (!sharedUrl.isNullOrBlank()) {
            openUrl(sharedUrl)
        } else {
            openCapturedText(text = normalized)
        }
    }

    fun openHistoryItem(contentResolver: ContentResolver, item: HistoryEntry) {
        openSourceLabel(
            contentResolver = contentResolver,
            sourceLabel = item.sourceLabel,
            pendingBookmark = null,
            cachedMessage = app.getString(R.string.message_opened_cached_paper)
        )
    }

    fun openBookmark(contentResolver: ContentResolver, bookmark: BookmarkEntry) {
        openSourceLabel(
            contentResolver = contentResolver,
            sourceLabel = bookmark.sourceLabel,
            pendingBookmark = bookmark,
            cachedMessage = app.getString(R.string.message_opened_bookmarked_location)
        )
    }

    fun openReadingListItem(contentResolver: ContentResolver, item: ReadingListEntry) {
        openSourceLabel(
            contentResolver = contentResolver,
            sourceLabel = item.sourceLabel,
            pendingBookmark = null,
            cachedMessage = app.getString(R.string.message_opened_reading_list_item)
        )
    }

    fun deleteHistoryItem(item: HistoryEntry) {
        val result = repository.deleteHistoryItem(item)
        _uiState.update {
            it.copy(
                history = result.history,
                bookmarks = result.bookmarks,
                snackbarMessage = app.getString(R.string.message_removed_from_history)
            )
        }
    }

    fun addBookmark(
        document: ReaderDocument,
        blockIndex: Int,
        charOffset: Int = 0,
        snippet: String? = null
    ) {
        val updated = repository.addBookmark(document, blockIndex, charOffset, snippet)
        _uiState.update {
            it.copy(
                bookmarks = updated,
                snackbarMessage = app.getString(R.string.message_bookmark_saved)
            )
        }
    }

    fun deleteBookmark(bookmark: BookmarkEntry) {
        val updated = repository.deleteBookmark(bookmark)
        _uiState.update {
            it.copy(
                bookmarks = updated,
                snackbarMessage = app.getString(R.string.message_bookmark_removed)
            )
        }
    }

    fun renameBookmark(bookmark: BookmarkEntry, label: String) {
        val normalizedLabel = label.replace(Regex("\\s+"), " ").trim().take(80).takeIf { it.isNotBlank() }
        val updated = repository.renameBookmark(bookmark, label)
        _uiState.update {
            it.copy(
                bookmarks = updated,
                snackbarMessage = if (normalizedLabel != null) {
                    app.getString(R.string.message_bookmark_label_saved)
                } else {
                    app.getString(R.string.message_bookmark_label_cleared)
                }
            )
        }
    }

    fun addDocumentToReadingList(document: ReaderDocument) {
        val alreadyPresent = uiState.value.readingList.any { it.sourceLabel == document.sourceLabel }
        val readingList = repository.addDocumentToReadingList(document)
        _uiState.update {
            it.copy(
                readingList = readingList,
                snackbarMessage = if (alreadyPresent) {
                    app.getString(R.string.message_reading_list_item_updated)
                } else {
                    app.getString(R.string.message_added_to_to_read)
                }
            )
        }
    }

    fun addHistoryItemToReadingList(item: HistoryEntry) {
        val alreadyPresent = uiState.value.readingList.any { it.sourceLabel == item.sourceLabel }
        val readingList = repository.addHistoryItemToReadingList(item)
        _uiState.update {
            it.copy(
                readingList = readingList,
                snackbarMessage = if (alreadyPresent) {
                    app.getString(R.string.message_reading_list_item_updated)
                } else {
                    app.getString(R.string.message_added_to_to_read)
                }
            )
        }
    }

    fun removeReadingListItem(item: ReadingListEntry) {
        val updated = repository.removeReadingListItem(item)
        _uiState.update {
            it.copy(
                readingList = updated,
                snackbarMessage = app.getString(R.string.message_removed_from_to_read)
            )
        }
    }

    fun setReadingListDone(item: ReadingListEntry, isDone: Boolean) {
        val updated = repository.setReadingListDone(item, isDone)
        _uiState.update {
            it.copy(
                readingList = updated,
                snackbarMessage = if (isDone) {
                    app.getString(R.string.message_marked_done)
                } else {
                    app.getString(R.string.message_marked_undone)
                }
            )
        }
    }

    fun clearPendingBookmark() {
        _uiState.update { it.copy(pendingBookmark = null) }
    }

    fun generateSummaryForCurrentDocument(forceRegenerate: Boolean = false) {
        val document = uiState.value.document ?: return
        viewModelScope.launch {
            val summaryStartedAtMs = System.currentTimeMillis()
            SummaryDiagnosticsStore.markPreparing()
            val resolvedSummaryModelId = resolveInstalledCleanupModel(
                installedInfos = cleanupModelRepository.listInstalledModelInfos(),
                preferredModelId = cleanupSettingsRepository.selectedModelId()
            )?.info?.modelId
            _uiState.update {
                it.copy(
                    isGeneratingSummary = true,
                    summaryDraftText = null,
                    summaryActiveModelStorageKey = resolvedSummaryModelId,
                    summaryConfiguredAccelerationMode = ReaderCleanupAccelerationMode.Cpu,
                    summaryExecutionBackendLabel = null,
                    summaryProgressLabel = app.getString(R.string.label_preparing_summary_model),
                    summaryProgressPercent = 5,
                    summaryProgressEtaSeconds = (SUMMARY_PROGRESS_ESTIMATE_MS / 1000L).toInt()
                )
            }
            val progressJob = launchSummaryProgressTicker()
            runCatching {
                SummaryDiagnosticsStore.markGenerating()
                _uiState.update {
                    it.copy(
                        summaryProgressLabel = app.getString(R.string.label_generating_summary),
                        summaryProgressPercent = 12,
                        summaryProgressEtaSeconds = (SUMMARY_PROGRESS_ESTIMATE_MS / 1000L).toInt()
                    )
                }
                val summarizedDocument = withContext(Dispatchers.Default) {
                    summaryPipeline.summarizeDocument(
                        document = document,
                        forceRegenerate = forceRegenerate,
                        onPrepared = { backendLabel ->
                            _uiState.update { state ->
                                if (!state.isGeneratingSummary) {
                                    state
                                } else {
                                    state.copy(
                                        summaryExecutionBackendLabel = backendLabel
                                            ?: state.summaryExecutionBackendLabel
                                    )
                                }
                            }
                        }
                    ) { partial ->
                        SummaryDiagnosticsStore.updateDraft(partial)
                        _uiState.update { state ->
                            if (!state.isGeneratingSummary) {
                                state
                            } else {
                                state.copy(summaryDraftText = partial)
                            }
                        }
                    }
                }
                val finalDocument = summarizedDocument.copy(
                    summary = summarizedDocument.summary?.copy(
                        generationDurationMs = (System.currentTimeMillis() - summaryStartedAtMs)
                            .coerceAtLeast(0L)
                    )
                )
                SummaryDiagnosticsStore.markSaving()
                _uiState.update {
                    it.copy(
                        summaryProgressLabel = app.getString(R.string.label_saving_summary),
                        summaryProgressPercent = 97,
                        summaryProgressEtaSeconds = 1
                    )
                }
                withContext(Dispatchers.IO) {
                    repository.saveDocumentCache(finalDocument)
                }
                repository.rememberLastOpened(finalDocument)
                progressJob.cancel()
                SummaryDiagnosticsStore.clear()
                _uiState.update { state ->
                    state.copy(
                        isGeneratingSummary = false,
                        summaryDraftText = null,
                        summaryActiveModelStorageKey = null,
                        summaryConfiguredAccelerationMode = null,
                        summaryExecutionBackendLabel = null,
                        summaryProgressLabel = null,
                        summaryProgressPercent = null,
                        summaryProgressEtaSeconds = null,
                        document = if (state.document?.sourceLabel == document.sourceLabel) {
                            finalDocument
                        } else {
                            state.document
                        },
                        snackbarMessage = app.getString(R.string.message_summary_ready)
                    )
                }
            }.onFailure { error ->
                progressJob.cancel()
                SummaryDiagnosticsStore.clear()
                _uiState.update {
                    it.copy(
                        isGeneratingSummary = false,
                        summaryActiveModelStorageKey = null,
                        summaryConfiguredAccelerationMode = null,
                        summaryExecutionBackendLabel = null,
                        summaryProgressLabel = null,
                        summaryProgressPercent = null,
                        summaryProgressEtaSeconds = null,
                        snackbarMessage = error.message ?: app.getString(R.string.error_summary_unavailable)
                    )
                }
            }
        }
    }

    fun saveReadingProgress(sourceLabel: String, blockIndex: Int, scrollOffset: Int) {
        repository.saveReadingProgress(sourceLabel, blockIndex, scrollOffset)
    }

    fun readingProgressFor(sourceLabel: String): ReadingProgress? {
        return repository.readingProgressFor(sourceLabel)
    }

    fun cacheSummary(): CacheSummary {
        return repository.cacheSummary()
    }

    fun clearDocumentCache() {
        val result = repository.clearDocumentCache()
        _uiState.update {
            it.copy(
                snackbarMessage = if (result.removedFileCount > 0) {
                    app.getString(
                        R.string.message_cache_cleared,
                        result.removedFileCount,
                        Formatter.formatFileSize(app, result.freedBytes)
                    )
                } else {
                    app.getString(R.string.message_cache_already_empty)
                }
            )
        }
    }

    fun openUrl(url: String) {
        val normalized = url.trim()
        if (normalized.isBlank()) {
            val message = app.getString(R.string.error_paste_url_first)
            _uiState.update {
                it.copy(
                    errorMessage = message,
                    snackbarMessage = message
                )
            }
            return
        }

        val scheme = runCatching { Uri.parse(normalized).scheme?.lowercase(Locale.US) }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            val message = app.getString(R.string.error_only_http_https_urls)
            _uiState.update {
                it.copy(
                    errorMessage = message,
                    snackbarMessage = message
                )
            }
            return
        }

        requestOpenReaderScreen()
        viewModelScope.launch {
            runCatching {
                repository.loadUsableCachedDocumentForSource(normalized)?.let { cachedDocument ->
                    repository.rememberLastOpened(cachedDocument)
                    _uiState.update {
                        it.copy(
                            urlInput = normalized,
                            isLoading = false,
                            loadingMessage = "",
                            document = cachedDocument,
                            pendingBookmark = null,
                            errorMessage = null,
                            snackbarMessage = app.getString(R.string.message_opened_cached_paper),
                            history = repository.saveHistoryForDocument(cachedDocument)
                        )
                    }
                    maybeStartBackgroundCleanup(cachedDocument)
                    return@runCatching
                }

                _uiState.update {
                    it.copy(
                        urlInput = normalized,
                        isLoading = true,
                        loadingMessage = app.getString(R.string.loading_fetching_document),
                        errorMessage = null,
                        document = null
                    )
                }

                val document = withContext(Dispatchers.IO) {
                    documentLoader.loadFromUrl(normalized)
                }
                withContext(Dispatchers.IO) {
                    repository.saveDocumentCache(document)
                }
                repository.rememberLastOpened(document)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingMessage = "",
                        document = document,
                        errorMessage = null,
                        snackbarMessage = app.getString(R.string.message_document_converted_on_device),
                        history = repository.saveHistoryForDocument(document)
                    )
                }
                maybeStartBackgroundCleanup(document)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingMessage = "",
                        errorMessage = error.message ?: app.getString(R.string.error_unable_to_open_url),
                        snackbarMessage = error.message ?: app.getString(R.string.error_unable_to_open_url)
                    )
                }
            }
        }
    }

    fun openUri(contentResolver: ContentResolver, uri: Uri) {
        requestOpenReaderScreen()
        viewModelScope.launch {
            runCatching {
                if (uri.scheme.equals("content", ignoreCase = true)) {
                    runCatching {
                        contentResolver.takePersistableUriPermission(uri, IntentFlags.readOnly)
                    }
                }
                repository.loadUsableCachedDocumentForSource(uri.toString())?.let { cachedDocument ->
                    repository.rememberLastOpened(cachedDocument)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            loadingMessage = "",
                            document = cachedDocument,
                            pendingBookmark = null,
                            errorMessage = null,
                            snackbarMessage = app.getString(R.string.message_opened_cached_paper),
                            history = repository.saveHistoryForDocument(cachedDocument)
                        )
                    }
                    maybeStartBackgroundCleanup(cachedDocument)
                    return@runCatching
                }

                _uiState.update {
                    it.copy(
                        isLoading = true,
                        loadingMessage = app.getString(R.string.loading_reading_local_pdf),
                        errorMessage = null,
                        document = null
                    )
                }

                val document = withContext(Dispatchers.IO) {
                    documentLoader.loadFromUri(contentResolver, uri)
                }
                withContext(Dispatchers.IO) {
                    repository.saveDocumentCache(document)
                }
                repository.rememberLastOpened(document)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingMessage = "",
                        document = document,
                        errorMessage = null,
                        snackbarMessage = app.getString(R.string.message_local_pdf_converted_on_device),
                        history = repository.saveHistoryForDocument(document)
                    )
                }
                maybeStartBackgroundCleanup(document)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingMessage = "",
                        errorMessage = error.message ?: app.getString(R.string.error_unable_to_read_pdf),
                        snackbarMessage = error.message ?: app.getString(R.string.error_unable_to_read_pdf)
                    )
                }
            }
        }
    }

    fun openCapturedText(
        text: String,
        title: String? = null,
        sourceLabel: String? = null
    ) {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            val message = app.getString(R.string.error_no_captured_text)
            _uiState.update {
                it.copy(
                    errorMessage = message,
                    snackbarMessage = message
                )
            }
            return
        }

        requestOpenReaderScreen()
        viewModelScope.launch {
            runCatching {
                _uiState.update {
                    it.copy(
                        isLoading = true,
                        loadingMessage = app.getString(R.string.loading_opening_captured_text),
                        errorMessage = null,
                        document = null
                    )
                }

                val document = withContext(Dispatchers.IO) {
                    documentLoader.loadFromPlainText(
                        text = normalizedText,
                        title = title,
                        sourceLabel = sourceLabel
                    )
                }
                withContext(Dispatchers.IO) {
                    repository.saveDocumentCache(document)
                }
                repository.rememberLastOpened(document)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingMessage = "",
                        document = document,
                        errorMessage = null,
                        snackbarMessage = app.getString(R.string.message_captured_text_opened),
                        history = repository.saveHistoryForDocument(document)
                    )
                }
                maybeStartBackgroundCleanup(document)
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        loadingMessage = "",
                        errorMessage = error.message ?: app.getString(R.string.error_no_captured_text),
                        snackbarMessage = error.message ?: app.getString(R.string.error_no_captured_text)
                    )
                }
            }
        }
    }

    private fun openSourceLabel(
        contentResolver: ContentResolver,
        sourceLabel: String,
        pendingBookmark: BookmarkEntry?,
        cachedMessage: String
    ) {
        repository.loadUsableCachedDocumentForSource(sourceLabel)?.let { cachedDocument ->
            repository.rememberLastOpened(cachedDocument)
            requestOpenReaderScreen()
            _uiState.update {
                it.copy(
                    document = cachedDocument,
                    pendingBookmark = pendingBookmark,
                    errorMessage = null,
                    snackbarMessage = cachedMessage,
                    history = repository.saveHistoryForDocument(cachedDocument)
                )
            }
            maybeStartBackgroundCleanup(cachedDocument)
            return
        }

        _uiState.update { it.copy(pendingBookmark = pendingBookmark) }
        if (sourceLabel.startsWith("http://") || sourceLabel.startsWith("https://")) {
            openUrl(sourceLabel)
        } else if (ReaderAccessibilityIntents.isCapturedTextSourceLabel(sourceLabel)) {
            val message = app.getString(R.string.error_captured_text_not_cached)
            _uiState.update {
                it.copy(
                    pendingBookmark = null,
                    errorMessage = message,
                    snackbarMessage = message
                )
            }
        } else {
            openUri(contentResolver, Uri.parse(sourceLabel))
        }
    }

    private fun maybeStartBackgroundCleanup(document: ReaderDocument) {
        viewModelScope.launch {
            val cleanedDocument = withContext(Dispatchers.Default) {
                cleanupPipeline.cleanDocumentIfEligible(document) { partialDocument ->
                    _uiState.update { state ->
                        if (state.document?.sourceLabel == document.sourceLabel) {
                            state.copy(document = partialDocument)
                        } else {
                            state
                        }
                    }
                }
            }
            if (cleanedDocument == document) {
                return@launch
            }

            withContext(Dispatchers.IO) {
                repository.saveDocumentCache(cleanedDocument)
            }
            repository.rememberLastOpened(cleanedDocument)

            _uiState.update { state ->
                if (state.document?.sourceLabel == document.sourceLabel) {
                    state.copy(document = cleanedDocument)
                } else {
                    state
                }
            }
        }
    }

    private fun launchSummaryProgressTicker() = viewModelScope.launch {
        val startTimeMs = System.currentTimeMillis()
        while (true) {
            val elapsedMs = (System.currentTimeMillis() - startTimeMs).coerceAtLeast(0L)
            val progressPercent = estimatedSummaryProgressPercent(elapsedMs)
            val etaSeconds = ((SUMMARY_PROGRESS_ESTIMATE_MS - elapsedMs).coerceAtLeast(0L) / 1000L)
                .toInt()
                .coerceAtLeast(1)
            _uiState.update { state ->
                if (!state.isGeneratingSummary) {
                    state
                } else {
                    state.copy(
                        summaryProgressPercent = maxOf(state.summaryProgressPercent ?: 0, progressPercent),
                        summaryProgressEtaSeconds = if ((state.summaryProgressPercent ?: progressPercent) >= 97) {
                            1
                        } else {
                            etaSeconds
                        }
                    )
                }
            }
            delay(400L)
        }
    }

    private fun estimatedSummaryProgressPercent(elapsedMs: Long): Int {
        val boundedElapsedMs = elapsedMs.coerceAtLeast(0L)
        val fraction = when {
            boundedElapsedMs <= SUMMARY_PREPARING_PHASE_MS -> {
                0.05f + (boundedElapsedMs.toFloat() / SUMMARY_PREPARING_PHASE_MS.toFloat()) * 0.15f
            }

            else -> {
                val generationWindowMs = (SUMMARY_PROGRESS_ESTIMATE_MS - SUMMARY_PREPARING_PHASE_MS).coerceAtLeast(1L)
                val generationElapsedMs = (boundedElapsedMs - SUMMARY_PREPARING_PHASE_MS)
                    .coerceIn(0L, generationWindowMs)
                0.20f + (generationElapsedMs.toFloat() / generationWindowMs.toFloat()) * 0.72f
            }
        }
        return (fraction * 100f).roundToInt().coerceIn(5, 92)
    }
}
