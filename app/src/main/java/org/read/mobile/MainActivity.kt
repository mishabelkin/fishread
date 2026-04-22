@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package org.read.mobile

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.res.Configuration
import android.view.Gravity
import android.view.View
import android.net.Uri
import android.os.Build
import android.os.BatteryManager
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.format.Formatter
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SettingsVoice
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import org.read.mobile.ui.theme.NightSlate
import org.read.mobile.ui.theme.Sand
import java.util.Locale
import kotlin.math.roundToInt
import org.read.mobile.ui.theme.LocalPdfReaderTheme

class MainActivity : ComponentActivity() {
    private val viewModel: PdfReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        PDFBoxResourceLoader.init(applicationContext)
        enableEdgeToEdge()
        val darkMode =
            (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = if (darkMode) NightSlate.toArgb() else Sand.toArgb()
        // Avoid replaying the original share/view intent on configuration changes.
        if (savedInstanceState == null) {
            handleIncomingIntent(intent)
        }

        setContent {
            LocalPdfReaderTheme {
                ReaderScreen(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) {
            return
        }

        when (intent.action) {
            ReaderAccessibilityIntents.ACTION_OPEN_READER -> {
                viewModel.requestOpenReaderScreen()
            }

            ReaderAccessibilityIntents.ACTION_BACKGROUND_READER -> {
                moveTaskToBack(true)
            }

            ReaderAccessibilityIntents.ACTION_OPEN_CAPTURED_TEXT -> {
                val capturedText = intent.getStringExtra(ReaderAccessibilityIntents.EXTRA_CAPTURED_TEXT)?.trim()
                if (!capturedText.isNullOrBlank()) {
                    viewModel.openCapturedText(
                        text = capturedText,
                        title = intent.getStringExtra(ReaderAccessibilityIntents.EXTRA_CAPTURED_TITLE),
                        sourceLabel = intent.getStringExtra(ReaderAccessibilityIntents.EXTRA_CAPTURED_SOURCE)
                    )
                }
            }

            Intent.ACTION_SEND -> {
                extractSharedText(intent)?.let(viewModel::handleSharedText)
                extractStreamUri(intent)?.let { uri ->
                    viewModel.openUri(contentResolver, uri)
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                extractSharedText(intent)?.let(viewModel::handleSharedText)
                extractStreamUris(intent).firstOrNull()?.let { uri ->
                    viewModel.openUri(contentResolver, uri)
                }
            }

            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    if (uri.scheme.equals("content", ignoreCase = true) || uri.scheme.equals("file", ignoreCase = true)) {
                        viewModel.openUri(contentResolver, uri)
                    } else {
                        viewModel.openUrl(uri.toString())
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun extractStreamUri(intent: Intent): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }

    private fun extractSharedText(intent: Intent): String? {
        return sequenceOf(
            intent.getStringExtra(Intent.EXTRA_TEXT),
            intent.getStringExtra(Intent.EXTRA_SUBJECT)
        ).firstOrNull { !it.isNullOrBlank() }
    }

    @Suppress("DEPRECATION")
    private fun extractStreamUris(intent: Intent): List<Uri> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        } else {
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(viewModel: PdfReaderViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val density = LocalDensity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val ttsController = rememberPdfTtsController()
    val cleanupController = rememberReaderCleanupController()
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var currentScreen by rememberSaveable { mutableStateOf(if (uiState.document != null) "document" else "history") }
    var historyKindFilter by rememberSaveable { mutableStateOf(DocumentKind.PDF.name) }
    val showDocument = currentScreen == "document"
    val showSkim = currentScreen == "skim"
    val showHistory = currentScreen == "history"
    val showReadingList = currentScreen == "readingList"
    val showBookmarks = currentScreen == "bookmarks"
    val showOptions = currentScreen == "options"
    val showDiagnostics = currentScreen == "diagnostics"
    var menuExpanded by rememberSaveable { mutableStateOf(false) }
    var openLinkDialogOpen by rememberSaveable { mutableStateOf(false) }
    var bookmarkMenuExpanded by rememberSaveable { mutableStateOf(false) }
    var clearCacheDialogOpen by rememberSaveable { mutableStateOf(false) }
    var textSelectionMode by rememberSaveable { mutableStateOf(false) }
    var paperDetailsExpanded by rememberSaveable(uiState.document?.sourceLabel) { mutableStateOf(false) }
    var pendingCurrentJump by remember { mutableStateOf(false) }
    var pendingDocumentJumpBlockIndex by remember { mutableStateOf<Int?>(null) }
    var accessibilityServiceEnabled by remember { mutableStateOf(isReadAccessibilityServiceEnabled(context)) }
    var visibleRange by remember { mutableStateOf<IntRange?>(null) }
    var firstVisibleAbsoluteIndex by remember { mutableStateOf(0) }
    var showTopFab by remember { mutableStateOf(false) }
    var autoFollowActive by remember(uiState.document?.sourceLabel) { mutableStateOf(true) }
    var autoFollowPausedByDrag by remember(uiState.document?.sourceLabel) { mutableStateOf(false) }
    var lastAutoFollowHandledAbsoluteIndex by remember(uiState.document?.sourceLabel) { mutableStateOf(-1) }
    var activeReadingLineMetrics by remember(uiState.document?.sourceLabel) { mutableStateOf<ActiveReadingLineMetrics?>(null) }
    var lastAutoFollowHandledLineKey by remember(uiState.document?.sourceLabel) { mutableStateOf<Pair<Int, Int>?>(null) }
    var pendingScrubAbsoluteIndex by remember(uiState.document?.sourceLabel) { mutableStateOf<Int?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.openUri(context.contentResolver, uri)
        }
    }
    val cleanupModelFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val message = cleanupController.selectSharedModelFolder(context.contentResolver, uri)
                snackbarHostState.showSnackbar(message)
            }
        }
    }
    var urlInput by rememberSaveable { mutableStateOf(uiState.urlInput) }
    val cacheSummary = remember(
        clearCacheDialogOpen,
        uiState.history,
        uiState.bookmarks,
        uiState.readingList,
        uiState.document
    ) {
        if (clearCacheDialogOpen) viewModel.cacheSummary() else null
    }

    LaunchedEffect(uiState.urlInput) {
        if (uiState.urlInput != urlInput) {
            urlInput = uiState.urlInput
        }
    }

    LaunchedEffect(currentScreen, uiState.document?.sourceLabel) {
        if (currentScreen == "home") {
            currentScreen = if (uiState.document != null) "document" else "history"
        }
    }

    LaunchedEffect(showOptions) {
        if (showOptions) {
            ttsController.refreshVoices()
            cleanupController.refresh()
            accessibilityServiceEnabled = isReadAccessibilityServiceEnabled(context)
        }
    }

    DisposableEffect(lifecycleOwner, showOptions) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessibilityServiceEnabled = isReadAccessibilityServiceEnabled(context)
                if (showOptions) {
                    cleanupController.refresh()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val playbackMatchesVisibleDocument = uiState.document?.sourceLabel?.let { sourceLabel ->
        sourceLabel == ttsController.playbackDocumentId
    } == true
    val manualReaderDragActive by listState.interactionSource.collectIsDraggedAsState()
    val latestManualReaderDragActive by rememberUpdatedState(manualReaderDragActive)
    var visualPlaybackBlockIndex by remember(uiState.document?.sourceLabel) { mutableStateOf(-1) }
    var visualPlaybackSegmentRange by remember(uiState.document?.sourceLabel) { mutableStateOf<IntRange?>(null) }
    var visualPlaybackIsSpeaking by remember(uiState.document?.sourceLabel) { mutableStateOf(false) }
    val latestPlaybackVisualHolder = remember(uiState.document?.sourceLabel) {
        MutableHolder(PlaybackVisualSnapshot(blockIndex = -1, segmentRange = null, isSpeaking = false))
    }

    fun applyPlaybackVisualSnapshot(snapshot: PlaybackVisualSnapshot) {
        visualPlaybackBlockIndex = snapshot.blockIndex
        visualPlaybackSegmentRange = snapshot.segmentRange
        visualPlaybackIsSpeaking = snapshot.isSpeaking
    }

    LaunchedEffect(showDocument, uiState.document?.sourceLabel) {
        val visibleSourceLabel = uiState.document?.sourceLabel
        if (!showDocument || visibleSourceLabel == null) {
            val emptySnapshot = PlaybackVisualSnapshot(blockIndex = -1, segmentRange = null, isSpeaking = false)
            latestPlaybackVisualHolder.value = emptySnapshot
            applyPlaybackVisualSnapshot(emptySnapshot)
            return@LaunchedEffect
        }
        snapshotFlow {
            val matches = ttsController.playbackDocumentId == visibleSourceLabel
            PlaybackVisualSnapshot(
                blockIndex = if (matches) ttsController.currentBlockIndex else -1,
                segmentRange = if (matches) ttsController.currentSegmentRange else null,
                isSpeaking = matches && ttsController.isSpeaking
            )
        }
            .distinctUntilChanged()
            .collectLatest { snapshot ->
                latestPlaybackVisualHolder.value = snapshot
                if (!latestManualReaderDragActive) {
                    applyPlaybackVisualSnapshot(snapshot)
                }
            }
    }

    LaunchedEffect(showDocument, uiState.document?.sourceLabel, manualReaderDragActive) {
        if (!showDocument || manualReaderDragActive) {
            return@LaunchedEffect
        }
        applyPlaybackVisualSnapshot(latestPlaybackVisualHolder.value)
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val message = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearSnackbar()
    }

    LaunchedEffect(ttsController.statusMessage) {
        val message = ttsController.statusMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        ttsController.clearStatus()
    }

    LaunchedEffect(ttsController.manualNavigationVersion, currentScreen, playbackMatchesVisibleDocument) {
        if (!showDocument || !playbackMatchesVisibleDocument || ttsController.manualNavigationVersion == 0L) {
            return@LaunchedEffect
        }
        pendingCurrentJump = true
    }

    LaunchedEffect(uiState.document?.sourceLabel) {
        uiState.document?.let { document ->
            ttsController.loadDocument(document.sourceLabel, document.title, document.displayBlocks)
            currentScreen = "document"
            textSelectionMode = false
            autoFollowActive = true
            autoFollowPausedByDrag = false
            lastAutoFollowHandledAbsoluteIndex = -1
            lastAutoFollowHandledLineKey = null
            activeReadingLineMetrics = null
        }
    }

    LaunchedEffect(uiState.openReaderScreenRequestId) {
        if (uiState.openReaderScreenRequestId == 0L) {
            return@LaunchedEffect
        }
        currentScreen = "document"
        textSelectionMode = false
    }

    LaunchedEffect(uiState.document?.sourceLabel, visualPlaybackIsSpeaking) {
        if (uiState.document == null) {
            return@LaunchedEffect
        }
        if (visualPlaybackIsSpeaking) {
            autoFollowActive = true
            autoFollowPausedByDrag = false
            lastAutoFollowHandledAbsoluteIndex = -1
            lastAutoFollowHandledLineKey = null
        }
    }

    val currentSpeechAbsoluteIndex = uiState.document
        ?.takeIf { visualPlaybackBlockIndex >= 0 }
        ?.let { document -> documentListPrefixCount(document) + visualPlaybackBlockIndex }

    LaunchedEffect(currentSpeechAbsoluteIndex) {
        activeReadingLineMetrics = null
    }

    LaunchedEffect(showDocument, manualReaderDragActive, visualPlaybackIsSpeaking) {
        if (shouldPauseAutoFollowForDrag(showDocument, manualReaderDragActive, visualPlaybackIsSpeaking)) {
            autoFollowActive = false
            autoFollowPausedByDrag = true
            lastAutoFollowHandledAbsoluteIndex = -1
            lastAutoFollowHandledLineKey = null
        }
    }

    LaunchedEffect(showDocument, currentScreen, textSelectionMode) {
        if (!showDocument) {
            visibleRange = null
            firstVisibleAbsoluteIndex = 0
            showTopFab = false
            return@LaunchedEffect
        }

        snapshotFlow {
            val visibleItems = listState.layoutInfo.visibleItemsInfo
            Triple(
                visibleItems.takeIf { it.isNotEmpty() }?.let { it.first().index..it.last().index },
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }
            .distinctUntilChanged()
            .collect { (range, firstIndex, firstOffset) ->
                visibleRange = range
                firstVisibleAbsoluteIndex = firstIndex
                showTopFab = !textSelectionMode && (firstIndex > 2 || firstOffset > 400)
            }
    }

    LaunchedEffect(
        uiState.document?.sourceLabel,
        currentScreen,
        pendingDocumentJumpBlockIndex,
        uiState.pendingBookmark?.sourceLabel,
        uiState.pendingBookmark?.blockIndex,
        uiState.pendingBookmark?.charOffset,
        uiState.pendingBookmark?.createdAt
    ) {
        val document = uiState.document ?: return@LaunchedEffect
        if (!showDocument) {
            return@LaunchedEffect
        }

        val pendingBookmark = uiState.pendingBookmark?.takeIf { it.sourceLabel == document.sourceLabel }
        val progress = viewModel.readingProgressFor(document.sourceLabel)
        val targetBlockIndex = pendingBookmark?.blockIndex ?: pendingDocumentJumpBlockIndex ?: progress?.blockIndex ?: 0
        val targetIndex = documentListPrefixCount(document) + targetBlockIndex
        val targetOffset = if (pendingBookmark != null || pendingDocumentJumpBlockIndex != null) 0 else (progress?.scrollOffset ?: 0)
        listState.scrollToItem(targetIndex, targetOffset)
        if (pendingBookmark != null) {
            viewModel.clearPendingBookmark()
        }
        if (pendingDocumentJumpBlockIndex != null) {
            pendingDocumentJumpBlockIndex = null
        }
    }

    LaunchedEffect(uiState.document?.sourceLabel, currentScreen) {
        val document = uiState.document ?: return@LaunchedEffect
        if (!showDocument) {
            return@LaunchedEffect
        }

        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .debounce(160L)
            .collect { (absoluteIndex, scrollOffset) ->
                val relativeIndex = (absoluteIndex - documentListPrefixCount(document)).coerceAtLeast(0)
                viewModel.saveReadingProgress(document.sourceLabel, relativeIndex, scrollOffset)
            }
    }

    LaunchedEffect(pendingCurrentJump, currentScreen, currentSpeechAbsoluteIndex) {
        if (!pendingCurrentJump || !showDocument) {
            return@LaunchedEffect
        }

        val absoluteIndex = currentSpeechAbsoluteIndex ?: run {
            pendingCurrentJump = false
            return@LaunchedEffect
        }
        try {
            autoFollowActive = true
            autoFollowPausedByDrag = false
            listState.scrollToItem(absoluteIndex, 0)
            lastAutoFollowHandledAbsoluteIndex = absoluteIndex
            lastAutoFollowHandledLineKey = null
        } finally {
            pendingCurrentJump = false
        }
    }

    LaunchedEffect(
        showDocument,
        textSelectionMode,
        currentScreen,
        autoFollowActive,
        manualReaderDragActive,
        visualPlaybackIsSpeaking,
        currentSpeechAbsoluteIndex
    ) {
        if (!showDocument || textSelectionMode || currentScreen != "document") {
            return@LaunchedEffect
        }
        if (!autoFollowActive || manualReaderDragActive || !visualPlaybackIsSpeaking) {
            return@LaunchedEffect
        }

        val absoluteIndex = currentSpeechAbsoluteIndex ?: return@LaunchedEffect
        if (absoluteIndex == lastAutoFollowHandledAbsoluteIndex) {
            return@LaunchedEffect
        }
        lastAutoFollowHandledAbsoluteIndex = absoluteIndex
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        val visibleStart = visibleItems.firstOrNull()?.index ?: return@LaunchedEffect
        val visibleEnd = visibleItems.lastOrNull()?.index ?: return@LaunchedEffect
        if (absoluteIndex in visibleStart..visibleEnd) {
            return@LaunchedEffect
        }
        val visibleItemCount = (visibleEnd - visibleStart + 1).coerceAtLeast(1)
        val followLead = maxOf(1, visibleItemCount / 3)
        val targetIndex = if (absoluteIndex > visibleEnd) {
            (absoluteIndex - followLead).coerceAtLeast(0)
        } else {
            absoluteIndex
        }

        listState.animateScrollToItem(targetIndex, 0)
    }

    LaunchedEffect(
        showDocument,
        textSelectionMode,
        currentScreen,
        autoFollowActive,
        manualReaderDragActive,
        visualPlaybackIsSpeaking,
        activeReadingLineMetrics
    ) {
        if (!showDocument || textSelectionMode || currentScreen != "document") {
            return@LaunchedEffect
        }
        if (!autoFollowActive || manualReaderDragActive || !visualPlaybackIsSpeaking) {
            return@LaunchedEffect
        }

        val lineMetrics = activeReadingLineMetrics ?: return@LaunchedEffect
        val lineKey = lineMetrics.absoluteIndex to lineMetrics.lineIndex
        if (lineKey == lastAutoFollowHandledLineKey) {
            return@LaunchedEffect
        }
        lastAutoFollowHandledLineKey = lineKey

        val currentItemInfo = listState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == lineMetrics.absoluteIndex }
            ?: return@LaunchedEffect

        val viewportStart = listState.layoutInfo.viewportStartOffset.toFloat()
        val viewportEnd = listState.layoutInfo.viewportEndOffset.toFloat()
        val viewportHeight = (viewportEnd - viewportStart).coerceAtLeast(1f)
        val desiredTop = viewportStart + viewportHeight * 0.34f
        val currentLineTop = currentItemInfo.offset.toFloat() + lineMetrics.lineTopPx
        val currentLineBottom = currentItemInfo.offset.toFloat() + lineMetrics.lineBottomPx
        val bottomComfort = viewportEnd - viewportHeight * 0.18f
        val topComfort = viewportStart + viewportHeight * 0.20f
        val needsFollow = currentLineBottom > bottomComfort || currentLineTop < topComfort
        if (!needsFollow) {
            return@LaunchedEffect
        }

        listState.animateScrollBy(currentLineTop - desiredTop)
    }
    val highlightedDirection = when {
        !showDocument -> null
        currentSpeechAbsoluteIndex == null || visibleRange == null -> null
        currentSpeechAbsoluteIndex < visibleRange!!.first -> -1
        currentSpeechAbsoluteIndex > visibleRange!!.last -> 1
        else -> null
    }
    val currentFabDirection = highlightedDirection ?: when {
        currentSpeechAbsoluteIndex == null || visibleRange == null -> -1
        currentSpeechAbsoluteIndex < visibleRange!!.first -> -1
        currentSpeechAbsoluteIndex > visibleRange!!.last -> 1
        else -> -1
    }
    val shouldShowCurrentFab = showDocument &&
        currentSpeechAbsoluteIndex != null &&
        (
            highlightedDirection != null ||
                pendingCurrentJump
            )
    var showCurrentFab by remember { mutableStateOf(false) }
    LaunchedEffect(showDocument, currentSpeechAbsoluteIndex, shouldShowCurrentFab) {
        if (!showDocument || currentSpeechAbsoluteIndex == null) {
            showCurrentFab = false
            return@LaunchedEffect
        }
        if (shouldShowCurrentFab) {
            showCurrentFab = true
            return@LaunchedEffect
        }
        delay(360)
        showCurrentFab = false
    }
    val currentBookmarkTarget = uiState.document?.let { document ->
        buildBookmarkTarget(
            document = document,
            fallbackVisibleAbsoluteIndex = firstVisibleAbsoluteIndex,
            currentSpeechBlockIndex = visualPlaybackBlockIndex,
            currentSpeechRange = visualPlaybackSegmentRange
        )
    }
    val currentExistingBookmark = uiState.document?.let { document ->
        currentBookmarkTarget?.let { target ->
            findMatchingBookmark(
                bookmarks = uiState.bookmarks,
                sourceLabel = document.sourceLabel,
                target = target
            )
        }
    }
    val currentDocumentBookmarks = uiState.document?.let { document ->
        uiState.bookmarks
            .filter { it.sourceLabel == document.sourceLabel }
            .sortedWith(compareBy<BookmarkEntry> { it.blockIndex }.thenBy { it.charOffset }.thenBy { it.createdAt })
    }.orEmpty()
    val skimEntries = uiState.document?.let(::buildSkimEntries).orEmpty()
    val currentReadingListItem = uiState.document?.let { document ->
        uiState.readingList.firstOrNull { it.sourceLabel == document.sourceLabel }
    }
    val toggleCurrentBookmark = {
        val document = uiState.document
        val target = currentBookmarkTarget
        if (document != null && target != null) {
            currentExistingBookmark?.let(viewModel::deleteBookmark)
                ?: viewModel.addBookmark(
                    document = document,
                    blockIndex = target.blockIndex,
                    charOffset = target.charOffset,
                    snippet = target.snippet
                )
        }
    }
    val scrollToDocumentFraction: (Float) -> Unit = { fraction ->
        uiState.document?.let { document ->
            if (document.displayBlocks.isEmpty()) {
                return@let
            }
            val clampedFraction = fraction.coerceIn(0f, 1f)
            val targetBlockIndex = if (document.displayBlocks.size == 1) {
                0
            } else {
                (clampedFraction * document.displayBlocks.lastIndex.toFloat()).roundToInt()
                    .coerceIn(0, document.displayBlocks.lastIndex)
            }
            val absoluteIndex = documentListPrefixCount(document) + targetBlockIndex
            pendingScrubAbsoluteIndex = absoluteIndex
        }
    }

    LaunchedEffect(showDocument, uiState.document?.sourceLabel) {
        if (!showDocument) {
            pendingScrubAbsoluteIndex = null
            return@LaunchedEffect
        }
        var lastHandledAbsoluteIndex: Int? = null
        snapshotFlow { pendingScrubAbsoluteIndex }
            .collectLatest { absoluteIndex ->
                if (absoluteIndex == null || absoluteIndex == lastHandledAbsoluteIndex) {
                    return@collectLatest
                }
                lastHandledAbsoluteIndex = absoluteIndex
                listState.scrollToItem(absoluteIndex, 0)
            }
    }

    if (openLinkDialogOpen) {
        OpenLinkDialog(
            url = urlInput,
            onUrlChange = {
                urlInput = it
                viewModel.setUrl(it)
            },
            onDismiss = { openLinkDialogOpen = false },
            onOpen = {
                openLinkDialogOpen = false
                viewModel.openUrl(urlInput)
            }
        )
    }

    Scaffold(
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.985f),
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shadowElevation = 3.dp,
                tonalElevation = 1.dp
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.995f),
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clickable { menuExpanded = true },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.turingfish),
                                        contentDescription = stringResource(R.string.content_desc_open_navigation_menu),
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                if (uiState.document != null) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.label_current_document_menu)) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Rounded.Description,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            currentScreen = "document"
                                            menuExpanded = false
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_open_link)) },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Download, contentDescription = null)
                                    },
                                    onClick = {
                                        openLinkDialogOpen = true
                                        menuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_pick_pdf)) },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                                    },
                                    onClick = {
                                        filePicker.launch(arrayOf("application/pdf"))
                                        menuExpanded = false
                                    }
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_history)) },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.History, contentDescription = null)
                                    },
                                    onClick = {
                                        currentScreen = "history"
                                        menuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_bookmarks)) },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Bookmark, contentDescription = null)
                                    },
                                    onClick = {
                                        currentScreen = "bookmarks"
                                        menuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.reading_list_title)) },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Description, contentDescription = null)
                                    },
                                    onClick = {
                                        currentScreen = "readingList"
                                        menuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.options_title)) },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.SettingsVoice, contentDescription = null)
                                    },
                                    onClick = {
                                        currentScreen = "options"
                                        menuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.diagnostics_title)) },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Description, contentDescription = null)
                                    },
                                    onClick = {
                                        currentScreen = "diagnostics"
                                        menuExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_clear_cache)) },
                                    leadingIcon = {
                                        Icon(Icons.Rounded.Delete, contentDescription = null)
                                    },
                                    onClick = {
                                        clearCacheDialogOpen = true
                                        menuExpanded = false
                                    }
                                )
                            }
                            }

                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (showDocument && uiState.document != null) {
                                    CompactHeaderAction(
                                        if (textSelectionMode) stringResource(R.string.action_done)
                                        else stringResource(R.string.action_select)
                                    ) {
                                        val newSelectionMode = !textSelectionMode
                                        textSelectionMode = newSelectionMode
                                    }
                                    CompactHeaderAction(stringResource(R.string.reading_list_title)) {
                                        currentScreen = "readingList"
                                    }
                                } else if (showSkim && uiState.document != null) {
                                    CompactHeaderAction(stringResource(R.string.action_read)) { currentScreen = "document" }
                                    CompactHeaderAction(
                                        if (textSelectionMode) stringResource(R.string.action_done)
                                        else stringResource(R.string.action_select)
                                    ) {
                                        val newSelectionMode = !textSelectionMode
                                        textSelectionMode = newSelectionMode
                                    }
                                } else if (!showDocument && uiState.document != null) {
                                    CompactHeaderAction(stringResource(R.string.action_current)) { currentScreen = "document" }
                                }
                                if (!showDocument && !showHistory) {
                                    CompactHeaderAction(stringResource(R.string.action_history)) { currentScreen = "history" }
                                }
                                if (!showDocument && !showReadingList) {
                                    CompactHeaderAction(stringResource(R.string.reading_list_title)) { currentScreen = "readingList" }
                                }
                            }

                            if (showDocument && uiState.document != null) {
                                Box {
                                    Box(
                                        modifier = Modifier
                                            .size(30.dp)
                                            .combinedClickable(
                                                onClick = { bookmarkMenuExpanded = true },
                                                onLongClick = { toggleCurrentBookmark() }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Rounded.Bookmark,
                                            contentDescription = stringResource(R.string.content_desc_open_bookmarks),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = bookmarkMenuExpanded,
                                        onDismissRequest = { bookmarkMenuExpanded = false }
                                    ) {
                                        currentBookmarkTarget?.let {
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (currentExistingBookmark != null) {
                                                            stringResource(R.string.action_remove_current_bookmark)
                                                        } else {
                                                            stringResource(R.string.action_add_bookmark_here)
                                                        }
                                                    )
                                                },
                                                leadingIcon = {
                                                    Icon(
                                                        if (currentExistingBookmark != null) Icons.Rounded.Delete else Icons.Rounded.Bookmark,
                                                        contentDescription = null
                                                    )
                                                },
                                                onClick = {
                                                    toggleCurrentBookmark()
                                                    bookmarkMenuExpanded = false
                                                }
                                            )
                                            if (currentDocumentBookmarks.isNotEmpty()) {
                                                HorizontalDivider()
                                            }
                                        }

                                        if (currentDocumentBookmarks.isEmpty()) {
                                            DropdownMenuItem(
                                                text = { Text(stringResource(R.string.label_no_document_bookmarks)) },
                                                onClick = { bookmarkMenuExpanded = false }
                                            )
                                        } else {
                                            currentDocumentBookmarks.forEach { bookmark ->
                                                DropdownMenuItem(
                                                    text = {
                                                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                            Text(
                                                                bookmark.label ?: bookmark.snippet,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis
                                                            )
                                                            (bookmark.sectionHeading ?: bookmark.documentTitle)
                                                                .takeIf { it.isNotBlank() }
                                                                ?.let { heading ->
                                                                    Text(
                                                                        heading,
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                                        maxLines = 1,
                                                                        overflow = TextOverflow.Ellipsis
                                                                    )
                                                                }
                                                        }
                                                    },
                                                    leadingIcon = {
                                                        Icon(Icons.Rounded.Bookmark, contentDescription = null)
                                                    },
                                                    trailingIcon = {
                                                        IconButton(
                                                            onClick = {
                                                                viewModel.deleteBookmark(bookmark)
                                                                if (currentDocumentBookmarks.size == 1) {
                                                                    bookmarkMenuExpanded = false
                                                                }
                                                            },
                                                            modifier = Modifier.size(28.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Rounded.Delete,
                                                                contentDescription = stringResource(R.string.action_delete_bookmark),
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    },
                                                    onClick = {
                                                        bookmarkMenuExpanded = false
                                                        currentScreen = "document"
                                                        viewModel.openBookmark(context.contentResolver, bookmark)
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (showDocument && !textSelectionMode) {
                uiState.document?.let { document ->
                    Surface(
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp
                    ) {
                        ListeningControlsBarHost(
                            ttsController = ttsController,
                            document = document,
                            onSeekRequested = {
                                pendingCurrentJump = true
                            }
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (showTopFab || showCurrentFab) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    if (showCurrentFab) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                pendingCurrentJump = true
                            },
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .scale(0.92f),
                            text = { Text(stringResource(R.string.action_current)) },
                            icon = {
                                Icon(
                                    if (currentFabDirection < 0) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                                    contentDescription = stringResource(R.string.content_desc_jump_to_current)
                                )
                            }
                        )
                    }
                    if (showTopFab) {
                        ExtendedFloatingActionButton(
                            onClick = {
                                coroutineScope.launch {
                                    listState.animateScrollToItem(0)
                                }
                            },
                            modifier = Modifier
                                .heightIn(min = 40.dp)
                                .scale(0.92f),
                            text = { Text(stringResource(R.string.action_top)) },
                            icon = {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.content_desc_back_to_top))
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            if (showDocument && textSelectionMode) {
                uiState.document?.let { document ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .padding(16.dp)
                    ) {
                        SelectionModeBanner(
                            onDone = { textSelectionMode = false },
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        ReaderSelectableDocumentBody(
                            title = document.title,
                            metadataBlocks = document.metadataBlocks,
                            blocks = document.displayBlocks,
                            footnoteBlocks = document.footnoteBlocks,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (showDocument) {
                            Modifier
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        } else {
                            Modifier
                        }
                    ),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (showHistory) {
                if (uiState.history.isEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(stringResource(R.string.history_empty_title), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.history_empty_description),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    val pdfHistory = uiState.history.filter { it.kind == DocumentKind.PDF }
                    val webHistory = uiState.history.filter { it.kind == DocumentKind.WEB }
                    val bookmarksBySource = uiState.bookmarks.groupBy { it.sourceLabel }
                    val readingListBySource = uiState.readingList.associateBy { it.sourceLabel }
                    val showingPdfHistory = historyKindFilter != DocumentKind.WEB.name
                    val selectedHistoryItems = if (showingPdfHistory) pdfHistory else webHistory
                    val selectedHistoryTitle = if (showingPdfHistory) {
                        context.getString(R.string.history_pdfs)
                    } else {
                        context.getString(R.string.history_web_pages)
                    }

                    if (uiState.history.isNotEmpty()) {
                        item {
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier.padding(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    ReaderHistoryTypeSelector(
                                        selectedKind = if (showingPdfHistory) DocumentKind.PDF else DocumentKind.WEB,
                                        onSelectKind = { historyKindFilter = it.name }
                                    )
                                    if (selectedHistoryItems.isEmpty()) {
                                        Text(
                                            stringResource(R.string.history_empty_kind, selectedHistoryTitle),
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    } else {
                                        ReaderHistorySection(
                                            title = selectedHistoryTitle,
                                            items = selectedHistoryItems,
                                            onOpen = { item ->
                                                currentScreen = "document"
                                                viewModel.openHistoryItem(context.contentResolver, item)
                                            },
                                            onDelete = viewModel::deleteHistoryItem,
                                            bookmarksBySource = bookmarksBySource,
                                            readingListBySource = readingListBySource,
                                            onOpenBookmark = { bookmark ->
                                                currentScreen = "document"
                                                viewModel.openBookmark(context.contentResolver, bookmark)
                                            },
                                            onDeleteBookmark = viewModel::deleteBookmark,
                                            onToggleReadingList = { item ->
                                                val existing = readingListBySource[item.sourceLabel]
                                                if (existing != null) {
                                                    viewModel.removeReadingListItem(existing)
                                                } else {
                                                    viewModel.addHistoryItemToReadingList(item)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                    return@LazyColumn
                }

                if (showReadingList) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (uiState.readingList.isEmpty()) {
                                Text(stringResource(R.string.reading_list_empty_title), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.reading_list_empty_description),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                ReaderReadingListSection(
                                    items = uiState.readingList,
                                    currentDocumentSourceLabel = uiState.document?.sourceLabel,
                                    onOpen = { item ->
                                        currentScreen = "document"
                                        viewModel.openReadingListItem(context.contentResolver, item)
                                    },
                                    onToggleDone = { item ->
                                        viewModel.setReadingListDone(item, !item.isDone)
                                    },
                                    onRemove = viewModel::removeReadingListItem
                                )
                            }
                        }
                    }
                }

                    return@LazyColumn
                }

                if (showBookmarks) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            if (uiState.bookmarks.isEmpty()) {
                                Text(stringResource(R.string.bookmarks_empty_title), style = MaterialTheme.typography.titleMedium)
                                Text(
                                    stringResource(R.string.bookmarks_empty_description),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                ReaderBookmarksSection(
                                    bookmarks = uiState.bookmarks,
                                    currentDocumentSourceLabel = uiState.document?.sourceLabel,
                                    onOpenBookmark = { bookmark ->
                                        currentScreen = "document"
                                        viewModel.openBookmark(context.contentResolver, bookmark)
                                    },
                                    onDeleteBookmark = viewModel::deleteBookmark,
                                    onRenameBookmark = viewModel::renameBookmark
                                )
                            }
                        }
                    }
                }

                    return@LazyColumn
                }

                if (showOptions) {
                item {
                    ReaderOptionsSection(
                        ttsController = ttsController,
                        cleanupController = cleanupController,
                        accessibilityServiceEnabled = accessibilityServiceEnabled,
                        onOpenAccessibilitySettings = { openAccessibilitySettings(context) },
                        onOpenSpeechSettings = { openSpeechSettings(context) },
                        onChooseCleanupModelFolder = { cleanupModelFolderPicker.launch(null) },
                        onRefreshCleanupModelFolder = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(cleanupController.refreshModelFolder())
                            }
                        },
                        onClearCleanupModelCache = {
                            coroutineScope.launch {
                                snackbarHostState.showSnackbar(cleanupController.clearModelExecutionCache())
                            }
                        }
                    )
                }

                    return@LazyColumn
                }

                if (showDiagnostics) {
                item {
                    ReaderDiagnosticsSection(
                        ttsController = ttsController,
                        accessibilityServiceEnabled = accessibilityServiceEnabled
                    )
                }

                    return@LazyColumn
                }

                if (showSkim) {
                uiState.document?.let { document ->
                val bookmarksByBlock = uiState.bookmarks
                    .filter { it.sourceLabel == document.sourceLabel }
                    .groupBy { it.blockIndex }

                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(stringResource(R.string.label_skimming_title), style = MaterialTheme.typography.headlineSmall)
                            Text(
                                stringResource(R.string.skim_description),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (textSelectionMode) {
                    item {
                        SelectionModeBanner(
                            onDone = { textSelectionMode = false }
                        )
                    }
                }

                val documentCleanupStatus = documentCleanupStatusLabel(context, document)
                val documentSummaryStatus = documentSummaryStatusLabel(
                    context = context,
                    document = document,
                    activeModelStorageKey = uiState.summaryActiveModelStorageKey,
                    activeConfiguredAccelerationMode = uiState.summaryConfiguredAccelerationMode,
                    activeBackendLabel = uiState.summaryExecutionBackendLabel,
                    preferActiveState = uiState.isGeneratingSummary
                )

                item {
                    PaperDetailsCard(
                        title = document.title,
                        webLinkUrl = documentWebLinkUrl(document.sourceLabel),
                        metadataBlocks = document.metadataBlocks,
                        cleanupStatus = documentCleanupStatus,
                        summaryModelInfo = documentSummaryStatus,
                        summaryText = document.summary?.text,
                        summaryDurationMs = document.summary?.generationDurationMs,
                        summaryDraftText = uiState.summaryDraftText,
                        isGeneratingSummary = uiState.isGeneratingSummary,
                        summaryProgressLabel = uiState.summaryProgressLabel,
                        summaryProgressPercent = uiState.summaryProgressPercent,
                        summaryProgressEtaSeconds = uiState.summaryProgressEtaSeconds,
                        expanded = paperDetailsExpanded,
                        onToggleExpanded = { paperDetailsExpanded = !paperDetailsExpanded },
                        selectable = textSelectionMode,
                        saved = currentReadingListItem != null,
                        onGenerateSummary = viewModel::generateSummaryForCurrentDocument,
                        onRefreshSummary = { viewModel.generateSummaryForCurrentDocument(forceRegenerate = true) },
                        onToggleSaved = {
                            currentReadingListItem?.let(viewModel::removeReadingListItem)
                                ?: viewModel.addDocumentToReadingList(document)
                        }
                    )
                }

                itemsIndexed(skimEntries) { _, skimEntry ->
                    val block = skimEntry.block
                    val blockBookmarks = bookmarksByBlock[skimEntry.blockIndex].orEmpty()
                    val bookmarkRanges = remember(block.text, blockBookmarks) {
                        bookmarkRangesForBlock(block.text, blockBookmarks)
                    }
                    val citationRanges = remember(block.text) {
                        citationRangesForBlock(block.text)
                    }
                    val isCurrentSpeechBlock =
                        skimEntry.blockIndex == visualPlaybackBlockIndex && visualPlaybackSegmentRange != null

                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = skimEntry.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            ReaderBlockText(
                                text = block.text,
                                active = isCurrentSpeechBlock,
                                activeRange = if (isCurrentSpeechBlock) visualPlaybackSegmentRange else null,
                                textStyle = if (block.type == ReaderBlockType.Heading) {
                                    MaterialTheme.typography.headlineMedium
                                } else {
                                    MaterialTheme.typography.bodyLarge
                                },
                                bookmarkEntries = blockBookmarks,
                                bookmarkRanges = bookmarkRanges,
                                citationRanges = citationRanges,
                                onBookmarkIndicatorClick = { bookmark ->
                                    pendingDocumentJumpBlockIndex = skimEntry.openBlockIndex
                                    currentScreen = "document"
                                    viewModel.openBookmark(context.contentResolver, bookmark)
                                },
                                onBookmarkIndicatorLongClick = viewModel::deleteBookmark,
                                onClickOffset = {
                                    if (!textSelectionMode) {
                                        pendingDocumentJumpBlockIndex = skimEntry.openBlockIndex
                                        currentScreen = "document"
                                    }
                                },
                                onLongClickOffset = if (textSelectionMode) {
                                    null
                                } else {
                                    { offset ->
                                        buildBookmarkTargetForOffset(document, skimEntry.blockIndex, offset)?.let { target ->
                                            findMatchingBookmark(
                                                bookmarks = uiState.bookmarks,
                                                sourceLabel = document.sourceLabel,
                                                target = target
                                            )?.let(viewModel::deleteBookmark)
                                                ?: viewModel.addBookmark(
                                                    document = document,
                                                    blockIndex = target.blockIndex,
                                                    charOffset = target.charOffset,
                                                    snippet = target.snippet
                                                )
                                        }
                                    }
                                },
                                selectable = textSelectionMode
                            )
                        }
                    }
                }

                return@LazyColumn
                }
                }

                if (uiState.isLoading) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                stringResource(R.string.loading_document_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
                }

                uiState.errorMessage?.let { error ->
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(stringResource(R.string.error_open_document_title), style = MaterialTheme.typography.titleMedium)
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

                if (showDocument) {
                uiState.document?.let { document ->
                val bookmarksByBlock = uiState.bookmarks
                    .filter { it.sourceLabel == document.sourceLabel }
                    .groupBy { it.blockIndex }
                val documentCleanupStatus = documentCleanupStatusLabel(context, document)
                val documentSummaryStatus = documentSummaryStatusLabel(
                    context = context,
                    document = document,
                    activeModelStorageKey = uiState.summaryActiveModelStorageKey,
                    activeConfiguredAccelerationMode = uiState.summaryConfiguredAccelerationMode,
                    activeBackendLabel = uiState.summaryExecutionBackendLabel,
                    preferActiveState = uiState.isGeneratingSummary
                )

                item {
                    PaperDetailsCard(
                        title = document.title,
                        webLinkUrl = documentWebLinkUrl(document.sourceLabel),
                        metadataBlocks = document.metadataBlocks,
                        cleanupStatus = documentCleanupStatus,
                        summaryModelInfo = documentSummaryStatus,
                        summaryText = document.summary?.text,
                        summaryDurationMs = document.summary?.generationDurationMs,
                        summaryDraftText = uiState.summaryDraftText,
                        isGeneratingSummary = uiState.isGeneratingSummary,
                        summaryProgressLabel = uiState.summaryProgressLabel,
                        summaryProgressPercent = uiState.summaryProgressPercent,
                        summaryProgressEtaSeconds = uiState.summaryProgressEtaSeconds,
                        expanded = paperDetailsExpanded,
                        onToggleExpanded = { paperDetailsExpanded = !paperDetailsExpanded },
                        selectable = textSelectionMode,
                        saved = currentReadingListItem != null,
                        onGenerateSummary = viewModel::generateSummaryForCurrentDocument,
                        onRefreshSummary = { viewModel.generateSummaryForCurrentDocument(forceRegenerate = true) },
                        onToggleSaved = {
                            currentReadingListItem?.let(viewModel::removeReadingListItem)
                                ?: viewModel.addDocumentToReadingList(document)
                        }
                    )
                }

                if (textSelectionMode) {
                    item {
                        ReaderSelectableDocumentBody(
                            blocks = document.displayBlocks
                        )
                    }
                } else {
                    itemsIndexed(
                        items = document.displayBlocks,
                        key = { index, block -> "${document.sourceLabel}:${block.type.name}:$index" },
                        contentType = { _, block -> block.type }
                    ) { index, block ->
                        if (block.text.isBlank()) {
                            return@itemsIndexed
                        }
                        val blockBookmarks = bookmarksByBlock[index].orEmpty()
                        val bookmarkRanges = remember(block.text, blockBookmarks) {
                            bookmarkRangesForBlock(block.text, blockBookmarks)
                        }
                        val citationRanges = remember(block.text) {
                            citationRangesForBlock(block.text)
                        }
                        val isCurrentSpeechBlock = index == visualPlaybackBlockIndex && visualPlaybackSegmentRange != null
                        val isPastSpeechBlock = visualPlaybackIsSpeaking && visualPlaybackBlockIndex >= 0 && index < visualPlaybackBlockIndex
                        val progressedTextColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.82f)
                        val currentReadRange = if (isCurrentSpeechBlock) {
                            visualPlaybackSegmentRange
                                ?.first
                                ?.takeIf { it > 0 }
                                ?.let { 0 until it }
                        } else {
                            null
                        }
                        val canStartReadingHere = block.type == ReaderBlockType.Heading || block.type == ReaderBlockType.Paragraph
                        when (block.type) {
                            ReaderBlockType.Heading -> ReaderBlockText(
                                text = block.text,
                                active = isCurrentSpeechBlock,
                                activeRange = if (isCurrentSpeechBlock) visualPlaybackSegmentRange else null,
                                textStyle = MaterialTheme.typography.headlineMedium,
                                color = if (isPastSpeechBlock) progressedTextColor else MaterialTheme.colorScheme.onSurface,
                                readRange = currentReadRange,
                                readColor = progressedTextColor,
                                bookmarkEntries = blockBookmarks,
                                bookmarkRanges = bookmarkRanges,
                                citationRanges = citationRanges,
                                onBookmarkIndicatorClick = { bookmark ->
                                    viewModel.openBookmark(context.contentResolver, bookmark)
                                },
                                onBookmarkIndicatorLongClick = viewModel::deleteBookmark,
                                onClickOffset = { offset ->
                                    ttsController.speakFromBlockOffset(document, index, offset)
                                },
                                onLongClickOffset = { offset ->
                                    buildBookmarkTargetForOffset(document, index, offset)?.let { target ->
                                        findMatchingBookmark(
                                            bookmarks = uiState.bookmarks,
                                            sourceLabel = document.sourceLabel,
                                            target = target
                                        )?.let(viewModel::deleteBookmark)
                                            ?: viewModel.addBookmark(
                                                document = document,
                                                blockIndex = target.blockIndex,
                                                charOffset = target.charOffset,
                                                snippet = target.snippet
                                            )
                                    }
                                },
                                onActiveLineMetricsChanged = if (isCurrentSpeechBlock) {
                                    { lineIndex, lineTop, lineBottom ->
                                        activeReadingLineMetrics = ActiveReadingLineMetrics(
                                            absoluteIndex = documentListPrefixCount(document) + index,
                                            lineIndex = lineIndex,
                                            lineTopPx = lineTop,
                                            lineBottomPx = lineBottom
                                        )
                                    }
                                } else {
                                    null
                                },
                                selectable = false
                            )

                            ReaderBlockType.Metadata -> ReaderBlockText(
                                text = block.text,
                                active = false,
                                activeRange = null,
                                textStyle = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                citationRanges = citationRanges,
                                onClickOffset = null,
                                selectable = false
                            )

                            ReaderBlockType.Footnote -> ReaderBlockText(
                                text = block.text,
                                active = false,
                                activeRange = null,
                                textStyle = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                citationRanges = citationRanges,
                                onClickOffset = null,
                                selectable = false
                            )

                            ReaderBlockType.Paragraph -> ReaderBlockText(
                                text = block.text,
                                active = isCurrentSpeechBlock,
                                activeRange = if (isCurrentSpeechBlock) visualPlaybackSegmentRange else null,
                                textStyle = MaterialTheme.typography.bodyLarge,
                                color = if (isPastSpeechBlock) progressedTextColor else MaterialTheme.colorScheme.onSurface,
                                readRange = currentReadRange,
                                readColor = progressedTextColor,
                                bookmarkEntries = blockBookmarks,
                                bookmarkRanges = bookmarkRanges,
                                citationRanges = citationRanges,
                                onBookmarkIndicatorClick = { bookmark ->
                                    viewModel.openBookmark(context.contentResolver, bookmark)
                                },
                                onBookmarkIndicatorLongClick = viewModel::deleteBookmark,
                                onClickOffset = if (canStartReadingHere) {
                                    { offset ->
                                        ttsController.speakFromBlockOffset(document, index, offset)
                                    }
                                } else {
                                    null
                                },
                                onLongClickOffset = if (canStartReadingHere) {
                                    { offset ->
                                        buildBookmarkTargetForOffset(document, index, offset)?.let { target ->
                                            findMatchingBookmark(
                                                bookmarks = uiState.bookmarks,
                                                sourceLabel = document.sourceLabel,
                                                target = target
                                            )?.let(viewModel::deleteBookmark)
                                                ?: viewModel.addBookmark(
                                                    document = document,
                                                    blockIndex = target.blockIndex,
                                                    charOffset = target.charOffset,
                                                    snippet = target.snippet
                                                )
                                        }
                                    }
                                } else {
                                    null
                                },
                                onActiveLineMetricsChanged = if (isCurrentSpeechBlock) {
                                    { lineIndex, lineTop, lineBottom ->
                                        activeReadingLineMetrics = ActiveReadingLineMetrics(
                                            absoluteIndex = documentListPrefixCount(document) + index,
                                            lineIndex = lineIndex,
                                            lineTopPx = lineTop,
                                            lineBottomPx = lineBottom
                                        )
                                    }
                                } else {
                                    null
                                },
                                selectable = false
                            )
                        }
                    }
                }
                if (document.footnoteBlocks.isNotEmpty()) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                            Text(stringResource(R.string.label_footnotes), style = MaterialTheme.typography.titleMedium)
                                document.footnoteBlocks.forEach { block ->
                                    if (textSelectionMode) {
                                        SelectionContainer {
                                            Text(
                                                block.text,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    } else {
                                        Text(
                                            block.text,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                }
                }
                }
            }

            if (showDocument) {
                uiState.document
                    ?.takeIf { !textSelectionMode && it.displayBlocks.isNotEmpty() }
                    ?.let { document ->
                    DocumentProgressRailHost(
                        listState = listState,
                        document = document,
                        onScrubToFraction = scrollToDocumentFraction,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 4.dp, top = 12.dp, bottom = 12.dp)
                    )
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }

    if (clearCacheDialogOpen) {
        val summaryText = cacheSummary?.let {
            context.getString(
                R.string.label_cache_summary,
                it.fileCount,
                Formatter.formatFileSize(context, it.totalBytes)
            )
        } ?: context.getString(
            R.string.label_cache_summary,
            0,
            Formatter.formatFileSize(context, 0L)
        )
        AlertDialog(
            onDismissRequest = { clearCacheDialogOpen = false },
            title = { Text(stringResource(R.string.dialog_clear_cache_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.dialog_clear_cache_message,
                        summaryText
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearDocumentCache()
                        clearCacheDialogOpen = false
                    }
                ) {
                    Text(stringResource(R.string.action_clear_cached_documents))
                }
            },
            dismissButton = {
                TextButton(onClick = { clearCacheDialogOpen = false }) {
                    Text(stringResource(R.string.action_keep_cache))
                }
            }
        )
    }
}

@Composable
private fun CompactHeaderAction(
    label: String,
    onClick: () -> Unit
) {
    Text(
        text = label,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ListeningControlsBarHost(
    ttsController: PdfTtsController,
    document: ReaderDocument,
    onSeekRequested: () -> Unit
) {
    val playbackActiveForDocument = ttsController.playbackDocumentId == document.sourceLabel
    ListeningControlsBar(
        ttsController = ttsController,
        document = document,
        playbackActiveForDocument = playbackActiveForDocument,
        playbackHasSegments = playbackActiveForDocument && ttsController.hasSegments,
        playbackIsSpeaking = playbackActiveForDocument && ttsController.isSpeaking,
        playbackProgressFraction = if (playbackActiveForDocument) ttsController.progressFraction else 0f,
        playbackProgressLabel = if (playbackActiveForDocument && ttsController.hasSegments) {
            ttsController.progressLabel
        } else {
            "0%"
        },
        onSeekRequested = onSeekRequested
    )
}

@Composable
private fun ListeningControlsBar(
    ttsController: PdfTtsController,
    document: ReaderDocument,
    playbackActiveForDocument: Boolean,
    playbackHasSegments: Boolean,
    playbackIsSpeaking: Boolean,
    playbackProgressFraction: Float,
    playbackProgressLabel: String,
    onSeekRequested: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                tonalElevation = 1.dp,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlaybackProgressScrubber(
                        progressFraction = if (playbackActiveForDocument) playbackProgressFraction else 0f,
                        enabled = playbackActiveForDocument && playbackHasSegments,
                        modifier = Modifier.weight(1f),
                        onSeekRequested = { fraction ->
                            ttsController.seekToFraction(document, fraction)
                            onSeekRequested()
                        }
                    )
                    Text(
                        if (playbackActiveForDocument && playbackHasSegments) playbackProgressLabel else "0%",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.92f),
                        maxLines = 1
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                tonalElevation = 1.dp,
                shape = RoundedCornerShape(14.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalIconButton(
                            onClick = { ttsController.skipPrevious(document) },
                            enabled = playbackActiveForDocument && playbackHasSegments,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Rounded.SkipPrevious,
                                contentDescription = stringResource(R.string.action_previous),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        FilledTonalIconButton(
                            onClick = {
                                if (playbackActiveForDocument && playbackIsSpeaking) {
                                    ttsController.stop()
                                } else {
                                    ttsController.speak(document)
                                }
                            },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                if (playbackActiveForDocument && playbackIsSpeaking) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (playbackActiveForDocument && playbackIsSpeaking) {
                                    stringResource(R.string.action_pause)
                                } else {
                                    stringResource(R.string.action_play)
                                },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        FilledTonalIconButton(
                            onClick = { ttsController.skipNext(document) },
                            enabled = playbackActiveForDocument && playbackHasSegments,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Rounded.SkipNext,
                                contentDescription = stringResource(R.string.action_next),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .padding(start = 8.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { ttsController.slower() },
                            enabled = ttsController.isReady,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Remove,
                                contentDescription = stringResource(R.string.action_slower),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                        Text(
                            ttsController.speedLabel,
                            style = MaterialTheme.typography.labelSmall
                        )
                        IconButton(
                            onClick = { ttsController.faster() },
                            enabled = ttsController.isReady,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Add,
                                contentDescription = stringResource(R.string.action_faster),
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OpenLinkDialog(
    url: String,
    onUrlChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpen: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_open_link)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text(stringResource(R.string.home_url_label)) },
                placeholder = { Text(stringResource(R.string.home_url_placeholder)) },
                leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = ImeAction.Go
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onGo = { onOpen() }
                )
            )
        },
        confirmButton = {
            TextButton(onClick = onOpen) {
                Text(stringResource(R.string.action_open_link))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun SelectionModeBanner(
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    stringResource(R.string.label_copy_text_mode),
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    stringResource(R.string.label_copy_text_mode_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.84f)
                )
            }
            TextButton(
                onClick = onDone,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
            ) {
                Text(stringResource(R.string.action_done))
            }
        }
    }
}

private data class CleanupStatusInfo(
    val title: String,
    val summary: String,
    val detailLines: List<String>,
    val report: CleanupRunDiagnostics? = null,
    val diffEntries: List<CleanupDiffEntry> = emptyList()
)

internal data class CleanupDiffEntry(
    val blockIndex: Int,
    val changeKinds: List<String>,
    val beforeText: String,
    val afterText: String
)

private data class BatterySnapshot(
    val levelPercent: Int,
    val isCharging: Boolean,
    val voltageVolts: Double?,
    val currentAmps: Double?,
    val temperatureCelsius: Float?
)

private data class DiagnosticsSample(
    val batterySnapshot: BatterySnapshot?,
    val batteryDeltaPercent: Int,
    val approxDeviceDrawWatts: Double?,
    val processCpuPercent: Float?,
    val processMemoryMb: Long,
    val threadCount: Int,
    val accessibilityEnabled: Boolean,
    val summaryStatus: String,
    val playbackStatus: String,
    val sessionMinutes: Int
)

@Composable
private fun CleanupStatusBadge(
    info: CleanupStatusInfo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(999.dp),
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(1.dp)
        ) {
            Text(
                text = info.title,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = info.summary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun CleanupStatusDetailsDialog(
    info: CleanupStatusInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(info.title) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    info.detailLines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                info.report?.let { report ->
                    HorizontalDivider()
                    CleanupDiagnosticsReportSection(
                        report = report,
                        diffEntries = info.diffEntries
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_done))
            }
        },
        dismissButton = {
            info.report?.let {
                TextButton(onClick = { shareCleanupReport(context, info) }) {
                    Text(stringResource(R.string.action_share_cleanup_report))
                }
            }
        }
    )
}

@Composable
private fun CleanupStatusControl(
    info: CleanupStatusInfo,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var detailsOpen by rememberSaveable(info.summary, info.report?.hashCode()) { mutableStateOf(false) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        horizontalAlignment = Alignment.Start
    ) {
        CleanupStatusBadge(
            info = info,
            onClick = { detailsOpen = true }
        )
        info.report?.let {
            Column(
                verticalArrangement = Arrangement.spacedBy(0.dp),
                horizontalAlignment = Alignment.Start
            ) {
                TextButton(
                    onClick = { detailsOpen = true },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.action_view_cleanup_report),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                TextButton(
                    onClick = { shareCleanupReport(context, info) },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.action_share_cleanup_report),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        } ?: Text(
            text = stringResource(R.string.cleanup_report_unavailable_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (info.report == null) {
            Text(
                text = stringResource(R.string.cleanup_report_enable_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (detailsOpen) {
        CleanupStatusDetailsDialog(
            info = info,
            onDismiss = { detailsOpen = false }
        )
    }
}

@Composable
private fun CleanupDiagnosticsReportSection(
    report: CleanupRunDiagnostics,
    diffEntries: List<CleanupDiffEntry>
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = stringResource(R.string.cleanup_report_title),
            style = MaterialTheme.typography.titleMedium
        )

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CleanupReportLine(
                stringResource(R.string.cleanup_report_label_scope),
                stringResource(
                    R.string.cleanup_report_value_scope,
                    report.eligibleParagraphs,
                    report.totalParagraphs,
                    report.totalChunks
                )
            )
            CleanupReportLine(
                stringResource(R.string.cleanup_report_label_result),
                stringResource(
                    R.string.cleanup_report_value_result,
                    report.acceptedChunks,
                    report.rejectedChunks,
                    report.changedParagraphs,
                    report.droppedParagraphs
                )
            )
            CleanupReportLine(
                stringResource(R.string.cleanup_report_label_text_delta),
                stringResource(
                    R.string.cleanup_report_value_text_delta,
                    report.eligibleCharsBefore,
                    report.eligibleCharsAfter
                )
            )
            CleanupReportMultilineValue(
                label = stringResource(R.string.cleanup_report_label_llm_scope),
                value = buildCleanupLlmScopeSummary(report)
            )
        }

        if (report.changeKindCounts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.cleanup_report_change_kinds_title),
                    style = MaterialTheme.typography.labelLarge
                )
                report.changeKindCounts.forEach { count ->
                    CleanupReportLine(formatCleanupDiagnosticKey(count.key), count.count.toString())
                }
            }
        }

        if (report.rejectionReasonCounts.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.cleanup_report_rejections_title),
                    style = MaterialTheme.typography.labelLarge
                )
                report.rejectionReasonCounts.forEach { count ->
                    CleanupReportLine(formatCleanupDiagnosticKey(count.key), count.count.toString())
                }
            }
        }

        if (report.chunkReports.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.cleanup_report_chunks_title),
                    style = MaterialTheme.typography.labelLarge
                )
                report.chunkReports.forEachIndexed { index, chunk ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.cleanup_report_chunk_title,
                                    index + 1,
                                    chunk.firstBlockIndex + 1,
                                    chunk.lastBlockIndex + 1
                                ),
                                style = MaterialTheme.typography.labelLarge
                            )
                            CleanupReportMultilineValue(
                                label = stringResource(R.string.cleanup_report_label_llm_input),
                                value = buildCleanupChunkLlmInputSummary(chunk)
                            )
                            CleanupReportLine(
                                stringResource(R.string.cleanup_report_label_status),
                                formatCleanupChunkStatus(chunk.status)
                            )
                            chunk.rejectionReason?.let { reason ->
                                CleanupReportLine(
                                    stringResource(R.string.cleanup_report_label_reason),
                                    formatCleanupDiagnosticKey(reason)
                                )
                            }
                            chunk.failureSummary?.let { failureSummary ->
                                CleanupReportMultilineValue(
                                    label = stringResource(R.string.cleanup_report_label_error),
                                    value = failureSummary
                                )
                            }
                            CleanupReportLine(
                                stringResource(R.string.cleanup_report_label_chars),
                                if (chunk.cleanedChars != null) {
                                    stringResource(
                                        R.string.cleanup_report_value_chars,
                                        chunk.originalChars,
                                        chunk.cleanedChars
                                    )
                                } else {
                                    chunk.originalChars.toString()
                                }
                            )
                            if (chunk.status == CleanupChunkStatus.AcceptedChanged) {
                                CleanupReportLine(
                                    stringResource(R.string.cleanup_report_label_changes),
                                    stringResource(
                                        R.string.cleanup_report_value_changes,
                                        chunk.changedParagraphs,
                                        chunk.droppedParagraphs
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        if (diffEntries.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.cleanup_report_diffs_title),
                    style = MaterialTheme.typography.labelLarge
                )
                diffEntries.forEachIndexed { index, entry ->
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.cleanup_report_diff_title,
                                    index + 1,
                                    entry.blockIndex + 1
                                ),
                                style = MaterialTheme.typography.labelLarge
                            )
                            if (entry.changeKinds.isNotEmpty()) {
                                Text(
                                    text = entry.changeKinds.joinToString(separator = " · ", transform = ::formatCleanupDiagnosticKey),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                text = stringResource(R.string.cleanup_report_before_title),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = entry.beforeText,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = stringResource(R.string.cleanup_report_after_title),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                text = entry.afterText.ifBlank { stringResource(R.string.cleanup_report_removed_value) },
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanupReportLine(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun CleanupReportMultilineValue(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun PaperDetailsCard(
    title: String,
    webLinkUrl: String?,
    metadataBlocks: List<ReaderBlock>,
    cleanupStatus: CleanupStatusInfo?,
    summaryModelInfo: String?,
    summaryText: String?,
    summaryDurationMs: Long?,
    summaryDraftText: String?,
    isGeneratingSummary: Boolean,
    summaryProgressLabel: String?,
    summaryProgressPercent: Int?,
    summaryProgressEtaSeconds: Int?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    selectable: Boolean,
    saved: Boolean,
    onGenerateSummary: (Boolean) -> Unit,
    onRefreshSummary: () -> Unit,
    onToggleSaved: () -> Unit
) {
    val context = LocalContext.current
    var cleanupReportOpen by rememberSaveable(cleanupStatus?.summary, cleanupStatus?.report?.hashCode()) {
        mutableStateOf(false)
    }
    val toReadButtonLabel = buildAnnotatedString {
        append(
            if (saved) {
                stringResource(R.string.action_remove_from_prefix)
            } else {
                stringResource(R.string.action_save_to_prefix)
            }
        )
        append(" ")
        withStyle(
            androidx.compose.ui.text.SpanStyle(
                color = MaterialTheme.colorScheme.secondary,
                fontWeight = FontWeight.SemiBold
            )
        ) {
            append(stringResource(R.string.reading_list_title))
        }
    }
    val collapsedMetadataSummary = remember(title, metadataBlocks) {
        preferredMetadataSummaryLine(title, metadataBlocks)
    }
    val collapsedSummaryActionLabel = when {
        isGeneratingSummary -> stringResource(R.string.label_summarizing)
        summaryText.isNullOrBlank() -> stringResource(R.string.action_summarize)
        else -> stringResource(R.string.label_summary_section)
    }
    val expandedSummaryActionLabel = if (summaryText.isNullOrBlank()) {
        stringResource(R.string.action_summarize)
    } else {
        stringResource(R.string.action_refresh_summary)
    }
    val openOrGenerateSummary = {
        if (!expanded) {
            onToggleExpanded()
        }
        if (summaryText.isNullOrBlank() && !isGeneratingSummary) {
            onGenerateSummary(false)
        }
    }
    val displayedSummaryText = when {
        isGeneratingSummary && !summaryDraftText.isNullOrBlank() -> summaryDraftText
        !summaryText.isNullOrBlank() -> summaryText
        else -> null
    }
    val summaryDurationLabel = summaryDurationMs?.let(::formatSummaryGenerationDuration)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selectable) {
                    Modifier
                } else {
                    Modifier.clickable(onClick = onToggleExpanded)
                }
            )
    ) {
        val compactDetailsLayout = maxWidth < 390.dp

        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (compactDetailsLayout) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = if (expanded) 3 else 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!expanded && !collapsedMetadataSummary.isNullOrBlank()) {
                                Text(
                                    collapsedMetadataSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            cleanupStatus?.let { status ->
                                CleanupStatusControl(info = status)
                            }
                            Text(
                                if (expanded) {
                                    stringResource(R.string.label_paper_details_expanded)
                                } else {
                                    stringResource(R.string.label_paper_details_collapsed)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!expanded) {
                                TextButton(
                                    onClick = openOrGenerateSummary,
                                    enabled = !isGeneratingSummary,
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        collapsedSummaryActionLabel,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                            if (isGeneratingSummary && !expanded) {
                                SummaryProgressIndicator(
                                    label = summaryProgressLabel
                                        ?: stringResource(R.string.label_summarizing),
                                    progressPercent = summaryProgressPercent,
                                    etaSeconds = summaryProgressEtaSeconds
                                )
                                summaryDraftText?.takeIf { it.isNotBlank() }?.let { draft ->
                                    Text(
                                        text = draft,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = onToggleSaved,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = toReadButtonLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = onToggleExpanded,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = if (expanded) {
                                        stringResource(R.string.content_desc_collapse_paper_details)
                                    } else {
                                        stringResource(R.string.content_desc_expand_paper_details)
                                    },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = if (expanded) 3 else 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (!expanded && !collapsedMetadataSummary.isNullOrBlank()) {
                                Text(
                                    collapsedMetadataSummary,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            cleanupStatus?.let { status ->
                                CleanupStatusControl(info = status)
                            }
                            Text(
                                if (expanded) {
                                    stringResource(R.string.label_paper_details_expanded)
                                } else {
                                    stringResource(R.string.label_paper_details_collapsed)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (!expanded) {
                                TextButton(
                                    onClick = openOrGenerateSummary,
                                    enabled = !isGeneratingSummary,
                                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        collapsedSummaryActionLabel,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                }
                            }
                            if (isGeneratingSummary && !expanded) {
                                SummaryProgressIndicator(
                                    label = summaryProgressLabel
                                        ?: stringResource(R.string.label_summarizing),
                                    progressPercent = summaryProgressPercent,
                                    etaSeconds = summaryProgressEtaSeconds
                                )
                                summaryDraftText?.takeIf { it.isNotBlank() }?.let { draft ->
                                    Text(
                                        text = draft,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 4,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = onToggleSaved,
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                            ) {
                                Text(
                                    text = toReadButtonLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(
                                onClick = onToggleExpanded,
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = if (expanded) {
                                        stringResource(R.string.content_desc_collapse_paper_details)
                                    } else {
                                        stringResource(R.string.content_desc_expand_paper_details)
                                    },
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                if (expanded) {
                    HorizontalDivider()
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.42f),
                        tonalElevation = 1.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.label_summary_section),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                                OutlinedButton(
                                    onClick = {
                                        if (summaryText.isNullOrBlank()) {
                                            onGenerateSummary(false)
                                        } else {
                                            onRefreshSummary()
                                        }
                                    },
                                    enabled = !isGeneratingSummary,
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                                ) {
                                    Text(expandedSummaryActionLabel)
                                }
                            }
                            summaryModelInfo?.let { info ->
                                Text(
                                    text = info,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.88f)
                                )
                            }
                            cleanupStatus?.report?.let { _ ->
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TextButton(
                                        onClick = { cleanupReportOpen = true },
                                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                                    ) {
                                        Text(stringResource(R.string.action_view_cleanup_report))
                                    }
                                    TextButton(
                                        onClick = { shareCleanupReport(context, cleanupStatus) },
                                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)
                                    ) {
                                        Text(stringResource(R.string.action_share_cleanup_report))
                                    }
                                }
                            }
                            if (!isGeneratingSummary && !summaryDurationLabel.isNullOrBlank()) {
                                Text(
                                    text = stringResource(
                                        R.string.label_summary_generation_time,
                                        summaryDurationLabel
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.82f)
                                )
                            }
                            if (isGeneratingSummary) {
                                SummaryProgressIndicator(
                                    label = summaryProgressLabel
                                        ?: stringResource(R.string.label_summarizing),
                                    progressPercent = summaryProgressPercent,
                                    etaSeconds = summaryProgressEtaSeconds
                                )
                            }
                            when {
                                !displayedSummaryText.isNullOrBlank() -> {
                                    SummaryBodyText(
                                        text = displayedSummaryText,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }

                                isGeneratingSummary -> {
                                    Text(
                                        stringResource(R.string.label_summary_waiting_for_output),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }

                                else -> {
                                    Text(
                                        stringResource(R.string.label_summary_empty_state),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer
                                    )
                                }
                            }
                        }
                    }

                    webLinkUrl?.let { url ->
                        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.label_document_web_link_title),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    textDecoration = TextDecoration.Underline,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clickable { openDocumentWebLink(context, url) }
                                )
                            }
                        }
                    }

                    metadataBlocks.forEach { block ->
                        Text(
                            block.text,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    if (cleanupReportOpen && cleanupStatus != null) {
        CleanupStatusDetailsDialog(
            info = cleanupStatus,
            onDismiss = { cleanupReportOpen = false }
        )
    }
}

internal enum class SummaryDisplayLineKind {
    Heading,
    Paragraph,
    Bullet,
    Spacer
}

internal data class SummaryDisplayLine(
    val kind: SummaryDisplayLineKind,
    val text: String = ""
)

private val SUMMARY_BULLET_LINE_REGEX = Regex("""^\s*(?:[-*•]|\d+[.)])\s*(.+)$""")

@Composable
private fun SummaryBodyText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    val displayLines = remember(text) { buildSummaryDisplayLinesForUi(text) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        displayLines.forEach { line ->
            when (line.kind) {
                SummaryDisplayLineKind.Heading -> {
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = color
                    )
                }

                SummaryDisplayLineKind.Paragraph -> {
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = color
                    )
                }

                SummaryDisplayLineKind.Bullet -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "\u2022",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = color
                        )
                        Text(
                            text = line.text,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = color
                        )
                    }
                }

                SummaryDisplayLineKind.Spacer -> {
                    Spacer(modifier = Modifier.height(2.dp))
                }
            }
        }
    }
}

@Composable
private fun SummaryProgressIndicator(
    label: String,
    progressPercent: Int?,
    etaSeconds: Int?
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            strokeWidth = 2.dp
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ReaderDiagnosticsSection(
    ttsController: PdfTtsController,
    accessibilityServiceEnabled: Boolean,
) {
    val context = LocalContext.current
    val diagnosticsSample = rememberDiagnosticsSample(context)
    val loggingState = rememberDiagnosticsLoggingState(context)
    val accessibilityStatus = if (diagnosticsSample?.accessibilityEnabled == true || accessibilityServiceEnabled) {
        stringResource(R.string.diagnostics_status_ready)
    } else {
        stringResource(R.string.diagnostics_status_disabled)
    }
    val playbackStatus = diagnosticsSample?.playbackStatus ?: when {
        ttsController.isSpeaking -> stringResource(R.string.diagnostics_status_speaking)
        ttsController.hasSegments -> stringResource(R.string.diagnostics_status_paused)
        else -> stringResource(R.string.diagnostics_status_idle)
    }
    val summaryStatus = diagnosticsSample?.summaryStatus ?: stringResource(R.string.diagnostics_status_idle)
    val batteryNowLabel = diagnosticsSample?.batterySnapshot?.levelPercent?.let { "$it%" }
        ?: stringResource(R.string.diagnostics_value_unavailable)
    val powerSourceLabel = diagnosticsSample?.batterySnapshot?.isCharging?.let { isCharging ->
        if (isCharging) {
            context.getString(R.string.diagnostics_value_charging)
        } else {
            context.getString(R.string.diagnostics_value_on_battery)
        }
    } ?: stringResource(R.string.diagnostics_value_unavailable)
    val drawLabel = diagnosticsSample?.approxDeviceDrawWatts?.let {
        String.format(Locale.US, "%.2f W", it)
    } ?: stringResource(R.string.diagnostics_value_unavailable)
    val batteryDeltaLabel = diagnosticsSample?.let { sample ->
        context.getString(
            R.string.diagnostics_value_battery_delta,
            formatSignedPercent(sample.batteryDeltaPercent),
            sample.sessionMinutes
        )
    } ?: stringResource(R.string.diagnostics_value_unavailable)
    val temperatureLabel = diagnosticsSample?.batterySnapshot?.temperatureCelsius?.let {
        String.format(Locale.US, "%.1f°C", it)
    } ?: stringResource(R.string.diagnostics_value_unavailable)
    val cpuLabel = diagnosticsSample?.processCpuPercent?.let {
        String.format(Locale.US, "%.0f%% of one core", it)
    } ?: stringResource(R.string.diagnostics_value_measuring)
    val memoryLabel = diagnosticsSample?.processMemoryMb?.let { "$it MB" }
        ?: stringResource(R.string.diagnostics_value_unavailable)
    val threadCountLabel = diagnosticsSample?.threadCount?.toString()
        ?: stringResource(R.string.diagnostics_value_unavailable)
    val sessionLabel = diagnosticsSample?.sessionMinutes?.let {
        context.getString(R.string.diagnostics_value_session_minutes, it)
    } ?: stringResource(R.string.diagnostics_value_unavailable)
    val diagnosticsLogRepository = remember(context) { DiagnosticsLogRepository(context.applicationContext) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = stringResource(R.string.diagnostics_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = stringResource(R.string.diagnostics_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.diagnostics_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.diagnostics_refresh_rate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DiagnosticsSectionCard(title = stringResource(R.string.diagnostics_section_energy)) {
            DiagnosticsLine(stringResource(R.string.diagnostics_label_battery_now), batteryNowLabel)
            DiagnosticsLine(stringResource(R.string.diagnostics_label_power_source), powerSourceLabel)
            DiagnosticsLine(stringResource(R.string.diagnostics_label_device_draw), drawLabel)
            DiagnosticsLine(stringResource(R.string.diagnostics_label_battery_delta), batteryDeltaLabel)
            DiagnosticsLine(stringResource(R.string.diagnostics_label_temperature), temperatureLabel)
        }

        DiagnosticsSectionCard(title = stringResource(R.string.diagnostics_section_runtime)) {
            DiagnosticsLine(stringResource(R.string.diagnostics_label_cpu), cpuLabel)
            DiagnosticsLine(stringResource(R.string.diagnostics_label_memory), memoryLabel)
            DiagnosticsLine(stringResource(R.string.diagnostics_label_threads), threadCountLabel)
            DiagnosticsLine(stringResource(R.string.diagnostics_label_session), sessionLabel)
        }

        DiagnosticsSectionCard(title = stringResource(R.string.diagnostics_section_state)) {
            DiagnosticsLine(stringResource(R.string.diagnostics_label_accessibility_service), accessibilityStatus)
            DiagnosticsLine(stringResource(R.string.diagnostics_label_playback), playbackStatus)
            DiagnosticsLine(stringResource(R.string.diagnostics_label_summary), summaryStatus)
        }

        DiagnosticsSectionCard(title = stringResource(R.string.diagnostics_section_logging)) {
            Text(
                text = stringResource(R.string.diagnostics_logging_protocol_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { DiagnosticsLoggingService.start(context) },
                    enabled = loggingState?.isRunning != true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_start_logging))
                }
                OutlinedButton(
                    onClick = { DiagnosticsLoggingService.stop(context) },
                    enabled = loggingState?.isRunning == true,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_stop_logging))
                }
                OutlinedButton(
                    onClick = { DiagnosticsLoggingService.shareLatestLog(context) },
                    enabled = !loggingState?.latestFilePath.isNullOrBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_share_latest_log))
                }
                OutlinedButton(
                    onClick = { diagnosticsLogRepository.clearAllLogs() },
                    enabled = loggingState?.isRunning != true && !loggingState?.latestFilePath.isNullOrBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_clear_logs))
                }
            }
            DiagnosticsLine(
                stringResource(R.string.diagnostics_label_logger_status),
                if (loggingState?.isRunning == true) {
                    stringResource(R.string.diagnostics_value_logger_running)
                } else {
                    stringResource(R.string.diagnostics_value_logger_stopped)
                }
            )
            DiagnosticsLine(
                stringResource(R.string.diagnostics_label_logger_file),
                loggingState?.activeFileName ?: stringResource(R.string.diagnostics_value_none)
            )
            DiagnosticsLine(
                stringResource(R.string.diagnostics_label_logger_samples),
                loggingState?.let { stringResource(R.string.diagnostics_value_samples_count, it.sampleCount) }
                    ?: stringResource(R.string.diagnostics_value_unavailable)
            )
            DiagnosticsLine(
                stringResource(R.string.diagnostics_label_logger_last_sample),
                loggingState?.lastSampleAtMs?.let(::formatDiagnosticsTimestamp)
                    ?: stringResource(R.string.diagnostics_value_none)
            )
            DiagnosticsLine(
                stringResource(R.string.diagnostics_label_logger_folder),
                loggingState?.storageDirectoryPath ?: stringResource(R.string.diagnostics_value_unavailable)
            )
        }
    }
}

@Composable
private fun DiagnosticsSectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium
            )
            content()
        }
    }
}

@Composable
private fun DiagnosticsLine(
    label: String,
    value: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun buildSummaryDisplayLines(text: String): List<SummaryDisplayLine> {
    val normalizedText = normalizeSummaryTextForDisplay(text)
    val lines = mutableListOf<SummaryDisplayLine>()
    var insideBulletSection = false

    normalizedText.lines().forEach { rawLine ->
        val trimmed = rawLine.trim()
        when {
            trimmed.isBlank() -> {
                lines += SummaryDisplayLine(SummaryDisplayLineKind.Spacer)
            }

            trimmed.equals("Overview:", ignoreCase = true) ||
                trimmed.equals("Key points:", ignoreCase = true) -> {
                insideBulletSection = trimmed.equals("Key points:", ignoreCase = true)
                lines += SummaryDisplayLine(SummaryDisplayLineKind.Heading, trimmed)
            }

            Regex("""^\s*(?:[-*•·▪◦]|\d+[.)])\s+(.+)$""").matches(trimmed) -> {
                insideBulletSection = true
                val bulletText = Regex("""^\s*(?:[-*•·▪◦]|\d+[.)])\s+(.+)$""").matchEntire(trimmed)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.trim()
                    .orEmpty()
                if (bulletText.isNotBlank()) {
                    lines += SummaryDisplayLine(SummaryDisplayLineKind.Bullet, bulletText)
                }
            }

            insideBulletSection -> {
                lines += SummaryDisplayLine(SummaryDisplayLineKind.Bullet, trimmed)
            }

            else -> {
                lines += SummaryDisplayLine(SummaryDisplayLineKind.Paragraph, trimmed)
            }
        }
    }

    return lines
}

internal fun buildSummaryDisplayLinesForUi(text: String): List<SummaryDisplayLine> {
    val normalizedText = normalizeSummaryTextForDisplay(text)
    val lines = mutableListOf<SummaryDisplayLine>()
    val bulletRegex = Regex("""^\s*(?:[-*\u2022\u00B7\u25AA\u25E6]|\d+[.)])\s*(.+)$""")
    val inlineBulletRegex = Regex("""(?:^|(?<=[\s.!?]))(?:[-*\u2022\u00B7\u25AA\u25E6]|\d+[.)])\s*""")
    var insideBulletSection = false

    fun lastBulletLine(): SummaryDisplayLine? =
        lines.lastOrNull { it.kind == SummaryDisplayLineKind.Bullet }

    fun appendToPreviousBullet(extraText: String): Boolean {
        val lastIndex = lines.indexOfLast { it.kind == SummaryDisplayLineKind.Bullet }
        if (lastIndex < 0) {
            return false
        }
        val previous = lines[lastIndex]
        lines[lastIndex] = previous.copy(
            text = listOf(previous.text, extraText)
                .filter { it.isNotBlank() }
                .joinToString(separator = " ")
        )
        return true
    }

    fun extractInlineBulletItems(textLine: String): List<String> {
        val matches = inlineBulletRegex.findAll(textLine).toList()
        if (matches.isEmpty()) {
            return emptyList()
        }
        return matches.mapIndexedNotNull { index, match ->
            val start = match.range.last + 1
            val end = if (index + 1 < matches.size) {
                matches[index + 1].range.first
            } else {
                textLine.length
            }
            textLine.substring(start, end)
                .trim()
                .takeIf { it.isNotBlank() }
        }
    }

    fun shouldStartImplicitBullet(textLine: String): Boolean {
        val previousBullet = lastBulletLine() ?: return false
        val firstChar = textLine.firstOrNull() ?: return false
        val startsLikeStandaloneBullet = firstChar.isUpperCase() ||
            firstChar.isDigit() ||
            firstChar == '"' ||
            firstChar == '\'' ||
            firstChar == '('
        if (!startsLikeStandaloneBullet) {
            return false
        }
        val previousLooksComplete = previousBullet.text.lastOrNull() in setOf('.', '!', '?', ')', '"')
        return previousLooksComplete
    }

    normalizedText.lines().forEach { rawLine ->
        val trimmed = rawLine.trim()
        when {
            trimmed.isBlank() -> {
                insideBulletSection = false
                lines += SummaryDisplayLine(SummaryDisplayLineKind.Spacer)
            }

            trimmed.equals("Overview:", ignoreCase = true) ||
                trimmed.equals("Key points:", ignoreCase = true) -> {
                insideBulletSection = trimmed.equals("Key points:", ignoreCase = true)
                lines += SummaryDisplayLine(SummaryDisplayLineKind.Heading, trimmed)
            }

            bulletRegex.matches(trimmed) || extractInlineBulletItems(trimmed).isNotEmpty() -> {
                insideBulletSection = true
                val bulletItems = extractInlineBulletItems(trimmed).ifEmpty {
                    listOfNotNull(
                        bulletRegex.matchEntire(trimmed)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                    )
                }
                bulletItems.forEach { bulletText ->
                    lines += SummaryDisplayLine(SummaryDisplayLineKind.Bullet, bulletText)
                }
            }

            insideBulletSection -> {
                if (shouldStartImplicitBullet(trimmed)) {
                    lines += SummaryDisplayLine(SummaryDisplayLineKind.Bullet, trimmed)
                } else if (!appendToPreviousBullet(trimmed)) {
                    lines += SummaryDisplayLine(SummaryDisplayLineKind.Bullet, trimmed)
                }
            }

            else -> {
                lines += SummaryDisplayLine(SummaryDisplayLineKind.Paragraph, trimmed)
            }
        }
    }

    return lines
}

private fun normalizeSummaryTextForDisplay(text: String): String {
    var normalized = text
        .replace(Regex("""```(?:[\w-]+)?"""), "")
        .replace(Regex("""\r\n?"""), "\n")
        .replace(Regex("""(?<=\S)\s+(?=(?:[-*\u00B7\u2022\u25AA\u25E6]|\d+[.)])\s+)"""), "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()
        .replace("\u00E2\u20AC\u00A2", "\u2022")
        .replace("\u00C2\u00B7", "\u2022")
        .replace("\u00E2\u2013\u00AA", "\u2022")
        .replace("\u00E2\u2014\u00A6", "\u2022")
        .replace(Regex("""(?i)\bOverview:\s*"""), "Overview:\n")
        .replace(Regex("""(?i)\bKey points:\s*"""), "\n\nKey points:\n")
        .replace(Regex("""(?m)^\s*[·▪◦]\s*"""), "• ")
        .replace(Regex("""(?m)^\s*[-*]\s+"""), "• ")

    normalized = normalized
        .replace(Regex("""(?m)^\s*[\u00B7\u2022\u25AA\u25E6]\s*"""), "\u2022 ")
        .replace(Regex("""(?m)^\s*[-*]\s+"""), "\u2022 ")

    normalized = Regex("""(?i)\b(overview|key points):\s*""").replace(normalized) { match ->
        val heading = match.groupValues.getOrNull(1).orEmpty()
        if (heading.equals("overview", ignoreCase = true)) {
            "Overview:\n"
        } else {
            "\n\nKey points:\n"
        }
    }

    normalized = normalized
        .replace(
            Regex("""(?<=[.!?])\s*(?=(?:[-*\u00B7\u2022\u25AA\u25E6]|\d+[.)])\s*)"""),
            "\n"
        )
        .replace(
            Regex("""(?m)^(\s*(?:[-*\u00B7\u2022\u25AA\u25E6]|\d+[.)]))(?=\S)"""),
            "$1 "
        )

    normalized = Regex("""(?<=\S)\s+(?=(?:[-*•·▪◦]|\d+[.)])\s+)""").replace(normalized, "\n")
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    return normalized
}

private fun formatSummaryGenerationDuration(durationMs: Long): String {
    val totalSeconds = (durationMs / 1000L).coerceAtLeast(1L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return when {
        minutes <= 0L -> "${totalSeconds}s"
        seconds == 0L -> "${minutes}m"
        else -> "${minutes}m ${seconds}s"
    }
}

internal fun buildCleanupDiffEntries(document: ReaderDocument): List<CleanupDiffEntry> {
    val report = document.cleanupDiagnostics ?: return emptyList()
    val presentation = document.presentation ?: return emptyList()
    val cleanedBlocks = presentation.blocks
    val reportedEntries = report.chunkReports
        .flatMap { it.paragraphReports }
        .distinctBy { it.blockIndex }
        .mapNotNull { paragraphReport ->
            val beforeText = paragraphReport.beforeText
            val afterText = paragraphReport.afterText
            if (beforeText == null || afterText == null) {
                return@mapNotNull null
            }
            CleanupDiffEntry(
                blockIndex = paragraphReport.blockIndex.coerceAtLeast(0),
                changeKinds = paragraphReport.changeKinds,
                beforeText = beforeText,
                afterText = afterText
            )
        }
    if (reportedEntries.isNotEmpty()) {
        return reportedEntries
    }

    if (presentation.modelId != report.modelId || presentation.promptVersion != report.promptVersion) {
        return emptyList()
    }

    val changeKindsByIndex = report.chunkReports
        .flatMap { it.paragraphReports }
        .associate { it.blockIndex to it.changeKinds }

    return document.blocks.indices.mapNotNull { blockIndex ->
        val beforeBlock = document.blocks.getOrNull(blockIndex) ?: return@mapNotNull null
        val afterBlock = cleanedBlocks.getOrNull(blockIndex) ?: return@mapNotNull null
        if (beforeBlock.type != ReaderBlockType.Paragraph || afterBlock.type != ReaderBlockType.Paragraph) {
            return@mapNotNull null
        }
        if (beforeBlock.text == afterBlock.text) {
            return@mapNotNull null
        }
        CleanupDiffEntry(
            blockIndex = blockIndex,
            changeKinds = changeKindsByIndex[blockIndex].orEmpty(),
            beforeText = beforeBlock.text,
            afterText = afterBlock.text
        )
    }
}

private fun formatCleanupDiagnosticKey(key: String): String {
    return key
        .replace('_', ' ')
        .replace('-', ' ')
        .trim()
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}

private fun shareCleanupReport(
    context: android.content.Context,
    info: CleanupStatusInfo
): Boolean {
    if (info.report == null) return false
    val shareIntent = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.share_cleanup_report_title))
        .putExtra(Intent.EXTRA_TITLE, context.getString(R.string.share_cleanup_report_title))
        .putExtra(Intent.EXTRA_TEXT, buildCleanupReportShareText(context, info))
    val chooser = Intent.createChooser(
        shareIntent,
        context.getString(R.string.share_cleanup_report_title)
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return try {
        context.startActivity(chooser)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

private fun buildCleanupReportShareText(
    context: android.content.Context,
    info: CleanupStatusInfo
): String {
    val report = info.report ?: return info.summary
    val fileLinePrefix = context.getString(R.string.label_document_ai_cleanup_detail_file, "")
    return buildString {
        appendLine(context.getString(R.string.cleanup_report_title))
        appendLine(info.summary)

        info.detailLines
            .filterNot { it.startsWith(fileLinePrefix) }
            .forEach { appendLine(it) }

        appendLine()
        appendLine(context.getString(R.string.cleanup_report_label_scope))
        appendLine(
            context.getString(
                R.string.cleanup_report_value_scope,
                report.eligibleParagraphs,
                report.totalParagraphs,
                report.totalChunks
            )
        )
        appendLine(context.getString(R.string.cleanup_report_label_result))
        appendLine(
            context.getString(
                R.string.cleanup_report_value_result,
                report.acceptedChunks,
                report.rejectedChunks,
                report.changedParagraphs,
                report.droppedParagraphs
            )
        )
        appendLine(context.getString(R.string.cleanup_report_label_text_delta))
        appendLine(
            context.getString(
                R.string.cleanup_report_value_text_delta,
                report.eligibleCharsBefore,
                report.eligibleCharsAfter
            )
        )
        appendLine("${context.getString(R.string.cleanup_report_label_llm_scope)}:")
        appendLine(buildCleanupLlmScopeSummary(report))

        if (report.changeKindCounts.isNotEmpty()) {
            appendLine()
            appendLine(context.getString(R.string.cleanup_report_change_kinds_title))
            report.changeKindCounts.forEach { count ->
                appendLine("${formatCleanupDiagnosticKey(count.key)}: ${count.count}")
            }
        }

        if (report.rejectionReasonCounts.isNotEmpty()) {
            appendLine()
            appendLine(context.getString(R.string.cleanup_report_rejections_title))
            report.rejectionReasonCounts.forEach { count ->
                appendLine("${formatCleanupDiagnosticKey(count.key)}: ${count.count}")
            }
        }

        if (report.chunkReports.isNotEmpty()) {
            appendLine()
            appendLine(context.getString(R.string.cleanup_report_chunks_title))
            report.chunkReports.forEachIndexed { index, chunk ->
                appendLine(
                    context.getString(
                        R.string.cleanup_report_chunk_title,
                        index + 1,
                        chunk.firstBlockIndex + 1,
                        chunk.lastBlockIndex + 1
                    )
                )
                appendLine(
                    "${context.getString(R.string.cleanup_report_label_llm_input)}: " +
                        buildCleanupChunkLlmInputSummary(chunk)
                )
                appendLine(
                    "${context.getString(R.string.cleanup_report_label_status)}: " +
                        context.getString(cleanupChunkStatusStringRes(chunk.status))
                )
                chunk.rejectionReason?.let { reason ->
                    appendLine(
                        "${context.getString(R.string.cleanup_report_label_reason)}: " +
                            formatCleanupDiagnosticKey(reason)
                    )
                }
                chunk.failureSummary?.let { failureSummary ->
                    appendLine(
                        "${context.getString(R.string.cleanup_report_label_error)}: $failureSummary"
                    )
                }
                appendLine(
                    "${context.getString(R.string.cleanup_report_label_chars)}: " +
                        context.getString(
                            R.string.cleanup_report_value_chars,
                            chunk.originalChars,
                            chunk.cleanedChars ?: chunk.originalChars
                        )
                )
                appendLine(
                    "${context.getString(R.string.cleanup_report_label_changes)}: " +
                        context.getString(
                            R.string.cleanup_report_value_changes,
                            chunk.changedParagraphs,
                            chunk.droppedParagraphs
                        )
                )
            }
        }

        if (info.diffEntries.isNotEmpty()) {
            appendLine()
            appendLine(context.getString(R.string.cleanup_report_diffs_title))
            info.diffEntries.take(8).forEachIndexed { index, entry ->
                appendLine(
                    context.getString(
                        R.string.cleanup_report_diff_title,
                        index + 1,
                        entry.blockIndex + 1
                    )
                )
                if (entry.changeKinds.isNotEmpty()) {
                    appendLine(
                        "${context.getString(R.string.cleanup_report_label_changes)}: " +
                            entry.changeKinds.joinToString(" · ", transform = ::formatCleanupDiagnosticKey)
                    )
                }
                appendLine("${context.getString(R.string.cleanup_report_before_title)}:")
                appendLine(trimCleanupReportShareText(entry.beforeText))
                appendLine("${context.getString(R.string.cleanup_report_after_title)}:")
                appendLine(trimCleanupReportShareText(entry.afterText))
            }
        }
    }.trim()
}

private fun trimCleanupReportShareText(
    text: String,
    maxChars: Int = 320
): String {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.length <= maxChars) return normalized
    return normalized.take(maxChars - 1).trimEnd() + "…"
}

private fun buildCleanupLlmScopeSummary(report: CleanupRunDiagnostics): String {
    val skippedParagraphs = (report.totalParagraphs - report.eligibleParagraphs).coerceAtLeast(0)
    return buildString {
        append("Only eligible body paragraphs were sent to the LLM (${report.eligibleParagraphs} of ${report.totalParagraphs}).")
        if (skippedParagraphs > 0) {
            append(" $skippedParagraphs paragraph(s) stayed out because they were headings, metadata, footnotes, or ineligible body text.")
        }
        append(" Neighbor eligible paragraphs may be included as read-only context and are not rewritten.")
    }
}

private fun buildCleanupChunkLlmInputSummary(chunk: CleanupChunkReport): String {
    val contextParts = buildList {
        if (chunk.contextBeforeChars > 0) {
            add("before (${chunk.contextBeforeChars} chars)")
        }
        if (chunk.contextAfterChars > 0) {
            add("after (${chunk.contextAfterChars} chars)")
        }
    }
    return buildString {
        append("${chunk.targetParagraphCount} target paragraph(s)")
        if (contextParts.isEmpty()) {
            append("; no read-only context")
        } else {
            append("; read-only context: ")
            append(contextParts.joinToString(separator = ", "))
        }
    }
}

@androidx.annotation.StringRes
private fun cleanupChunkStatusStringRes(status: CleanupChunkStatus): Int {
    return when (status) {
        CleanupChunkStatus.AcceptedChanged -> R.string.cleanup_report_status_changed
        CleanupChunkStatus.AcceptedUnchanged -> R.string.cleanup_report_status_unchanged
        CleanupChunkStatus.Rejected -> R.string.cleanup_report_status_rejected
    }
}

@Composable
private fun formatCleanupChunkStatus(status: CleanupChunkStatus): String {
    return stringResource(cleanupChunkStatusStringRes(status))
}

private fun documentCleanupStatusLabel(
    context: android.content.Context,
    document: ReaderDocument
): CleanupStatusInfo? {
    val appContext = context.applicationContext
    val repository = LocalCleanupModelRepository(appContext)
    val cleanupReport = document.cleanupDiagnostics
    val cleanupDiffEntries = buildCleanupDiffEntries(document)

    document.presentation?.let { presentation ->
        val installedInfo = repository.installedModelInfo(presentation.modelId)
        val discoveredModel = installedInfo?.let(::discoverCleanupModel)
        val discoveredName = discoveredModel?.displayName
        val fallbackFileName = presentation.modelId.substringAfterLast('/').ifBlank { presentation.modelId }
        val modelLabel = discoveredName ?: cleanupModelDisplayName(fallbackFileName)
        val sizeLabel = installedInfo?.let { Formatter.formatFileSize(context, it.sizeBytes) }
        val configuredAccelerationLabel = discoveredModel?.runtimeSpec?.backendKind
            ?.takeIf { it == CleanupBackendKind.LiteRtLm }
            ?.let { cleanupAccelerationModeLabel(context, ReaderCleanupAccelerationMode.Cpu) }
        val actualAccelerationLabel = presentation.executionBackendLabel ?: document.summary?.executionBackendLabel
        val accelerationLabel = actualAccelerationLabel ?: configuredAccelerationLabel
        val runtimeLabel = discoveredModel?.runtimeSpec?.backendKind?.let {
            when (it) {
                CleanupBackendKind.LiteRtLm -> context.getString(R.string.options_cleanup_model_runtime_litertlm)
                CleanupBackendKind.MediaPipeTask -> context.getString(R.string.options_cleanup_model_runtime_task)
            }
        }
        return CleanupStatusInfo(
            title = context.getString(R.string.label_document_ai_cleanup_title),
            summary = listOfNotNull(modelLabel, sizeLabel, accelerationLabel).joinToString(separator = " - "),
            detailLines = buildList {
                add(context.getString(R.string.label_document_ai_cleanup_detail_status_applied))
                add(context.getString(R.string.label_document_ai_cleanup_detail_model, modelLabel))
                sizeLabel?.let { add(context.getString(R.string.label_document_ai_cleanup_detail_size, it)) }
                runtimeLabel?.let { add(context.getString(R.string.label_document_ai_cleanup_detail_backend, it)) }
                actualAccelerationLabel?.let {
                    add(context.getString(R.string.label_document_ai_cleanup_detail_acceleration, it))
                } ?: configuredAccelerationLabel?.let {
                    add(context.getString(R.string.label_document_ai_cleanup_detail_acceleration, it))
                }
                add(context.getString(R.string.label_document_ai_cleanup_detail_file, fallbackFileName))
            },
            report = cleanupReport,
            diffEntries = cleanupDiffEntries
        )
    }

    val settingsRepository = ReaderCleanupSettingsRepository(appContext)
    if (settingsRepository.cleanupMode() == ReaderCleanupMode.Off) {
        return null
    }

    val resolvedModel = resolveInstalledCleanupModel(
        installedInfos = repository.listInstalledModelInfos(),
        preferredModelId = settingsRepository.selectedModelId()
    ) ?: return null
    if (!supportsDocumentCleanupBackend(resolvedModel.runtimeSpec.backendKind)) {
        return null
    }
    val selectedInfo = resolvedModel.info
    val discoveredModel = resolvedModel.discoveredModel
    val sizeLabel = Formatter.formatFileSize(context, selectedInfo.sizeBytes)
    val accelerationLabel = discoveredModel.runtimeSpec.backendKind
        .takeIf { it == CleanupBackendKind.LiteRtLm }
        ?.let { cleanupAccelerationModeLabel(context, ReaderCleanupAccelerationMode.Cpu) }
    val runtimeLabel = when (discoveredModel.runtimeSpec.backendKind) {
        CleanupBackendKind.LiteRtLm -> context.getString(R.string.options_cleanup_model_runtime_litertlm)
        CleanupBackendKind.MediaPipeTask -> context.getString(R.string.options_cleanup_model_runtime_task)
    }
    return CleanupStatusInfo(
        title = context.getString(R.string.label_document_ai_cleanup_title),
        summary = listOfNotNull(discoveredModel.displayName, sizeLabel, accelerationLabel).joinToString(separator = " - "),
        detailLines = buildList {
            add(context.getString(R.string.label_document_ai_cleanup_detail_status_ready))
            add(context.getString(R.string.label_document_ai_cleanup_detail_model, discoveredModel.displayName))
            add(context.getString(R.string.label_document_ai_cleanup_detail_size, sizeLabel))
            add(context.getString(R.string.label_document_ai_cleanup_detail_backend, runtimeLabel))
            accelerationLabel?.let {
                add(context.getString(R.string.label_document_ai_cleanup_detail_acceleration, it))
            }
            add(context.getString(R.string.label_document_ai_cleanup_detail_file, selectedInfo.fileName))
            if (cleanupReport != null) {
                add(context.getString(R.string.cleanup_report_ready_detail))
            }
        },
        report = cleanupReport,
        diffEntries = cleanupDiffEntries
    )
}

private fun documentSummaryStatusLabel(
    context: android.content.Context,
    document: ReaderDocument,
    activeModelStorageKey: String? = null,
    activeConfiguredAccelerationMode: ReaderCleanupAccelerationMode? = null,
    activeBackendLabel: String? = null,
    preferActiveState: Boolean = false
): String? {
    val appContext = context.applicationContext
    val repository = LocalCleanupModelRepository(appContext)
    val settingsRepository = ReaderCleanupSettingsRepository(appContext)
    val summary = document.summary
    val shouldPreferActiveModel = preferActiveState && !activeModelStorageKey.isNullOrBlank()
    val installedInfos = repository.listInstalledModelInfos()

    if (!shouldPreferActiveModel && summary != null && summary.modelId.isNotBlank()) {
        val installedInfo = findInstalledCleanupModelInfo(
            installedInfos = installedInfos,
            modelId = summary.modelId
        )
        val discoveredModel = installedInfo?.let(::discoverCleanupModel)
        val fallbackFileName = summary.modelId.substringAfterLast('/').ifBlank { summary.modelId }
        val modelLabel = discoveredModel?.displayName ?: cleanupModelDisplayName(fallbackFileName)
        val sizeLabel = installedInfo?.let { Formatter.formatFileSize(context, it.sizeBytes) }
        val backendLabel = summary.executionBackendLabel
            ?: activeBackendLabel
            ?: discoveredModel?.runtimeSpec?.backendKind
                ?.takeIf { it == CleanupBackendKind.LiteRtLm }
                ?.let {
                    cleanupAccelerationModeLabel(
                        context,
                        activeConfiguredAccelerationMode ?: ReaderCleanupAccelerationMode.Cpu
                    )
                }
        return listOfNotNull(modelLabel, sizeLabel, backendLabel)
            .joinToString(separator = " - ")
            .takeIf { it.isNotBlank() }
    }

    val modelStorageKey = when {
        shouldPreferActiveModel -> activeModelStorageKey
        !activeModelStorageKey.isNullOrBlank() -> activeModelStorageKey
        else -> settingsRepository.selectedModelId()
    }
    val resolvedModel = resolveInstalledCleanupModel(
        installedInfos = installedInfos,
        preferredModelId = modelStorageKey
    ) ?: return null
    val installedInfo = resolvedModel.info
    val discoveredModel = resolvedModel.discoveredModel
    val sizeLabel = Formatter.formatFileSize(context, installedInfo.sizeBytes)
    val backendLabel = activeBackendLabel
        ?: discoveredModel.runtimeSpec.backendKind
            .takeIf { it == CleanupBackendKind.LiteRtLm }
            ?.let {
                cleanupAccelerationModeLabel(
                    context,
                    activeConfiguredAccelerationMode ?: ReaderCleanupAccelerationMode.Cpu
                )
            }
    return listOfNotNull(discoveredModel.displayName, sizeLabel, backendLabel)
        .joinToString(separator = " - ")
        .takeIf { it.isNotBlank() }
}

private fun cleanupAccelerationModeLabel(
    context: android.content.Context,
    mode: ReaderCleanupAccelerationMode
): String {
    return when (mode) {
        ReaderCleanupAccelerationMode.Cpu ->
            context.getString(R.string.options_cleanup_acceleration_cpu)
        ReaderCleanupAccelerationMode.Auto ->
            context.getString(R.string.options_cleanup_acceleration_auto)
        ReaderCleanupAccelerationMode.Gpu ->
            context.getString(R.string.options_cleanup_acceleration_gpu)
        ReaderCleanupAccelerationMode.Npu ->
            context.getString(R.string.options_cleanup_acceleration_npu)
    }
}

@Composable
private fun rememberDiagnosticsSample(
    context: android.content.Context,
): DiagnosticsSample? {
    val appContext = context.applicationContext
    val state = produceState<DiagnosticsSample?>(initialValue = null, key1 = appContext) {
        val sessionStartedAtMs = SystemClock.elapsedRealtime()
        val startingSnapshot = readBatterySnapshot(appContext)
        var lastCpuTimeMs = android.os.Process.getElapsedCpuTime()
        var lastRealtimeMs = SystemClock.elapsedRealtime()

        while (isActive) {
            val currentSnapshot = readBatterySnapshot(appContext)
            val currentCpuTimeMs = android.os.Process.getElapsedCpuTime()
            val currentRealtimeMs = SystemClock.elapsedRealtime()
            val cpuPercent = (currentRealtimeMs - lastRealtimeMs)
                .takeIf { it > 0L }
                ?.let { elapsedMs ->
                    val cpuDeltaMs = (currentCpuTimeMs - lastCpuTimeMs).coerceAtLeast(0L)
                    ((cpuDeltaMs.toFloat() / elapsedMs.toFloat()) * 100f).coerceAtLeast(0f)
                }

            val runtime = Runtime.getRuntime()
            val usedMemoryMb = ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L))
                .coerceAtLeast(0L)
            val batteryDeltaPercent = if (startingSnapshot != null && currentSnapshot != null) {
                currentSnapshot.levelPercent - startingSnapshot.levelPercent
            } else {
                0
            }
            val approxDeviceDrawWatts = currentSnapshot?.currentAmps?.let { amps ->
                currentSnapshot.voltageVolts?.let { volts ->
                    kotlin.math.abs(amps) * volts
                }
            }
            val playbackState = ReaderPlaybackStore.uiState.value
            val playbackStatus = when {
                playbackState.isSpeaking -> appContext.getString(R.string.diagnostics_status_speaking)
                playbackState.hasSegments -> appContext.getString(R.string.diagnostics_status_paused)
                else -> appContext.getString(R.string.diagnostics_status_idle)
            }
            val summaryState = SummaryDiagnosticsStore.uiState.value
            val summaryStatus = when (summaryState.status) {
                "generating" -> appContext.getString(R.string.diagnostics_status_generating)
                else -> appContext.getString(R.string.diagnostics_status_idle)
            }

            value = DiagnosticsSample(
                batterySnapshot = currentSnapshot,
                batteryDeltaPercent = batteryDeltaPercent,
                approxDeviceDrawWatts = approxDeviceDrawWatts,
                processCpuPercent = cpuPercent,
                processMemoryMb = usedMemoryMb,
                threadCount = Thread.getAllStackTraces().size,
                accessibilityEnabled = isReadAccessibilityServiceEnabled(appContext),
                summaryStatus = summaryStatus,
                playbackStatus = playbackStatus,
                sessionMinutes = ((currentRealtimeMs - sessionStartedAtMs).coerceAtLeast(0L) / 60_000L).toInt()
            )

            lastCpuTimeMs = currentCpuTimeMs
            lastRealtimeMs = currentRealtimeMs
            delay(5_000L)
        }
    }
    return state.value
}

@Composable
private fun rememberDiagnosticsLoggingState(
    context: android.content.Context
): DiagnosticsLoggingState? {
    val appContext = context.applicationContext
    val state = produceState<DiagnosticsLoggingState?>(initialValue = null, key1 = appContext) {
        val repository = DiagnosticsLogRepository(appContext)
        while (isActive) {
            value = repository.currentState()
            delay(2_000L)
        }
    }
    return state.value
}

private fun readBatterySnapshot(context: android.content.Context): BatterySnapshot? {
    val batteryIntent = context.registerReceiver(
        null,
        android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
    ) ?: return null

    val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) {
        return null
    }

    val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
    val isCharging =
        status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

    val voltageMillivolts = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        .takeIf { it > 0 }
    val temperatureTenthsC = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        .takeIf { it != Int.MIN_VALUE }

    val batteryManager = context.getSystemService(BatteryManager::class.java)
    val averageCurrentMicroamps = batteryManager
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        ?.takeIf { it != Int.MIN_VALUE && it != 0 }
    val currentNowMicroamps = batteryManager
        ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        ?.takeIf { it != Int.MIN_VALUE && it != 0 }
    val chosenCurrentMicroamps = averageCurrentMicroamps ?: currentNowMicroamps

    return BatterySnapshot(
        levelPercent = ((level * 100f) / scale.toFloat()).roundToInt().coerceIn(0, 100),
        isCharging = isCharging,
        voltageVolts = voltageMillivolts?.toDouble()?.div(1000.0),
        currentAmps = chosenCurrentMicroamps?.toDouble()?.div(1_000_000.0),
        temperatureCelsius = temperatureTenthsC?.div(10f)
    )
}

private fun formatSignedPercent(value: Int): String {
    return when {
        value > 0 -> "+$value%"
        else -> "$value%"
    }
}

private fun formatDiagnosticsTimestamp(epochMs: Long): String {
    return java.text.DateFormat.getDateTimeInstance(
        java.text.DateFormat.SHORT,
        java.text.DateFormat.MEDIUM
    ).format(java.util.Date(epochMs))
}

private fun preferredMetadataSummaryLine(
    title: String,
    metadataBlocks: List<ReaderBlock>
): String? {
    val normalizedTitle = normalizeMetadataSummaryLine(title)
    val normalizedBlocks = metadataBlocks
        .asSequence()
        .map { it.text.replace(Regex("""\s+"""), " ").trim() }
        .filter { it.isNotBlank() }
        .filterNot { isTitleLikeMetadataLine(it, normalizedTitle) }
        .toList()

    normalizedBlocks.firstOrNull(::looksLikeMetadataAuthorSummaryLine)?.let { return it }

    return normalizedBlocks.firstOrNull { line ->
        val lower = line.lowercase()
        !lower.startsWith("abstract") &&
            !lower.startsWith("summary") &&
            !lower.startsWith("keywords")
    }
}

private fun normalizeMetadataSummaryLine(text: String): String {
    return text
        .lowercase()
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""[^\p{L}\p{N} ]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun isTitleLikeMetadataLine(
    line: String,
    normalizedTitle: String
): Boolean {
    if (normalizedTitle.isBlank()) {
        return false
    }

    val normalizedLine = normalizeMetadataSummaryLine(line)
    if (normalizedLine.isBlank()) {
        return false
    }

    if (normalizedLine == normalizedTitle) {
        return true
    }

    return normalizedLine.length >= normalizedTitle.length &&
        normalizedLine.contains(normalizedTitle)
}

private fun looksLikeMetadataAuthorSummaryLine(text: String): Boolean {
    val clean = text.trim()
    if (clean.isBlank()) {
        return false
    }

    if (clean.startsWith("By ", ignoreCase = true)) {
        return true
    }

    val lower = clean.lowercase()
    if (
        listOf(
            "@",
            "university",
            "institute",
            "department",
            "school",
            "college",
            "center",
            "centre",
            "laboratory",
            "abstract",
            "introduction",
            "keywords",
            "arxiv",
            "doi",
            "copyright"
        ).any(lower::contains)
    ) {
        return false
    }

    val segments = clean
        .split(Regex("""\s*,\s*|\s+(?:and|&)\s+""", RegexOption.IGNORE_CASE))
        .map { it.trim() }
        .filter { it.isNotBlank() }
    if (segments.isEmpty()) {
        return false
    }

    return segments.all { segment ->
        val words = segment.split(Regex("""\s+""")).filter { it.isNotBlank() }
        words.size in 2..4 && words.all { word ->
            val normalized = word.trim(',', ';', ':', '.', '(', ')')
            val letters = normalized.filter(Char::isLetter)
            letters.length in 2..20 &&
                (
                    letters == letters.uppercase() ||
                        (letters.firstOrNull()?.isUpperCase() == true && letters.drop(1).all { it.isLowerCase() })
                    )
        }
    }
}

@Composable
private fun ReaderSelectableDocumentBody(
    title: String? = null,
    metadataBlocks: List<ReaderBlock> = emptyList(),
    blocks: List<ReaderBlock>,
    footnoteBlocks: List<ReaderBlock> = emptyList(),
    modifier: Modifier = Modifier
) {
    val defaultTextColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val secondaryTextColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val containerColor = MaterialTheme.colorScheme.surface.toArgb()
    val footnotesTitle = stringResource(R.string.label_footnotes)
    val horizontalPaddingPx = with(LocalDensity.current) { 6.dp.roundToPx() }
    val verticalPaddingPx = with(LocalDensity.current) { 6.dp.roundToPx() }

    val selectableText = remember(
        title,
        metadataBlocks,
        blocks,
        footnoteBlocks,
        defaultTextColor,
        secondaryTextColor,
        footnotesTitle
    ) {
        buildSelectableDocumentSpannable(
            title = title,
            metadataBlocks = metadataBlocks,
            blocks = blocks,
            footnoteBlocks = footnoteBlocks,
            footnotesTitle = footnotesTitle,
            defaultTextColor = defaultTextColor,
            secondaryTextColor = secondaryTextColor
        )
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            val scrollView = ScrollView(viewContext).apply {
                isFillViewport = true
                overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                isVerticalScrollBarEnabled = true
                scrollBarStyle = View.SCROLLBARS_INSIDE_INSET
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(containerColor)
            }
            val textView = TextView(viewContext).apply {
                setTextIsSelectable(true)
                isLongClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                setHorizontallyScrolling(false)
                minLines = 12
                gravity = Gravity.TOP or Gravity.START
                textAlignment = View.TEXT_ALIGNMENT_VIEW_START
                setMinEms(0)
                minWidth = 0
                textSize = 18f
                setTextColor(defaultTextColor)
                setLineSpacing(0f, 1.18f)
                setBackgroundColor(containerColor)
                setPadding(horizontalPaddingPx, verticalPaddingPx, horizontalPaddingPx, verticalPaddingPx)
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            scrollView.addView(textView)
            scrollView
        },
        update = { scrollView ->
            scrollView.setBackgroundColor(containerColor)
            val textView = scrollView.getChildAt(0) as TextView
            textView.setBackgroundColor(containerColor)
            textView.setTextColor(defaultTextColor)
            textView.textSize = 18f
            val existingText = textView.text
            if (!TextUtils.equals(existingText, selectableText)) {
                val scrollX = textView.scrollX
                val scrollY = scrollView.scrollY
                textView.setTextKeepState(selectableText, TextView.BufferType.SPANNABLE)
                scrollView.post {
                    scrollView.scrollTo(scrollX, scrollY)
                }
            }
        }
    )
}

private fun buildSelectableDocumentSpannable(
    title: String?,
    metadataBlocks: List<ReaderBlock>,
    blocks: List<ReaderBlock>,
    footnoteBlocks: List<ReaderBlock>,
    footnotesTitle: String,
    defaultTextColor: Int,
    secondaryTextColor: Int
): CharSequence {
    val builder = SpannableStringBuilder()

    fun appendStyledBlock(
        text: String,
        blockType: ReaderBlockType
    ) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) {
            return
        }
        if (builder.isNotEmpty()) {
            builder.append("\n\n")
        }
        val start = builder.length
        builder.append(trimmed)
        val end = builder.length
        when (blockType) {
            ReaderBlockType.Heading -> {
                builder.setSpan(StyleSpan(Typeface.BOLD), start, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(RelativeSizeSpan(1.12f), start, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(defaultTextColor), start, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            ReaderBlockType.Metadata,
            ReaderBlockType.Footnote -> {
                builder.setSpan(RelativeSizeSpan(0.92f), start, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(secondaryTextColor), start, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            }

            ReaderBlockType.Paragraph -> {
                builder.setSpan(ForegroundColorSpan(defaultTextColor), start, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    title
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.let { appendStyledBlock(it, ReaderBlockType.Heading) }

    metadataBlocks
        .filter { it.text.isNotBlank() }
        .forEach { block -> appendStyledBlock(block.text, ReaderBlockType.Metadata) }

    blocks.filter { it.text.isNotBlank() }.forEach { block ->
        appendStyledBlock(block.text, block.type)
    }

    if (footnoteBlocks.any { it.text.isNotBlank() }) {
        appendStyledBlock(footnotesTitle, ReaderBlockType.Heading)
        footnoteBlocks.filter { it.text.isNotBlank() }.forEach { block ->
            appendStyledBlock(block.text, ReaderBlockType.Footnote)
        }
    }

    return builder
}

@Composable
private fun ReaderBlockText(
    text: String,
    active: Boolean,
    activeRange: IntRange?,
    textStyle: androidx.compose.ui.text.TextStyle,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    readRange: IntRange? = null,
    readColor: androidx.compose.ui.graphics.Color = color,
    bookmarkEntries: List<BookmarkEntry> = emptyList(),
    bookmarkRanges: List<IntRange> = emptyList(),
    citationRanges: List<IntRange> = emptyList(),
    onClickOffset: ((Int) -> Unit)? = null,
    onLongClickOffset: ((Int) -> Unit)? = null,
    onBookmarkIndicatorClick: ((BookmarkEntry) -> Unit)? = null,
    onBookmarkIndicatorLongClick: ((BookmarkEntry) -> Unit)? = null,
    onActiveLineMetricsChanged: ((Int, Float, Float) -> Unit)? = null,
    selectable: Boolean = false
) {
    var layoutResult by remember(text, bookmarkRanges, citationRanges) { mutableStateOf<TextLayoutResult?>(null) }
    if (selectable) {
        SelectionContainer {
            Text(
                text = text,
                style = textStyle,
                color = color,
                modifier = Modifier.fillMaxWidth()
            )
        }
        return
    }
    val displayTransform = rememberDisplayTextTransform(
        text = text,
        readRange = readRange,
        bookmarkRanges = bookmarkRanges,
        citationRanges = citationRanges,
        bookmarkBackground = Color.Transparent,
        bookmarkColor = MaterialTheme.colorScheme.onSurface,
        readColor = readColor,
        citationColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f)
    )
    fun pointerOffsetFor(position: androidx.compose.ui.geometry.Offset): Int {
        val displayOffset = layoutResult?.getOffsetForPosition(position)
            ?.coerceIn(0, displayTransform.displayToOriginal.lastIndex)
            ?: 0
        return displayTransform.displayToOriginal[displayOffset]
    }
    val blockInteractionModifier = if (!selectable && (onClickOffset != null || onLongClickOffset != null)) {
        Modifier.pointerInput(text, bookmarkRanges, onClickOffset, onLongClickOffset) {
            detectTapGestures(
                onTap = { position ->
                    onClickOffset?.invoke(pointerOffsetFor(position))
                },
                onLongPress = { position ->
                    onLongClickOffset?.invoke(pointerOffsetFor(position))
                }
            )
        }
    } else {
        Modifier
    }
    val hasBookmarks = bookmarkRanges.isNotEmpty()
    val hasCitations = citationRanges.isNotEmpty()
    val density = LocalDensity.current
    val bookmarkUnderlineColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
    val bookmarkUnderlineThicknessPx = with(density) { 2.25.dp.toPx() }
    val bookmarkUnderlineOffsetPx = with(density) { 1.5.dp.toPx() }
    val currentLineMarkerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)
    val currentLineMarkerThicknessPx = with(density) { 2.5.dp.toPx() }
    val currentLineMarkerOffsetPx = with(density) { 2.5.dp.toPx() }
    val bookmarkIndicatorGroups = remember(layoutResult, bookmarkEntries, density, text) {
        layoutResult?.let { result ->
            val indicatorHeight = with(density) { 26.dp.toPx() }
            bookmarkEntries
                .mapNotNull { bookmark ->
                    val range = bookmarkRangeForEntry(text, bookmark) ?: return@mapNotNull null
                    val safeOffset = range.first.coerceIn(0, (text.length - 1).coerceAtLeast(0))
                    val line = result.getLineForOffset(safeOffset)
                    val lineCenter = (result.getLineTop(line) + result.getLineBottom(line)) / 2f
                    val indicatorOffset = (lineCenter - indicatorHeight / 2f).roundToInt().coerceAtLeast(0)
                    line to (indicatorOffset to bookmark)
                }
                .groupBy(
                    keySelector = { (line, _) -> line },
                    valueTransform = { (_, offsetAndBookmark) -> offsetAndBookmark }
                )
                .values
                .map { groupedEntries ->
                    val indicatorOffset = groupedEntries.first().first
                    BookmarkIndicatorGroup(
                        offsetPx = indicatorOffset,
                        bookmarks = groupedEntries.map { it.second }
                    )
                }
                .sortedBy { it.offsetPx }
        } ?: emptyList()
    }
    LaunchedEffect(active, activeRange, layoutResult, onActiveLineMetricsChanged) {
        val callback = onActiveLineMetricsChanged ?: return@LaunchedEffect
        val result = layoutResult ?: return@LaunchedEffect
        val range = activeRange ?: return@LaunchedEffect
        if (!active || text.isBlank()) {
            return@LaunchedEffect
        }
        val safeOffset = range.first.coerceIn(0, (text.length - 1).coerceAtLeast(0))
        val lineIndex = result.getLineForOffset(safeOffset)
        callback(
            lineIndex,
            result.getLineTop(lineIndex),
            result.getLineBottom(lineIndex)
        )
    }

    if (active && activeRange == null && !hasBookmarks && !hasCitations) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .then(blockInteractionModifier),
            color = MaterialTheme.colorScheme.secondaryContainer,
            tonalElevation = 2.dp,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = text,
                style = textStyle,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(12.dp),
                onTextLayout = { layoutResult = it }
            )
        }
    } else {
        Box(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = displayTransform.annotated,
                style = textStyle,
                color = color,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = if (hasBookmarks) 36.dp else 0.dp)
                    .drawBehind {
                        val result = layoutResult ?: return@drawBehind
                        if (active && activeRange != null) {
                            drawCurrentLineMarker(
                                layoutResult = result,
                                activeRange = activeRange,
                                markerColor = currentLineMarkerColor,
                                thicknessPx = currentLineMarkerThicknessPx,
                                offsetPx = currentLineMarkerOffsetPx
                            )
                        }
                        displayTransform.bookmarkDisplayRanges.forEach { range ->
                            drawBookmarkUnderlineRange(
                                layoutResult = result,
                                range = range,
                                underlineColor = bookmarkUnderlineColor,
                                thicknessPx = bookmarkUnderlineThicknessPx,
                                offsetPx = bookmarkUnderlineOffsetPx
                            )
                        }
                    }
                    .then(blockInteractionModifier),
                onTextLayout = { layoutResult = it }
            )
            if (hasBookmarks) {
                bookmarkIndicatorGroups.forEach { indicator ->
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset { IntOffset(0, indicator.offsetPx) }
                            .combinedClickable(
                                enabled = onBookmarkIndicatorClick != null || onBookmarkIndicatorLongClick != null,
                                onClick = {
                                    indicator.bookmarks.firstOrNull()?.let { bookmark ->
                                        onBookmarkIndicatorClick?.invoke(bookmark)
                                    }
                                },
                                onLongClick = {
                                    indicator.bookmarks.firstOrNull()?.let { bookmark ->
                                        onBookmarkIndicatorLongClick?.invoke(bookmark)
                                    }
                                }
                            ),
                        color = MaterialTheme.colorScheme.tertiaryContainer,
                        shape = MaterialTheme.shapes.small,
                        tonalElevation = 2.dp
                    ) {
                        Icon(
                            Icons.Rounded.Bookmark,
                            contentDescription = stringResource(R.string.content_desc_bookmarked_sentence),
                            modifier = Modifier
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                .size(18.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                }
            }
        }
    }
}

private data class DisplayTextTransform(
    val annotated: AnnotatedString,
    val displayToOriginal: IntArray,
    val bookmarkDisplayRanges: List<IntRange>
)

private data class ActiveReadingLineMetrics(
    val absoluteIndex: Int,
    val lineIndex: Int,
    val lineTopPx: Float,
    val lineBottomPx: Float
)

private data class BookmarkIndicatorGroup(
    val offsetPx: Int,
    val bookmarks: List<BookmarkEntry>
)

private data class PlaybackVisualSnapshot(
    val blockIndex: Int,
    val segmentRange: IntRange?,
    val isSpeaking: Boolean
)

internal fun shouldPauseAutoFollowForDrag(
    showDocument: Boolean,
    manualReaderDragActive: Boolean,
    visualPlaybackIsSpeaking: Boolean
): Boolean {
    return showDocument && manualReaderDragActive && visualPlaybackIsSpeaking
}

private class MutableHolder<T>(var value: T)

private data class SkimEntry(
    val openBlockIndex: Int,
    val blockIndex: Int,
    val block: ReaderBlock,
    val label: String
)

private data class DocumentProgressMetrics(
    val progressFraction: Float
)

private fun calculateDocumentProgressMetrics(
    listState: LazyListState,
    document: ReaderDocument
): DocumentProgressMetrics {
    val totalBlocks = document.displayBlocks.size
    if (totalBlocks <= 0) {
        return DocumentProgressMetrics(progressFraction = 0f)
    }

    val prefixCount = documentListPrefixCount(document)
    val documentIndexRange = prefixCount until (prefixCount + totalBlocks)
    val visibleDocumentItems = listState.layoutInfo.visibleItemsInfo
        .filter { it.index in documentIndexRange }

    val firstVisibleDocumentItem = visibleDocumentItems.firstOrNull()
    val progressFraction = when {
        totalBlocks <= 1 -> 0f
        firstVisibleDocumentItem != null -> {
            val relativeIndex = (firstVisibleDocumentItem.index - prefixCount).coerceIn(0, totalBlocks - 1)
            val itemSize = firstVisibleDocumentItem.size.takeIf { it > 0 } ?: 1
            val itemScrollFraction = (-firstVisibleDocumentItem.offset).toFloat()
                .div(itemSize.toFloat())
                .coerceIn(0f, 1f)
            ((relativeIndex.toFloat() + itemScrollFraction) / (totalBlocks - 1).toFloat()).coerceIn(0f, 1f)
        }
        listState.firstVisibleItemIndex < prefixCount -> 0f
        else -> 1f
    }

    return DocumentProgressMetrics(progressFraction = progressFraction)
}

@Composable
private fun DocumentProgressRail(
    progressMetrics: DocumentProgressMetrics,
    onScrubToFraction: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = modifier
            .width(14.dp)
            .fillMaxHeight()
    ) {
        val railHeightPx = with(density) { maxHeight.toPx().coerceAtLeast(1f) }
        val thumbWidth = 8.dp
        val thumbHeight = 18.dp
        val travelDistance = (maxHeight - thumbHeight).coerceAtLeast(0.dp)
        val thumbOffset = travelDistance * progressMetrics.progressFraction.coerceIn(0f, 1f)
        fun fractionForPosition(y: Float): Float = (y / railHeightPx).coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(onScrubToFraction, railHeightPx) {
                    if (onScrubToFraction == null) {
                        return@pointerInput
                    }
                    detectTapGestures { offset ->
                        onScrubToFraction(fractionForPosition(offset.y))
                    }
                }
                .pointerInput(onScrubToFraction, railHeightPx) {
                    if (onScrubToFraction == null) {
                        return@pointerInput
                    }
                    detectDragGestures(
                        onDragStart = { offset ->
                            onScrubToFraction(fractionForPosition(offset.y))
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            onScrubToFraction(fractionForPosition(change.position.y))
                        }
                    )
                }
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxHeight()
                    .width(3.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.extraLarge
            ) {}

            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = thumbOffset)
                    .width(thumbWidth)
                    .height(thumbHeight),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 3.dp
            ) {}
        }
    }
}

@Composable
private fun DocumentProgressRailHost(
    listState: LazyListState,
    document: ReaderDocument,
    onScrubToFraction: ((Float) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val progressMetrics by remember(listState, document) {
        derivedStateOf { calculateDocumentProgressMetrics(listState, document) }
    }
    DocumentProgressRail(
        progressMetrics = progressMetrics,
        onScrubToFraction = onScrubToFraction,
        modifier = modifier
    )
}

@Composable
private fun PlaybackProgressScrubber(
    progressFraction: Float,
    enabled: Boolean,
    onSeekRequested: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var dragFraction by remember { mutableStateOf(progressFraction) }
    var dragging by remember { mutableStateOf(false) }
    var pendingSeekFraction by remember { mutableStateOf<Float?>(null) }
    var lastSettledProgressFraction by remember { mutableStateOf(progressFraction) }

    LaunchedEffect(progressFraction, dragging, pendingSeekFraction) {
        if (dragging) {
            return@LaunchedEffect
        }

        val pendingFraction = pendingSeekFraction
        if (pendingFraction != null) {
            val externalProgressMoved =
                kotlin.math.abs(progressFraction - lastSettledProgressFraction) > 0.001f
            val reachedPendingFraction =
                kotlin.math.abs(progressFraction - pendingFraction) <= 0.02f
            if (externalProgressMoved || reachedPendingFraction) {
                pendingSeekFraction = null
                lastSettledProgressFraction = progressFraction
                dragFraction = progressFraction
            } else {
                dragFraction = pendingFraction
            }
        } else {
            lastSettledProgressFraction = progressFraction
            dragFraction = progressFraction
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .height(18.dp)
    ) {
        val density = LocalDensity.current
        val fraction = when {
            dragging -> dragFraction
            pendingSeekFraction != null -> pendingSeekFraction!!
            else -> progressFraction
        }.coerceIn(0f, 1f)
        val trackHeight = 4.dp
        val thumbWidth = 10.dp
        val thumbHeight = 14.dp
        val widthPx = with(density) { maxWidth.toPx().coerceAtLeast(1f) }
        val thumbTravel = (maxWidth - thumbWidth).coerceAtLeast(0.dp)
        val thumbOffset = thumbTravel * fraction

        fun fractionForX(x: Float): Float = (x / widthPx).coerceIn(0f, 1f)

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(enabled, widthPx) {
                    if (!enabled) {
                        return@pointerInput
                    }
                    detectTapGestures { offset ->
                        val requestedFraction = fractionForX(offset.x)
                        pendingSeekFraction = requestedFraction
                        dragFraction = requestedFraction
                        onSeekRequested(requestedFraction)
                    }
                }
                .pointerInput(enabled, widthPx) {
                    if (!enabled) {
                        return@pointerInput
                    }
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragging = true
                            pendingSeekFraction = null
                            dragFraction = fractionForX(offset.x)
                        },
                        onDragEnd = {
                            pendingSeekFraction = dragFraction
                            onSeekRequested(dragFraction)
                            dragging = false
                        },
                        onDragCancel = {
                            dragging = false
                            pendingSeekFraction = null
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            dragFraction = fractionForX(change.position.x)
                        }
                    )
                }
        ) {
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .height(trackHeight),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.85f),
                shape = MaterialTheme.shapes.extraLarge
            ) {}

            Surface(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth(fraction)
                    .height(trackHeight),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
                shape = MaterialTheme.shapes.extraLarge
            ) {}

            Surface(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = thumbOffset)
                    .width(thumbWidth)
                    .height(thumbHeight),
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outline
                },
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 2.dp
            ) {}
        }
    }
}

@Composable
private fun rememberDisplayTextTransform(
    text: String,
    readRange: IntRange?,
    bookmarkRanges: List<IntRange>,
    citationRanges: List<IntRange>,
    bookmarkBackground: androidx.compose.ui.graphics.Color,
    bookmarkColor: androidx.compose.ui.graphics.Color,
    readColor: androidx.compose.ui.graphics.Color,
    citationColor: androidx.compose.ui.graphics.Color
): DisplayTextTransform {
    return remember(
        text,
        readRange,
        bookmarkRanges,
        citationRanges,
        bookmarkBackground,
        bookmarkColor,
        readColor,
        citationColor
    ) {
        buildDisplayTextTransform(
            text = text,
            readRange = readRange,
            bookmarkRanges = bookmarkRanges,
            citationRanges = citationRanges,
            bookmarkBackground = bookmarkBackground,
            bookmarkColor = bookmarkColor,
            readColor = readColor,
            citationColor = citationColor
        )
    }
}

private fun buildDisplayTextTransform(
    text: String,
    readRange: IntRange?,
    bookmarkRanges: List<IntRange>,
    citationRanges: List<IntRange>,
    bookmarkBackground: androidx.compose.ui.graphics.Color,
    bookmarkColor: androidx.compose.ui.graphics.Color,
    readColor: androidx.compose.ui.graphics.Color,
    citationColor: androidx.compose.ui.graphics.Color
): DisplayTextTransform {
    val normalizedBookmarkRanges = bookmarkRanges
        .mapNotNull { range ->
            if (text.isEmpty()) {
                null
            } else {
                val start = range.first.coerceIn(0, text.lastIndex)
                val end = range.last.coerceIn(start, text.lastIndex)
                start..end
            }
        }
        .distinctBy { it.first to it.last }
        .sortedBy { it.first }

    val normalizedCitationRanges = citationRanges
        .mapNotNull { range ->
            if (text.isEmpty()) {
                null
            } else {
                val start = range.first.coerceIn(0, text.lastIndex)
                val end = range.last.coerceIn(start, text.lastIndex)
                start..end
            }
        }
        .distinctBy { it.first to it.last }
        .sortedBy { it.first }

    val normalizedReadRange = readRange?.let { range ->
        if (text.isEmpty()) {
            null
        } else {
            val start = range.first.coerceIn(0, text.lastIndex)
            val end = range.last.coerceIn(start, text.lastIndex)
            start..end
        }
    }

    val builder = AnnotatedString.Builder()
    val displayToOriginal = ArrayList<Int>(text.length + 1)
    val originalToDisplay = IntArray(text.length + 1) { -1 }
    val bookmarkDisplayRanges = mutableListOf<IntRange>()

    fun appendOriginal(start: Int, endExclusive: Int) {
        for (originalIndex in start until endExclusive) {
            if (originalToDisplay[originalIndex] == -1) {
                originalToDisplay[originalIndex] = builder.length
            }
            builder.append(text[originalIndex])
            displayToOriginal.add(originalIndex)
        }
    }

    var cursor = 0
    normalizedBookmarkRanges.forEach { range ->
        val start = range.first.coerceAtLeast(cursor)
        val endExclusive = (range.last + 1).coerceIn(start, text.length)
        if (start > cursor) {
            appendOriginal(cursor, start)
        }
        if (start < text.length && start < endExclusive) {
            val contentStart = builder.length
            appendOriginal(start, endExclusive)
            val contentEnd = builder.length
            if (contentStart < contentEnd) {
                bookmarkDisplayRanges += contentStart..(contentEnd - 1)
            }
        }
        cursor = endExclusive
    }
    if (cursor < text.length) {
        appendOriginal(cursor, text.length)
    }

    originalToDisplay[text.length] = builder.length
    displayToOriginal.add(text.length)

    normalizedCitationRanges.forEach { range ->
        val start = range.first.coerceIn(0, text.length)
        val endExclusive = (range.last + 1).coerceIn(start, text.length)
        val displayStart = originalToDisplay[start].takeIf { it >= 0 } ?: 0
        val displayEndExclusive = originalToDisplay[endExclusive].takeIf { it >= displayStart } ?: builder.length
        if (displayStart < displayEndExclusive) {
            builder.addStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = citationColor
                ),
                displayStart,
                displayEndExclusive
            )
        }
    }

    bookmarkDisplayRanges.forEach { range ->
        builder.addStyle(
            androidx.compose.ui.text.SpanStyle(
                background = bookmarkBackground,
                color = bookmarkColor
            ),
            range.first,
            range.last + 1
        )
    }

    normalizedReadRange?.let { range ->
        val start = range.first.coerceIn(0, text.length)
        val endExclusive = (range.last + 1).coerceIn(start, text.length)
        val displayStart = originalToDisplay[start].takeIf { it >= 0 } ?: 0
        val displayEndExclusive = originalToDisplay[endExclusive].takeIf { it >= displayStart } ?: builder.length
        if (displayStart < displayEndExclusive) {
            builder.addStyle(
                androidx.compose.ui.text.SpanStyle(
                    color = readColor
                ),
                displayStart,
                displayEndExclusive
            )
        }
    }

    return DisplayTextTransform(
        annotated = builder.toAnnotatedString(),
        displayToOriginal = displayToOriginal.toIntArray(),
        bookmarkDisplayRanges = bookmarkDisplayRanges
    )
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBookmarkUnderlineRange(
    layoutResult: TextLayoutResult,
    range: IntRange,
    underlineColor: Color,
    thicknessPx: Float,
    offsetPx: Float
) {
    val safeStart = range.first.coerceIn(0, (layoutResult.layoutInput.text.text.length - 1).coerceAtLeast(0))
    val safeEnd = range.last.coerceIn(safeStart, (layoutResult.layoutInput.text.text.length - 1).coerceAtLeast(0))
    val startLine = layoutResult.getLineForOffset(safeStart)
    val endLine = layoutResult.getLineForOffset(safeEnd)
    for (line in startLine..endLine) {
        val lineStart = layoutResult.getLineStart(line)
        val lineVisibleEndExclusive = layoutResult.getLineEnd(line, visibleEnd = true)
        if (lineVisibleEndExclusive <= lineStart) {
            continue
        }
        val segmentStart = maxOf(safeStart, lineStart)
        val segmentEndInclusive = minOf(safeEnd, lineVisibleEndExclusive - 1)
        if (segmentEndInclusive < segmentStart) {
            continue
        }

        val startRect = layoutResult.getBoundingBox(segmentStart)
        val endRect = layoutResult.getBoundingBox(segmentEndInclusive)
        val startX = minOf(startRect.left, endRect.right)
        val endX = maxOf(startRect.left, endRect.right)
        val underlineY = (layoutResult.getLineBaseline(line) + offsetPx)
            .coerceAtMost(size.height - thicknessPx / 2f)

        drawLine(
            color = underlineColor,
            start = Offset(startX, underlineY),
            end = Offset(endX, underlineY),
            strokeWidth = thicknessPx,
            cap = StrokeCap.Round
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCurrentLineMarker(
    layoutResult: TextLayoutResult,
    activeRange: IntRange,
    markerColor: Color,
    thicknessPx: Float,
    offsetPx: Float
) {
    val textLength = layoutResult.layoutInput.text.text.length
    if (textLength <= 0) {
        return
    }
    val safeOffset = activeRange.first.coerceIn(0, textLength - 1)
    val lineIndex = layoutResult.getLineForOffset(safeOffset)
    val lineStart = layoutResult.getLineStart(lineIndex)
    val lineVisibleEndExclusive = layoutResult.getLineEnd(lineIndex, visibleEnd = true)
    if (lineVisibleEndExclusive <= lineStart) {
        return
    }

    val startRect = layoutResult.getBoundingBox(lineStart)
    val endRect = layoutResult.getBoundingBox(lineVisibleEndExclusive - 1)
    val startX = minOf(startRect.left, endRect.right)
    val endX = maxOf(startRect.left, endRect.right)
    val underlineY = (layoutResult.getLineBaseline(lineIndex) + offsetPx)
        .coerceAtMost(size.height - thicknessPx / 2f)

    drawLine(
        color = markerColor,
        start = Offset(startX, underlineY),
        end = Offset(endX, underlineY),
        strokeWidth = thicknessPx,
        cap = StrokeCap.Round
    )
}

private fun buildSkimEntries(document: ReaderDocument): List<SkimEntry> {
    val blocks = document.displayBlocks
    if (blocks.isEmpty()) {
        return emptyList()
    }

    val headingIndices = blocks.indices.filter { blocks[it].type == ReaderBlockType.Heading }
    val paragraphIndices = blocks.indices.filter { blocks[it].type == ReaderBlockType.Paragraph }
    val entries = mutableListOf<SkimEntry>()
    val seen = mutableSetOf<Int>()

    fun add(index: Int, label: String, openBlockIndex: Int = index) {
        if (index !in blocks.indices || !seen.add(index)) {
            return
        }
        entries += SkimEntry(
            openBlockIndex = openBlockIndex.coerceIn(0, blocks.lastIndex),
            blockIndex = index,
            block = blocks[index],
            label = label
        )
    }

    if (headingIndices.isEmpty()) {
        paragraphIndices.take(4).forEachIndexed { position, index ->
            add(index, if (position == 0) "Opening" else "Key passage")
        }
        paragraphIndices.lastOrNull()?.let { add(it, "Closing") }
        return entries.sortedBy { it.blockIndex }
    }

    val firstHeading = headingIndices.first()
    paragraphIndices.filter { it < firstHeading }.take(2).forEachIndexed { position, index ->
        add(index, if (position == 0) "Opening" else "Lead-in")
    }

    headingIndices.forEachIndexed { headingPosition, headingIndex ->
        add(headingIndex, "Heading")
        val nextHeadingIndex = headingIndices.getOrNull(headingPosition + 1) ?: Int.MAX_VALUE
        val sectionParagraphs = paragraphIndices.filter { it > headingIndex && it < nextHeadingIndex }
        sectionParagraphs.firstOrNull()?.let { add(it, "Key passage", openBlockIndex = headingIndex) }
        if (looksLikeConclusionHeading(blocks[headingIndex].text)) {
            sectionParagraphs.getOrNull(1)?.let { add(it, "Closing", openBlockIndex = headingIndex) }
        }
    }

    paragraphIndices.lastOrNull()?.let { lastParagraphIndex ->
        val owningHeading = headingIndices.lastOrNull { it < lastParagraphIndex } ?: lastParagraphIndex
        add(lastParagraphIndex, "Closing", openBlockIndex = owningHeading)
    }

    return entries.sortedBy { it.blockIndex }
}

private fun looksLikeConclusionHeading(text: String): Boolean {
    val normalized = text
        .lowercase()
        .replace(Regex("""[^a-z\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return normalized.contains("conclusion") ||
        normalized.contains("discussion") ||
        normalized.contains("final remarks") ||
        normalized.contains("closing")
}

private fun citationRangesForBlock(text: String): List<IntRange> {
    if (text.isBlank()) {
        return emptyList()
    }

    val ranges = mutableListOf<IntRange>()

    INLINE_NUMERIC_CITATION_REGEX.findAll(text).forEach { match ->
        ranges += match.range
    }

    PARENTHETICAL_CITATION_REGEX.findAll(text).forEach { match ->
        val content = match.groupValues.getOrNull(1).orEmpty()
        if (looksLikeInlineCitation(content)) {
            ranges += match.range
        }
    }

    return ranges
        .distinctBy { it.first to it.last }
        .sortedBy { it.first }
}

private fun bookmarkRangesForBlock(text: String, bookmarks: List<BookmarkEntry>): List<IntRange> {
    return bookmarks
        .mapNotNull { bookmark -> bookmarkRangeForEntry(text, bookmark) }
        .distinctBy { it.first to it.last }
        .sortedBy { it.first }
}

private fun looksLikeInlineCitation(content: String): Boolean {
    val normalized = content.replace(Regex("\\s+"), " ").trim()
    if (!INLINE_CITATION_YEAR_REGEX.containsMatchIn(normalized)) {
        return false
    }

    val lower = normalized.lowercase()
    return lower.contains("et al") ||
        normalized.contains(';') ||
        INLINE_AUTHOR_YEAR_CITATION_REGEX.containsMatchIn(normalized) ||
        INLINE_CITATION_YEAR_REGEX.findAll(normalized).count() >= 2
}

private val INLINE_NUMERIC_CITATION_REGEX =
    Regex("""\[\s*\d+(?:\s*[-,;]\s*\d+|\s*(?:–|—)\s*\d+)*\s*]""")

private val PARENTHETICAL_CITATION_REGEX = Regex("""\(([^()]*)\)""")

private val INLINE_CITATION_YEAR_REGEX = Regex("""\b(?:19|20)\d{2}[a-z]?\b""")

private val INLINE_AUTHOR_YEAR_CITATION_REGEX =
    Regex("""\b[A-Z][A-Za-z'`\-]+(?:\s+(?:and|&)\s+[A-Z][A-Za-z'`\-]+)?(?:\s+et al\.)?,\s*(?:19|20)\d{2}[a-z]?\b""")

private fun bookmarkRangeForEntry(text: String, bookmark: BookmarkEntry): IntRange? {
    if (text.isBlank()) {
        return null
    }

    val start = bookmark.charOffset.coerceIn(0, text.lastIndex)
    val snippet = bookmark.snippet.replace(Regex("\\s+"), " ").trim()
    if (snippet.isNotBlank()) {
        val prefix = snippet.take(96)
        val foundIndex = text.indexOf(prefix, startIndex = start)
        if (foundIndex >= start && foundIndex <= start + 12) {
            val end = (foundIndex + prefix.length - 1).coerceAtMost(text.lastIndex)
            return foundIndex..end
        }
    }

    val sentenceEnd = findSentenceEnd(text, start)
    return start..sentenceEnd
}

private fun findSentenceEnd(text: String, start: Int): Int {
    if (text.isEmpty()) {
        return 0
    }
    var index = start.coerceIn(0, text.lastIndex)
    val hardLimit = (start + 220).coerceAtMost(text.lastIndex)
    while (index < hardLimit) {
        val character = text[index]
        if (character == '.' || character == '!' || character == '?') {
            return index
        }
        index += 1
    }
    return hardLimit
}

private fun findSentenceStart(text: String, anchor: Int): Int {
    if (text.isEmpty()) {
        return 0
    }
    var index = anchor.coerceIn(0, text.lastIndex)
    val hardLimit = (index - 220).coerceAtLeast(0)
    while (index > hardLimit) {
        val previous = text[index - 1]
        if (previous == '.' || previous == '!' || previous == '?' || previous == '\n') {
            break
        }
        index -= 1
    }
    while (index < text.length && text[index].isWhitespace()) {
        index += 1
    }
    return index.coerceIn(0, text.lastIndex)
}

private const val BOOKMARK_OFFSET_MATCH_TOLERANCE = 12

private fun normalizeBookmarkSnippet(snippet: String): String {
    return snippet
        .replace(Regex("\\s+"), " ")
        .trim()
        .lowercase()
}

private fun bookmarkSnippetsLikelyMatch(left: String, right: String): Boolean {
    if (left.isBlank() || right.isBlank()) {
        return false
    }
    val leftPrefix = left.take(48)
    val rightPrefix = right.take(48)
    if (leftPrefix.length < 16 || rightPrefix.length < 16) {
        return false
    }
    return leftPrefix == rightPrefix ||
        leftPrefix.startsWith(rightPrefix) ||
        rightPrefix.startsWith(leftPrefix)
}

private fun documentFirstAuthor(document: ReaderDocument): String? {
    return document.metadataBlocks
        .asSequence()
        .map { it.text.replace(Regex("\\s+"), " ").trim() }
        .mapNotNull(::extractFirstAuthorFromMetadata)
        .firstOrNull()
}

private fun extractFirstAuthorFromMetadata(metadataText: String): String? {
    val normalized = metadataText.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) {
        return null
    }

    val byline = Regex("""^by\s+(.+)$""", RegexOption.IGNORE_CASE)
        .matchEntire(normalized)
        ?.groupValues
        ?.getOrNull(1)
        ?.trim()
    if (!byline.isNullOrBlank()) {
        return splitFirstAuthor(byline)
    }

    val lower = normalized.lowercase()
    if (
        listOf(
            "@",
            "university",
            "institute",
            "department",
            "school of",
            "faculty",
            "laboratory",
            "center for",
            "centre for",
            "published",
            "doi",
            "arxiv",
            "preprint",
            "abstract",
            "keywords",
            "copyright"
        ).any(lower::contains)
    ) {
        return null
    }

    val firstAuthor = splitFirstAuthor(normalized)
    return firstAuthor?.takeIf(::looksLikePersonalName)
}

private fun splitFirstAuthor(text: String): String? {
    return text
        .replace(Regex("""\bet al\.\b""", RegexOption.IGNORE_CASE), "")
        .split(Regex("""\s*(?:,|;|\band\b|&)\s*""", RegexOption.IGNORE_CASE))
        .asSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
}

private fun looksLikePersonalName(text: String): Boolean {
    val clean = text.trim()
    if (clean.length !in 4..60 || clean.contains('@') || clean.contains(':')) {
        return false
    }

    val words = clean.split(Regex("""\s+"""))
    if (words.size !in 2..5) {
        return false
    }

    return words.all { word ->
        val normalized = word.trim('.', ',', ';')
        normalized.isNotBlank() && normalized.firstOrNull()?.isUpperCase() == true
    }
}

private fun documentListPrefixCount(document: ReaderDocument): Int {
    return if (document.metadataBlocks.isNotEmpty()) 1 else 0
}

private fun documentFootnotesIndex(document: ReaderDocument): Int {
    return documentListPrefixCount(document) + document.displayBlocks.size
}

private data class BookmarkTarget(
    val blockIndex: Int,
    val charOffset: Int,
    val snippet: String
)

private fun findMatchingBookmark(
    bookmarks: List<BookmarkEntry>,
    sourceLabel: String,
    target: BookmarkTarget
): BookmarkEntry? {
    val normalizedTargetSnippet = normalizeBookmarkSnippet(target.snippet)
    return bookmarks
        .asSequence()
        .filter { it.sourceLabel == sourceLabel && it.blockIndex == target.blockIndex }
        .filter { bookmark ->
            kotlin.math.abs(bookmark.charOffset - target.charOffset) <= BOOKMARK_OFFSET_MATCH_TOLERANCE ||
                bookmarkSnippetsLikelyMatch(normalizeBookmarkSnippet(bookmark.snippet), normalizedTargetSnippet)
        }
        .minByOrNull { bookmark -> kotlin.math.abs(bookmark.charOffset - target.charOffset) }
}

private fun buildBookmarkTarget(
    document: ReaderDocument,
    fallbackVisibleAbsoluteIndex: Int,
    currentSpeechBlockIndex: Int,
    currentSpeechRange: IntRange?
): BookmarkTarget? {
    if (currentSpeechBlockIndex in document.displayBlocks.indices && currentSpeechRange != null) {
        val blockText = document.displayBlocks[currentSpeechBlockIndex].text
        val anchorOffset = currentSpeechRange.first.coerceIn(0, blockText.lastIndex.coerceAtLeast(0))
        val segment = ReaderPlaybackStore.segmentForBlockAndOffset(currentSpeechBlockIndex, anchorOffset)
        val start = (segment?.startOffset ?: findSentenceStart(blockText, anchorOffset))
            .coerceIn(0, blockText.length)
        val endExclusive = (segment?.endOffset ?: (findSentenceEnd(blockText, start) + 1))
            .coerceIn(start, blockText.length)
        val snippet = blockText.substring(start, endExclusive).replace(Regex("\\s+"), " ").trim()
        return BookmarkTarget(
            blockIndex = currentSpeechBlockIndex,
            charOffset = start,
            snippet = snippet.ifBlank { blockText.take(140) }
        )
    }

    val relativeIndex = fallbackVisibleAbsoluteIndex - documentListPrefixCount(document)
    if (relativeIndex !in document.displayBlocks.indices) {
        return null
    }
    val blockText = document.displayBlocks[relativeIndex].text
    return BookmarkTarget(
        blockIndex = relativeIndex,
        charOffset = 0,
        snippet = blockText.replace(Regex("\\s+"), " ").trim().take(140)
    )
}

private fun buildBookmarkTargetForOffset(
    document: ReaderDocument,
    blockIndex: Int,
    charOffset: Int
): BookmarkTarget? {
    val block = document.displayBlocks.getOrNull(blockIndex) ?: return null
    val blockText = block.text
    if (blockText.isBlank()) {
        return null
    }

    val segment = ReaderPlaybackStore.segmentForBlockAndOffset(blockIndex, charOffset)
    val start = (segment?.startOffset ?: findSentenceStart(blockText, charOffset))
        .coerceIn(0, blockText.lastIndex.coerceAtLeast(0))
    val endExclusive = (segment?.endOffset ?: (findSentenceEnd(blockText, start) + 1))
        .coerceIn(start, blockText.length)
    val snippet = blockText.substring(start, endExclusive)
        .replace(Regex("\\s+"), " ")
        .trim()

    return BookmarkTarget(
        blockIndex = blockIndex,
        charOffset = start,
        snippet = snippet.ifBlank { blockText.replace(Regex("\\s+"), " ").trim().take(140) }
    )
}

@Composable
private fun ReaderOptionsSection(
    ttsController: PdfTtsController,
    cleanupController: ReaderCleanupController,
    accessibilityServiceEnabled: Boolean,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenSpeechSettings: () -> Unit,
    onChooseCleanupModelFolder: () -> Unit,
    onRefreshCleanupModelFolder: () -> Unit,
    onClearCleanupModelCache: () -> Unit
) {
    val context = LocalContext.current
    var voicePickerOpen by rememberSaveable { mutableStateOf(false) }
    val selectedVoiceOption = ttsController.availableVoices.firstOrNull { it.name == ttsController.selectedVoiceName }
    val currentVoiceLanguage = selectedVoiceOption?.languageLabel
        ?.takeIf { it.isNotBlank() }
        ?: java.util.Locale.getDefault().displayName.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
        }

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val compactOptionsLayout = maxWidth < 380.dp

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            Text(
                stringResource(R.string.options_title),
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                stringResource(R.string.options_description),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.options_accessibility_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (accessibilityServiceEnabled) {
                                stringResource(R.string.options_accessibility_enabled)
                            } else {
                                stringResource(R.string.options_accessibility_disabled)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(
                        if (accessibilityServiceEnabled) {
                            stringResource(R.string.options_accessibility_enabled_hint)
                        } else {
                            stringResource(R.string.options_accessibility_disabled_hint)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    TextButton(
                        onClick = onOpenAccessibilitySettings,
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(stringResource(R.string.options_open_accessibility_settings))
                    }
                }
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        stringResource(R.string.options_cleanup_title),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        stringResource(R.string.options_cleanup_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                stringResource(R.string.options_cleanup_toggle_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                if (cleanupController.isEnabled) {
                                    stringResource(R.string.options_cleanup_enabled_detail)
                                } else {
                                    stringResource(R.string.options_cleanup_disabled_detail)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = cleanupController.isEnabled,
                            onCheckedChange = cleanupController::setEnabled
                        )
                    }

                    HorizontalDivider()
                    CleanupModelPickerSection(
                        context = context,
                        cleanupController = cleanupController,
                        compactOptionsLayout = compactOptionsLayout,
                        onChooseCleanupModelFolder = onChooseCleanupModelFolder,
                        onRefreshCleanupModelFolder = onRefreshCleanupModelFolder,
                        onClearCleanupModelCache = onClearCleanupModelCache
                    )

                    if (cleanupController.selectedModelSupportsAcceleration) {
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.options_cleanup_acceleration_title),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            stringResource(R.string.options_cleanup_acceleration_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                stringResource(R.string.options_voice_engine_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                ttsController.currentEngineLabel
                                    ?: stringResource(R.string.options_voice_engine_unknown),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                stringResource(R.string.options_voice_system_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                stringResource(R.string.options_voice_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                ttsController.selectedVoiceLabel
                                    ?: stringResource(R.string.options_voice_default),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                currentVoiceLanguage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    if (ttsController.isVoiceLoading) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text(
                                stringResource(R.string.options_loading_voices),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else if (ttsController.availableVoices.isEmpty()) {
                        Text(
                            stringResource(R.string.options_no_voices),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (compactOptionsLayout) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = { voicePickerOpen = true },
                                enabled = !ttsController.isVoiceLoading && ttsController.availableVoices.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.options_voice_choose))
                            }
                            TextButton(
                                onClick = onOpenSpeechSettings,
                                modifier = Modifier.align(Alignment.Start)
                            ) {
                                Text(stringResource(R.string.options_open_system_speech))
                            }
                        }
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { voicePickerOpen = true },
                                enabled = !ttsController.isVoiceLoading && ttsController.availableVoices.isNotEmpty()
                            ) {
                                Text(stringResource(R.string.options_voice_choose))
                            }
                            TextButton(onClick = onOpenSpeechSettings) {
                                Text(stringResource(R.string.options_open_system_speech))
                            }
                        }
                    }
                }
            }
            }
        }
    }

    if (voicePickerOpen) {
        AlertDialog(
            onDismissRequest = { voicePickerOpen = false },
            title = {
                Text(stringResource(R.string.options_voice_picker_title))
            },
            text = {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                ) {
                    item {
                        VoiceOptionRow(
                            title = stringResource(R.string.options_voice_default),
                            detail = stringResource(R.string.options_voice_default_detail),
                            selected = ttsController.selectedVoiceName == null,
                            onClick = {
                                ttsController.selectVoice(null)
                                voicePickerOpen = false
                            }
                        )
                    }
                    items(ttsController.availableVoices.size) { index ->
                        val voice = ttsController.availableVoices[index]
                        VoiceOptionRow(
                            title = voice.label,
                            detail = voice.detail,
                            selected = ttsController.selectedVoiceName == voice.name,
                            onClick = {
                                ttsController.selectVoice(voice.name)
                                voicePickerOpen = false
                            }
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { voicePickerOpen = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun VoiceOptionRow(
    title: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CleanupModelPickerSection(
    context: android.content.Context,
    cleanupController: ReaderCleanupController,
    compactOptionsLayout: Boolean,
    onChooseCleanupModelFolder: () -> Unit,
    onRefreshCleanupModelFolder: () -> Unit,
    onClearCleanupModelCache: () -> Unit
) {
    val selectedInfo = cleanupController.installedModelInfo
    val installedModelInfos = cleanupController.installedModelInfos
    var modelListExpanded by rememberSaveable(installedModelInfos.size) {
        mutableStateOf(installedModelInfos.isEmpty())
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.options_cleanup_model_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        CleanupModelFolderCard(
            folderLabel = cleanupController.sharedModelFolderLabel
                ?: stringResource(R.string.options_cleanup_model_folder_not_selected),
            hasSharedModelFolder = cleanupController.hasSharedModelFolder,
            cacheFileCount = cleanupController.executionCacheFileCount,
            compactOptionsLayout = compactOptionsLayout,
            onChooseCleanupModelFolder = onChooseCleanupModelFolder,
            onRefreshCleanupModelFolder = onRefreshCleanupModelFolder,
            onClearCleanupModelCache = onClearCleanupModelCache
        )

        CleanupSelectedModelCard(
            title = selectedInfo?.let(cleanupController::modelRowTitle)
                ?: stringResource(R.string.options_cleanup_model_not_installed),
            detail = selectedInfo?.let { cleanupController.modelRowDetail(context, it) }
                ?: stringResource(R.string.options_cleanup_model_choose_folder_hint)
        )

        cleanupController.compatibilityHint?.let { hint ->
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
            ) {
                Text(
                    hint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        if (installedModelInfos.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                stringResource(R.string.options_cleanup_model_selector_title),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                stringResource(
                                    R.string.options_cleanup_model_selector_count,
                                    installedModelInfos.size
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { modelListExpanded = !modelListExpanded }
                        ) {
                            Icon(
                                imageVector = if (modelListExpanded) {
                                    Icons.Rounded.ExpandLess
                                } else {
                                    Icons.Rounded.ExpandMore
                                },
                                contentDescription = if (modelListExpanded) {
                                    stringResource(R.string.options_cleanup_model_collapse)
                                } else {
                                    stringResource(R.string.options_cleanup_model_expand)
                                }
                            )
                        }
                    }

                    if (modelListExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            installedModelInfos.forEach { modelInfo ->
                                CleanupModelOptionCard(
                                    title = cleanupController.modelRowTitle(modelInfo),
                                    detail = cleanupController.modelRowDetail(context, modelInfo),
                                    selected = cleanupController.selectedInstalledModelId == modelInfo.modelId,
                                    enabled = cleanupController.isCompatibleInstalledModel(modelInfo),
                                    onClick = { cleanupController.selectInstalledModel(modelInfo.modelId) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanupModelFolderCard(
    folderLabel: String,
    hasSharedModelFolder: Boolean,
    cacheFileCount: Int,
    compactOptionsLayout: Boolean,
    onChooseCleanupModelFolder: () -> Unit,
    onRefreshCleanupModelFolder: () -> Unit,
    onClearCleanupModelCache: () -> Unit
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                stringResource(R.string.options_cleanup_model_folder_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                folderLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                stringResource(R.string.options_cleanup_model_cache_detail, cacheFileCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (compactOptionsLayout) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onChooseCleanupModelFolder,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.options_cleanup_model_choose_folder))
                    }
                    if (hasSharedModelFolder) {
                        OutlinedButton(
                            onClick = onRefreshCleanupModelFolder,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.options_cleanup_model_refresh_folder))
                        }
                    }
                    OutlinedButton(
                        onClick = onClearCleanupModelCache,
                        enabled = cacheFileCount > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.options_cleanup_model_clear_cache))
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onChooseCleanupModelFolder,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.options_cleanup_model_choose_folder))
                    }
                    if (hasSharedModelFolder) {
                        OutlinedButton(
                            onClick = onRefreshCleanupModelFolder,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.options_cleanup_model_refresh_folder))
                        }
                    }
                }
                OutlinedButton(
                    onClick = onClearCleanupModelCache,
                    enabled = cacheFileCount > 0
                ) {
                    Text(stringResource(R.string.options_cleanup_model_clear_cache))
                }
            }
        }
    }
}

@Composable
private fun CleanupSelectedModelCard(
    title: String,
    detail: String
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CleanupModelOptionCard(
    title: String,
    detail: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val containerColor = when {
        selected -> MaterialTheme.colorScheme.secondaryContainer
        !enabled -> MaterialTheme.colorScheme.surfaceContainerLow
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val detailColor = when {
        selected -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.82f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = contentColor
                )
            } else {
                RadioButton(
                    selected = false,
                    onClick = onClick,
                    enabled = enabled
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = detailColor,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun rememberPdfTtsController(): PdfTtsController {
    val context = LocalContext.current.applicationContext
    val controller = remember { PdfTtsController(context) }
    DisposableEffect(controller) {
        onDispose { controller.release() }
    }
    return controller
}

@Composable
private fun rememberReaderCleanupController(): ReaderCleanupController {
    val context = LocalContext.current.applicationContext
    return remember { ReaderCleanupController(context) }
}

private class ReaderCleanupController(context: android.content.Context) {
    private val appContext = context.applicationContext
    private val settingsRepository = ReaderCleanupSettingsRepository(appContext)
    private val modelRepository = LocalCleanupModelRepository(appContext)

    var mode by mutableStateOf(settingsRepository.cleanupMode())
        private set

    var installedModelInfos by mutableStateOf(currentInstalledModelInfos())
        private set

    var selectedInstalledModelId by mutableStateOf(currentSelectedInstalledModelId())
        private set

    var installedModelInfo by mutableStateOf(currentInstalledModelInfo())
        private set

    var sharedModelFolderLabel by mutableStateOf(modelRepository.sharedModelFolderLabel())
        private set

    var hasSharedModelFolder by mutableStateOf(modelRepository.hasSharedModelFolder())
        private set

    var compatibilityHint by mutableStateOf(currentCompatibilityHint())
        private set

    var diagnosticsEnabled by mutableStateOf(settingsRepository.cleanupDiagnosticsEnabled())
        private set

    var executionCacheFileCount by mutableStateOf(modelRepository.executionCacheFileCount())
        private set

    val isEnabled: Boolean
        get() = mode != ReaderCleanupMode.Off

    val selectedModelSupportsAcceleration: Boolean
        get() = currentInstalledModelInfo()
            ?.let(::discoverCleanupModel)
            ?.runtimeSpec
            ?.backendKind == CleanupBackendKind.LiteRtLm

    init {
        normalizeAccelerationMode()
        reconcileSelectedInstalledModelFileSelection()
        refresh()
    }

    fun refresh() {
        normalizeAccelerationMode()
        mode = settingsRepository.cleanupMode()
        hasSharedModelFolder = modelRepository.hasSharedModelFolder()
        sharedModelFolderLabel = modelRepository.sharedModelFolderLabel()
        installedModelInfos = currentInstalledModelInfos()
        reconcileSelectedInstalledModelFileSelection()
        selectedInstalledModelId = currentSelectedInstalledModelId()
        installedModelInfo = currentInstalledModelInfo()
        compatibilityHint = currentCompatibilityHint()
        diagnosticsEnabled = settingsRepository.cleanupDiagnosticsEnabled()
        executionCacheFileCount = modelRepository.executionCacheFileCount()
    }

    fun selectMode(mode: ReaderCleanupMode) {
        settingsRepository.saveCleanupMode(mode)
        this.mode = mode
    }

    fun setEnabled(enabled: Boolean) {
        selectMode(if (enabled) ReaderCleanupMode.WebAndPdf else ReaderCleanupMode.Off)
        refresh()
    }

    fun updateDiagnosticsEnabled(enabled: Boolean) {
        settingsRepository.saveCleanupDiagnosticsEnabled(enabled)
        diagnosticsEnabled = enabled
    }

    fun selectAccelerationMode(mode: ReaderCleanupAccelerationMode) {
        settingsRepository.saveCleanupAccelerationMode(ReaderCleanupAccelerationMode.Cpu)
    }

    fun selectInstalledModel(modelId: String) {
        val requestedInfo = modelRepository.installedModelInfo(modelId)
            ?: return
        if (!isCompatibleInstalledModel(requestedInfo)) {
            refresh()
            return
        }
        settingsRepository.saveSelectedModelId(modelId)
        refresh()
    }

    fun selectSharedModelFolder(
        contentResolver: android.content.ContentResolver,
        uri: Uri
    ): String {
        return try {
            modelRepository.selectSharedModelFolder(contentResolver, uri)
            refresh()
            appContext.getString(
                R.string.message_cleanup_model_folder_selected,
                sharedModelFolderLabel ?: appContext.getString(R.string.diagnostics_value_none)
            )
        } catch (error: Throwable) {
            error.message ?: appContext.getString(R.string.message_cleanup_model_folder_failed)
        }
    }

    fun refreshModelFolder(): String {
        modelRepository.refreshInstalledModelInfos()
        refresh()
        return if (hasSharedModelFolder) {
            appContext.getString(
                R.string.message_cleanup_model_folder_refreshed,
                sharedModelFolderLabel ?: appContext.getString(R.string.diagnostics_value_none)
            )
        } else {
            appContext.getString(R.string.message_cleanup_model_folder_not_selected_short)
        }
    }

    fun clearModelExecutionCache(): String {
        val removed = modelRepository.clearExecutionCache()
        refresh()
        return if (removed > 0) {
            appContext.getString(R.string.message_cleanup_model_cache_cleared, removed)
        } else {
            appContext.getString(R.string.message_cleanup_model_cache_empty)
        }
    }

    private fun currentInstalledModelInfos(): List<InstalledCleanupModelInfo> {
        return modelRepository.listInstalledModelInfos()
    }

    private fun currentSelectedInstalledModelId(): String? {
        return resolveInstalledCleanupModel(
            installedInfos = currentInstalledModelInfos(),
            preferredModelId = settingsRepository.selectedModelId()
        )?.info?.modelId
    }

    private fun currentInstalledModelInfo(): InstalledCleanupModelInfo? {
        return resolveInstalledCleanupModel(
            installedInfos = currentInstalledModelInfos(),
            preferredModelId = selectedInstalledModelId
        )?.info
    }

    private fun currentCompatibilityHint(): String? {
        if (!hasSharedModelFolder) {
            return appContext.getString(R.string.options_cleanup_model_choose_folder_hint)
        }
        val available = currentInstalledModelInfos()
        val compatible = available.filter(::isCompatibleInstalledModel)
        return when {
            available.isEmpty() ->
                appContext.getString(R.string.options_cleanup_model_folder_empty_hint)
            compatible.isEmpty() ->
                appContext.getString(R.string.options_cleanup_model_incompatible_files_hint)
            available.any { !isCompatibleInstalledModel(it) } ->
                appContext.getString(R.string.options_cleanup_model_incompatible_files_hint)
            else -> null
        }
    }

    private fun normalizeAccelerationMode() {
        if (settingsRepository.cleanupAccelerationMode() != ReaderCleanupAccelerationMode.Cpu) {
            settingsRepository.saveCleanupAccelerationMode(ReaderCleanupAccelerationMode.Cpu)
        }
    }

    private fun reconcileSelectedInstalledModelFileSelection() {
        val resolved = currentSelectedInstalledModelId()
        val currentSaved = settingsRepository.selectedModelId()
        if (currentSaved != resolved) {
            settingsRepository.saveSelectedModelId(resolved)
        }
    }

    fun isCompatibleInstalledModel(info: InstalledCleanupModelInfo): Boolean {
        return discoverCleanupModel(info) != null
    }

    fun modelRowDetail(
        context: android.content.Context,
        info: InstalledCleanupModelInfo
    ): String {
        val status = discoverCleanupModel(info)?.let { discovered ->
            when (discovered.runtimeSpec.backendKind) {
                CleanupBackendKind.LiteRtLm -> context.getString(R.string.options_cleanup_model_runtime_litertlm)
                CleanupBackendKind.MediaPipeTask -> context.getString(R.string.options_cleanup_model_runtime_task)
            }
        } ?: context.getString(R.string.options_cleanup_model_runtime_stored_only)
        return context.getString(
            R.string.options_cleanup_model_file_detail,
            info.fileName,
            Formatter.formatFileSize(context, info.sizeBytes),
            status
        )
    }

    fun modelRowTitle(info: InstalledCleanupModelInfo): String {
        return discoverCleanupModel(info)?.displayName ?: cleanupModelDisplayName(info.fileName)
    }
}

private class PdfTtsController(context: android.content.Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val speechSettingsRepository = SpeechSettingsRepository(appContext)
    private var currentDocumentId: String? = null
    private var voiceProbeTts: TextToSpeech? = null

    var isReady by mutableStateOf(false)
        private set

    var isSpeaking by mutableStateOf(false)
        private set

    var hasSegments by mutableStateOf(false)
        private set

    var speed by mutableStateOf(speechSettingsRepository.selectedSpeed())
        private set

    var statusMessage by mutableStateOf<String?>(null)
        private set

    var currentBlockIndex by mutableStateOf(-1)
        private set

    var currentSegmentRange by mutableStateOf<IntRange?>(null)
        private set

    var manualNavigationVersion by mutableStateOf(0L)
        private set

    val speedLabel: String
        get() = String.format(java.util.Locale.US, "%.1fx", speed)

    var progressFraction by mutableStateOf(0f)
        private set

    var progressLabel by mutableStateOf("No listening position")
        private set

    var availableVoices by mutableStateOf<List<SpeechVoiceOption>>(emptyList())
        private set

    var selectedVoiceName by mutableStateOf(speechSettingsRepository.selectedVoiceName())
        private set

    var currentEngineLabel by mutableStateOf<String?>(null)
        private set

    var isVoiceLoading by mutableStateOf(true)
        private set

    var playbackDocumentId by mutableStateOf<String?>(null)
        private set

    val selectedVoiceLabel: String?
        get() = if (selectedVoiceName == null) null else {
            availableVoices.firstOrNull { it.name == selectedVoiceName }?.label
        }

    init {
        scope.launch {
            ReaderPlaybackStore.uiState.collectLatest { state ->
                playbackDocumentId = state.currentDocumentId
                isReady = state.isReady
                isSpeaking = state.isSpeaking
                hasSegments = state.hasSegments
                speed = state.speed
                statusMessage = state.statusMessage
                currentBlockIndex = state.currentBlockIndex
                currentSegmentRange = state.currentSegmentRange
                manualNavigationVersion = state.manualNavigationVersion
                progressFraction = ReaderPlaybackStore.progressFraction()
                progressLabel = ReaderPlaybackStore.progressLabel()
            }
        }
        refreshVoices()
    }

    fun loadDocument(documentId: String, title: String, blocks: List<ReaderBlock>) {
        if (currentDocumentId == documentId && playbackDocumentId == documentId && hasSegments) {
            return
        }

        if (currentDocumentId != null && currentDocumentId != documentId && isSpeaking) {
            ReaderPlaybackService.pause(appContext)
        }

        currentDocumentId = documentId
        ReaderPlaybackStore.loadDocument(documentId, title, blocks)
    }

    private fun ensureLoadedDocument(document: ReaderDocument) {
        loadDocument(document.sourceLabel, document.title, document.displayBlocks)
    }

    fun speak(document: ReaderDocument) {
        if (document.displayBlocks.isEmpty()) {
            statusMessage = appContext.getString(R.string.message_nothing_readable_to_play)
            return
        }
        ensureLoadedDocument(document)
        ReaderPlaybackService.play(appContext)
    }

    fun speakFromBlockOffset(document: ReaderDocument, blockIndex: Int, charOffset: Int) {
        ensureLoadedDocument(document)
        ReaderPlaybackService.seekToSegment(
            context = appContext,
            index = ReaderPlaybackStore.indexForBlockAndOffset(blockIndex, charOffset)
        )
    }

    fun seekToFraction(document: ReaderDocument, fraction: Float) {
        ensureLoadedDocument(document)
        ReaderPlaybackService.seekToFraction(appContext, fraction)
    }

    fun stop() {
        ReaderPlaybackService.pause(appContext)
    }

    fun skipNext(document: ReaderDocument) {
        ensureLoadedDocument(document)
        ReaderPlaybackService.skipNext(appContext)
    }

    fun skipPrevious(document: ReaderDocument) {
        ensureLoadedDocument(document)
        ReaderPlaybackService.skipPrevious(appContext)
    }

    fun slower() {
        ReaderPlaybackService.setSpeed(appContext, normalizeReaderSpeed(speed - 0.1f))
    }

    fun faster() {
        ReaderPlaybackService.setSpeed(appContext, normalizeReaderSpeed(speed + 0.1f))
    }

    fun clearStatus() {
        ReaderPlaybackStore.clearStatus()
    }

    fun refreshVoices() {
        isVoiceLoading = true
        val previousProbe = voiceProbeTts
        voiceProbeTts = null
        previousProbe?.shutdown()

        voiceProbeTts = TextToSpeech(appContext) { status ->
            val engine = voiceProbeTts
            if (status != TextToSpeech.SUCCESS || engine == null) {
                scope.launch {
                    isVoiceLoading = false
                    currentEngineLabel = null
                    availableVoices = emptyList()
                }
                return@TextToSpeech
            }

            scope.launch {
                runCatching {
                    currentEngineLabel = resolveTtsEngineLabel(engine.defaultEngine)
                    engine.setLanguage(java.util.Locale.getDefault())
                    val voices = engine.voices.orEmpty()
                        .filterNot { it.isNetworkConnectionRequired }
                        .sortedWith(
                            compareBy(
                                { voice ->
                                    voiceSortBucket(voice)
                                },
                                { it.locale?.displayName.orEmpty() },
                                { it.name }
                            )
                        )
                        .map { it.toSpeechVoiceOption() }
                    availableVoices = voices
                    selectedVoiceName = speechSettingsRepository.selectedVoiceName()
                        ?.takeIf { savedName -> voices.any { it.name == savedName } }
                }.onFailure {
                    currentEngineLabel = null
                    availableVoices = emptyList()
                }

                isVoiceLoading = false
            }
        }
    }

    fun selectVoice(voiceName: String?) {
        speechSettingsRepository.saveSelectedVoiceName(voiceName)
        selectedVoiceName = voiceName
        ReaderPlaybackService.setVoice(appContext, voiceName)
        statusMessage = if (voiceName == null) {
            appContext.getString(R.string.message_voice_default_selected)
        } else {
            appContext.getString(R.string.message_voice_selected)
        }
    }

    fun release() {
        voiceProbeTts?.shutdown()
        voiceProbeTts = null
        scope.cancel()
    }

    private fun resolveTtsEngineLabel(packageName: String?): String? {
        val normalizedPackage = packageName?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val packageManager = appContext.packageManager
            val applicationInfo = packageManager.getApplicationInfo(normalizedPackage, 0)
            packageManager.getApplicationLabel(applicationInfo).toString()
        }.getOrElse { normalizedPackage }
    }

    private fun voiceSortBucket(voice: android.speech.tts.Voice): Int {
        val locale = voice.locale ?: return 3
        val language = locale.language.orEmpty().lowercase()
        val country = locale.country.orEmpty().uppercase()
        return when {
            language == "en" && country == "US" -> 0
            language == "en" -> 1
            else -> 2
        }
    }
}

private fun openSpeechSettings(context: android.content.Context) {
    val intents = listOf(
        Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA),
        Intent("com.android.settings.TTS_SETTINGS")
    )

    for (intent in intents) {
        runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        }.onFailure {
            if (it !is ActivityNotFoundException) {
                return
            }
        }
    }
}

private fun openAccessibilitySettings(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun documentWebLinkUrl(sourceLabel: String?): String? {
    val parsed = sourceLabel
        ?.takeIf { it.isNotBlank() }
        ?.let(Uri::parse)
        ?: return null
    return parsed.toString().takeIf {
        parsed.scheme.equals("http", ignoreCase = true) ||
            parsed.scheme.equals("https", ignoreCase = true)
    }
}

private fun openDocumentWebLink(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

internal fun isReadAccessibilityServiceEnabled(context: android.content.Context): Boolean {
    val expected = ComponentName(context, ReadAccessibilityService::class.java)
        .flattenToString()
        .lowercase(Locale.US)
    val enabledServices = Settings.Secure
        .getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        .orEmpty()

    return enabledServices
        .split(':')
        .any { it.equals(expected, ignoreCase = true) }
}
