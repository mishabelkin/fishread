package org.read.mobile

data class PdfReaderUiState(
    val urlInput: String = "",
    val isLoading: Boolean = false,
    val isGeneratingSummary: Boolean = false,
    val summaryDraftText: String? = null,
    val summaryActiveModelStorageKey: String? = null,
    val summaryConfiguredAccelerationMode: ReaderCleanupAccelerationMode? = null,
    val summaryExecutionBackendLabel: String? = null,
    val summaryProgressLabel: String? = null,
    val summaryProgressPercent: Int? = null,
    val summaryProgressEtaSeconds: Int? = null,
    val loadingMessage: String = "",
    val document: ReaderDocument? = null,
    val history: List<HistoryEntry> = emptyList(),
    val bookmarks: List<BookmarkEntry> = emptyList(),
    val readingList: List<ReadingListEntry> = emptyList(),
    val pendingBookmark: BookmarkEntry? = null,
    val errorMessage: String? = null,
    val snackbarMessage: String? = null,
    val openReaderScreenRequestId: Long = 0L
)

data class ReaderDocument(
    val title: String,
    val sourceLabel: String,
    val kind: DocumentKind,
    val pageCount: Int,
    val paragraphCount: Int,
    val headingCount: Int,
    val metadataBlocks: List<ReaderBlock>,
    val footnoteBlocks: List<ReaderBlock>,
    val blocks: List<ReaderBlock>,
    val presentation: ReaderPresentation? = null,
    val cleanupDiagnostics: CleanupRunDiagnostics? = null,
    val summary: ReaderSummary? = null
)

data class ReaderPresentation(
    val modelId: String,
    val promptVersion: Int,
    val executionBackendLabel: String? = null,
    val blocks: List<ReaderBlock>
)

data class CleanupRunDiagnostics(
    val modelId: String,
    val promptVersion: Int,
    val executionBackendLabel: String? = null,
    val totalParagraphs: Int,
    val eligibleParagraphs: Int,
    val totalChunks: Int,
    val attemptedChunks: Int,
    val acceptedChunks: Int,
    val rejectedChunks: Int,
    val changedParagraphs: Int,
    val droppedParagraphs: Int,
    val eligibleCharsBefore: Int,
    val eligibleCharsAfter: Int,
    val changeKindCounts: List<CleanupDiagnosticsCount>,
    val rejectionReasonCounts: List<CleanupDiagnosticsCount>,
    val chunkReports: List<CleanupChunkReport>
)

data class CleanupDiagnosticsCount(
    val key: String,
    val count: Int
)

data class CleanupChunkReport(
    val firstBlockIndex: Int,
    val lastBlockIndex: Int,
    val status: CleanupChunkStatus,
    val rejectionReason: String? = null,
    val failureSummary: String? = null,
    val targetParagraphCount: Int = 0,
    val contextBeforeChars: Int = 0,
    val contextAfterChars: Int = 0,
    val originalChars: Int,
    val cleanedChars: Int? = null,
    val changedParagraphs: Int = 0,
    val droppedParagraphs: Int = 0,
    val paragraphReports: List<CleanupParagraphReport> = emptyList()
)

data class CleanupParagraphReport(
    val blockIndex: Int,
    val changeKinds: List<String>,
    val beforeText: String? = null,
    val afterText: String? = null
)

enum class CleanupChunkStatus {
    AcceptedChanged,
    AcceptedUnchanged,
    Rejected
}

data class ReaderSummary(
    val modelId: String,
    val promptVersion: Int,
    val executionBackendLabel: String? = null,
    val generationDurationMs: Long? = null,
    val sourceSignature: String,
    val text: String
)

data class HistoryEntry(
    val title: String,
    val summary: String,
    val kind: DocumentKind,
    val sourceLabel: String,
    val openedAt: Long
)

data class ReadingListEntry(
    val title: String,
    val summary: String,
    val kind: DocumentKind,
    val sourceLabel: String,
    val addedAt: Long,
    val isDone: Boolean
)

data class BookmarkEntry(
    val documentTitle: String,
    val sourceLabel: String,
    val blockIndex: Int,
    val charOffset: Int,
    val label: String?,
    val sectionHeading: String?,
    val snippet: String,
    val createdAt: Long
)

data class ReadingProgress(
    val blockIndex: Int,
    val scrollOffset: Int
)

data class CacheSummary(
    val fileCount: Int,
    val totalBytes: Long
)

data class CacheClearResult(
    val removedFileCount: Int,
    val freedBytes: Long
)

data class ReaderBlock(
    val type: ReaderBlockType,
    val text: String
)

enum class ReaderBlockType {
    Heading,
    Metadata,
    Footnote,
    Paragraph
}

enum class DocumentKind {
    PDF,
    WEB
}

val ReaderDocument.displayBlocks: List<ReaderBlock>
    get() = presentation?.blocks ?: blocks

object IntentFlags {
    const val readOnly = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
}
