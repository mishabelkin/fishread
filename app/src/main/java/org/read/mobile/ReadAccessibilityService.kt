package org.read.mobile

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs

internal fun shouldResumeExistingReaderSession(state: PlaybackUiState): Boolean {
    val documentId = state.currentDocumentId?.trim().orEmpty()
    if (!state.hasSegments || documentId.isBlank()) {
        return false
    }

    return state.isSpeaking
}

internal fun looksLikePdfSessionDocumentId(documentId: String): Boolean {
    val lower = documentId.trim().lowercase(Locale.US)
    return lower.startsWith("content://") ||
        lower.startsWith("file://") ||
        lower.endsWith(".pdf") ||
        lower.contains("/pdf/")
}

internal fun looksLikeBrowserOpenableUrl(url: String): Boolean {
    val normalized = url.trim()
    if (normalized.isBlank()) {
        return false
    }
    val lower = normalized.lowercase(Locale.US)
    return lower.startsWith("http://") || lower.startsWith("https://")
}

internal fun shouldReopenReaderForCurrentPdfUrl(
    state: PlaybackUiState,
    currentPdfUrl: String
): Boolean {
    if (!state.hasSegments) {
        return false
    }

    val currentDocumentId = state.currentDocumentId?.trim().orEmpty()
    if (currentDocumentId.isBlank()) {
        return false
    }

    if (state.isSpeaking) {
        return true
    }

    return normalizePdfDocumentIdentity(currentDocumentId) == normalizePdfDocumentIdentity(currentPdfUrl)
}

internal fun shouldReopenReaderForCurrentUrl(
    state: PlaybackUiState,
    currentUrl: String
): Boolean {
    if (!state.hasSegments) {
        return false
    }

    val currentDocumentId = state.currentDocumentId?.trim().orEmpty()
    if (currentDocumentId.isBlank()) {
        return false
    }

    if (state.isSpeaking) {
        return true
    }

    return normalizeBrowserDocumentIdentity(currentDocumentId) == normalizeBrowserDocumentIdentity(currentUrl)
}

internal fun normalizeBrowserDocumentIdentity(documentId: String): String {
    val normalized = documentId.trim()
    val fragmentStripped = normalized.substringBefore('#').trim()
    return runCatching {
        val uri = android.net.Uri.parse(fragmentStripped)
        val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
        val host = uri.host?.lowercase(Locale.US).orEmpty()
        if ((scheme == "http" || scheme == "https") && host.isNotBlank()) {
            buildString {
                append(scheme)
                append("://")
                append(host)
                val path = uri.encodedPath.orEmpty().trimEnd('/')
                if (path.isNotBlank()) {
                    append(path)
                }
                uri.encodedQuery?.takeIf { it.isNotBlank() }?.let {
                    append('?')
                    append(it)
                }
            }
        } else {
            fragmentStripped.trimEnd('/').lowercase(Locale.US)
        }
    }.getOrElse {
        fragmentStripped.trimEnd('/').lowercase(Locale.US)
    }
}

internal fun normalizePdfDocumentIdentity(documentId: String): String {
    return normalizeBrowserDocumentIdentity(documentId)
}

internal data class AccessibilityUrlCandidate(
    val url: String,
    val score: Int
)

internal fun normalizeAccessibilityUrlCandidate(text: String): List<String> {
    val normalized = text
        .replace('\u00A0', ' ')
        .trim()
    if (normalized.isBlank()) {
        return emptyList()
    }

    val fullUrlMatches = Regex("""https?://[^\s<>"'()]+""", RegexOption.IGNORE_CASE)
        .findAll(normalized)
        .map { sanitizeAccessibilityUrl(it.value) }
        .filter { looksLikeBrowserOpenableUrl(it) }
        .toList()
    if (fullUrlMatches.isNotEmpty()) {
        return fullUrlMatches
    }

    val bareUrlMatches = Regex(
        """\b(?:[a-z0-9-]+\.)+[a-z]{2,}(?:/[^\s<>"']*)?""",
        RegexOption.IGNORE_CASE
    ).findAll(normalized)
        .map { "https://${sanitizeAccessibilityUrl(it.value)}" }
        .filter { looksLikeBrowserOpenableUrl(it) }
        .toList()

    return bareUrlMatches
}

internal fun chooseBestAccessibilityUrlCandidate(candidates: List<AccessibilityUrlCandidate>): String? {
    return candidates
        .filter { looksLikeBrowserOpenableUrl(it.url) }
        .maxWithOrNull(
            compareBy<AccessibilityUrlCandidate> { it.score }
                .thenBy { it.url.length }
        )
        ?.url
}

internal fun chooseBestAccessibilityPdfUrlCandidate(candidates: List<AccessibilityUrlCandidate>): String? {
    return chooseBestAccessibilityUrlCandidate(candidates)
        ?.takeIf(::looksLikePdfSessionDocumentId)
}

private fun sanitizeAccessibilityUrl(url: String): String {
    return url.trim().trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
}

class ReadAccessibilityService : AccessibilityService() {
    companion object {
        private const val MAX_AUTO_SCROLL_STEPS = 10
        private const val MAX_STALLED_SCROLL_STEPS = 2
        private const val SCROLL_SETTLE_DELAY_MS = 650L
        private const val NEXT_SCROLL_DELAY_MS = 180L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null
    private var activeCaptureSession: AccessibilityCaptureSession? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            eventTypes = 0
            notificationTimeout = 0
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
        }
        registerAccessibilityButtonCallbackIfSupported()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        unregisterAccessibilityButtonCallbackIfSupported()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        unregisterAccessibilityButtonCallbackIfSupported()
        return super.onUnbind(intent)
    }

    private fun captureActiveWindowIntoRead() {
        if (activeCaptureSession != null) {
            showToast(getString(R.string.accessibility_capture_in_progress))
            return
        }

        val root = rootInActiveWindow
        if (root?.packageName?.toString() == packageName) {
            startActivity(ReaderAccessibilityIntents.createBackgroundReaderIntent(this))
            return
        }

        val playbackState = ReaderPlaybackStore.uiState.value

        if (root == null) {
            if (shouldResumeExistingReaderSession(playbackState)) {
                startActivity(ReaderAccessibilityIntents.createOpenReaderIntent(this))
                return
            }
            showToast(getString(R.string.accessibility_capture_no_text))
            return
        }

        findBrowserCurrentUrl(root)?.let { currentUrl ->
            if (shouldReopenReaderForCurrentUrl(playbackState, currentUrl)) {
                startActivity(ReaderAccessibilityIntents.createOpenReaderIntent(this))
            } else {
                startActivity(ReaderAccessibilityIntents.createOpenUrlIntent(this, currentUrl))
            }
            showToast(getString(R.string.accessibility_capture_opened))
            return
        }

        if (shouldResumeExistingReaderSession(playbackState)) {
            startActivity(ReaderAccessibilityIntents.createOpenReaderIntent(this))
            return
        }

        val session = AccessibilityCaptureSession()
        val windowSummary = collectCurrentWindowBlocks(session, root)
        val initialScrollTarget = findBestScrollableNode(root)
        if (windowSummary.newBlockCount == 0 && initialScrollTarget == null) {
            showToast(getString(R.string.accessibility_capture_no_text))
            return
        }

        initialScrollTarget?.recycle()
        if (windowSummary.alreadyExposedEnough) {
            finishCapture(session)
        } else if (shouldAttemptAutoScroll(root, initialScrollTarget != null)) {
            activeCaptureSession = session
            showToast(getString(R.string.accessibility_capture_collecting))
            scheduleNextAutoScrollStep()
        } else {
            finishCapture(session)
        }
    }

    private fun extractCapturedBlocks(
        root: AccessibilityNodeInfo,
        visibleOnly: Boolean = true
    ): List<String> {
        val rawBlocks = mutableListOf<AccessibilityTextBlock>()
        collectCandidateBlocks(root, rawBlocks, visibleOnly)
        return mergeAccessibilityBlocks(rawBlocks)
    }

    private fun collectCurrentWindowBlocks(
        session: AccessibilityCaptureSession,
        root: AccessibilityNodeInfo
    ): WindowCaptureSummary {
        val contentRoot = findBestScrollableNode(root)
        val visibleBlocks = (contentRoot ?: root).let { contentNode ->
            extractCapturedBlocks(contentNode, visibleOnly = true)
        }
        val exposedBlocks = contentRoot?.let { scrollTarget ->
            try {
                extractCapturedBlocks(scrollTarget, visibleOnly = false)
            } finally {
                scrollTarget.recycle()
            }
        }.orEmpty()

        var addedCount = 0
        val combinedBlocks = buildList {
            addAll(visibleBlocks)
            exposedBlocks.forEach { exposed ->
                if (visibleBlocks.none { normalizeAccessibilityToken(it) == normalizeAccessibilityToken(exposed) }) {
                    add(exposed)
                }
            }
        }

        combinedBlocks.forEach { block ->
            val token = normalizeAccessibilityToken(block)
            if (token.isNotBlank() && session.seenTokens.add(token)) {
                session.blocks += block
                addedCount += 1
            }
        }
        return WindowCaptureSummary(
            newBlockCount = addedCount,
            alreadyExposedEnough = isLikelyAlreadyFullyExposed(visibleBlocks, exposedBlocks)
        )
    }

    private fun scheduleNextAutoScrollStep() {
        mainHandler.postDelayed(
            { runAutoScrollStep() },
            NEXT_SCROLL_DELAY_MS
        )
    }

    private fun runAutoScrollStep() {
        val session = activeCaptureSession ?: return
        if (session.scrollStepCount >= MAX_AUTO_SCROLL_STEPS || session.stalledScrollCount >= MAX_STALLED_SCROLL_STEPS) {
            finishCapture(session)
            return
        }

        val root = rootInActiveWindow
        if (root == null) {
            finishCapture(session)
            return
        }

        val scrollTarget = findBestScrollableNode(root)
        if (scrollTarget == null) {
            finishCapture(session)
            return
        }

        val didScroll = scrollTarget.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        scrollTarget.recycle()
        if (!didScroll) {
            finishCapture(session)
            return
        }

        session.scrollStepCount += 1
        mainHandler.postDelayed(
            {
                val refreshedRoot = rootInActiveWindow
                if (refreshedRoot == null) {
                    finishCapture(session)
                    return@postDelayed
                }

                val added = collectCurrentWindowBlocks(session, refreshedRoot)
                if (added.newBlockCount == 0) {
                    session.stalledScrollCount += 1
                } else {
                    session.stalledScrollCount = 0
                }

                if (session.scrollStepCount >= MAX_AUTO_SCROLL_STEPS || session.stalledScrollCount >= MAX_STALLED_SCROLL_STEPS) {
                    finishCapture(session)
                } else {
                    scheduleNextAutoScrollStep()
                }
            },
            SCROLL_SETTLE_DELAY_MS
        )
    }

    private fun finishCapture(session: AccessibilityCaptureSession) {
        activeCaptureSession = null
        val capturedText = session.blocks.joinToString("\n\n").trim()
        if (capturedText.length < 120) {
            showToast(getString(R.string.accessibility_capture_no_text))
            return
        }

        val title = session.blocks.firstOrNull()?.takeIf(::looksLikeCapturedTitle)
        startActivity(
            ReaderAccessibilityIntents.createOpenCapturedTextIntent(
                context = this,
                text = capturedText,
                title = title
            )
        )
        showToast(getString(R.string.accessibility_capture_opened))
    }

    private fun shouldAttemptAutoScroll(root: AccessibilityNodeInfo, hasScrollTarget: Boolean): Boolean {
        if (!hasScrollTarget) {
            return false
        }

        val packageName = root.packageName?.toString().orEmpty().lowercase(Locale.US)
        return !packageName.contains("launcher") && !packageName.contains("systemui")
    }

    private fun findBrowserCurrentUrl(root: AccessibilityNodeInfo): String? {
        val candidates = mutableListOf<AccessibilityUrlCandidate>()

        fun visit(node: AccessibilityNodeInfo) {
            val marker = buildString {
                append(node.viewIdResourceName.orEmpty())
                append(' ')
                append(node.className?.toString().orEmpty())
                append(' ')
                append(node.packageName?.toString().orEmpty())
            }.lowercase(Locale.US)

            val markerScore = when {
                marker.contains("url_bar") ||
                    marker.contains("address") ||
                    marker.contains("omnibox") ||
                    marker.contains("location_bar") ||
                    marker.contains("search_box") -> 6
                marker.contains("toolbar") -> 2
                else -> 0
            }
            val className = node.className?.toString().orEmpty().lowercase(Locale.US)
            val rawTexts = sequenceOf(
                node.text?.toString(),
                node.contentDescription?.toString()
            ).filterNotNull().toList()
            val looksLikeAddressBarField =
                markerScore >= 6 ||
                    (
                        markerScore >= 2 &&
                            (node.isFocused || node.isAccessibilityFocused)
                    ) ||
                    (
                        (className.contains("edittext") || className.contains("autocomplete")) &&
                            rawTexts.any { it.contains("http://", ignoreCase = true) || it.contains("https://", ignoreCase = true) }
                    )

            if (looksLikeAddressBarField) {
                rawTexts.forEach { rawText ->
                    normalizeAccessibilityUrlCandidate(rawText).forEach { url ->
                        candidates += AccessibilityUrlCandidate(
                            url = url,
                            score = markerScore +
                                if (node.isVisibleToUser) 2 else 0 +
                                if (node.isFocused || node.isAccessibilityFocused) 2 else 0 +
                                if (rawText.contains("http://", ignoreCase = true) || rawText.contains("https://", ignoreCase = true)) 2 else 0
                        )
                    }
                }
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { child ->
                    try {
                        visit(child)
                    } finally {
                        child.recycle()
                    }
                }
            }
        }

        visit(root)
        return chooseBestAccessibilityUrlCandidate(candidates)
    }

    private fun collectCandidateBlocks(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityTextBlock>,
        visibleOnly: Boolean
    ) {
        if (visibleOnly && !node.isVisibleToUser) {
            return
        }

        val normalizedText = node.text?.toString()
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            .orEmpty()

        if (shouldIncludeNodeText(node, normalizedText)) {
            val bounds = Rect().also(node::getBoundsInScreen)
            output += AccessibilityTextBlock(
                text = normalizedText,
                top = bounds.top,
                left = bounds.left,
                bottom = bounds.bottom,
                right = bounds.right,
                isHeading = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && node.isHeading,
                isClickable = node.isClickable,
                className = node.className?.toString().orEmpty(),
                packageName = node.packageName?.toString().orEmpty()
            )
        }

        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                collectCandidateBlocks(child, output, visibleOnly)
                child.recycle()
            }
        }
    }

    private fun shouldIncludeNodeText(node: AccessibilityNodeInfo, text: String): Boolean {
        if (text.isBlank() || node.isPassword) {
            return false
        }

        val packageName = node.packageName?.toString().orEmpty().lowercase(Locale.US)
        if (packageName.contains("systemui")) {
            return false
        }

        val normalized = text.replace(Regex("\\s+"), " ").trim()
        val lower = normalized.lowercase(Locale.US)
        val wordCount = normalized.split(' ').count { it.isNotBlank() }
        val className = node.className?.toString()?.lowercase(Locale.US).orEmpty()
        val isHeading = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && node.isHeading

        val noisyExactLabels = setOf(
            "back", "home", "search", "share", "menu", "more options", "settings",
            "copy", "paste", "done", "cancel", "open", "close", "reload", "next", "previous",
            "sign in", "log in", "sign up", "subscribe", "skip to content", "accessibility"
        )
        if (normalized.length <= 32 && lower in noisyExactLabels) {
            return false
        }

        val noisyPrefixLabels = listOf(
            "privacy", "terms", "cookie", "advertisement", "related", "recommended",
            "comments", "reply", "follow", "notifications", "open in app", "view all",
            "download app", "sign up for", "more from"
        )
        if (normalized.length <= 48 && noisyPrefixLabels.any { lower.startsWith(it) }) {
            return false
        }

        if (
            className.contains("toolbar") ||
            className.contains("tabwidget") ||
            className.contains("bottomnavigation") ||
            className.contains("actionmenu") ||
            className.contains("chip")
        ) {
            return false
        }

        if (hasReadableChild(node, normalized)) {
            return false
        }

        if (className.contains("button") || className.contains("checkbox") || className.contains("switch")) {
            return normalized.length >= 70 && wordCount >= 10
        }

        if (node.isClickable && normalized.length < 36 && wordCount < 6 && !isHeading) {
            return false
        }

        if (wordCount >= 8 || normalized.length >= 52) {
            return true
        }

        if (isHeading || looksLikeCapturedTitle(normalized)) {
            return true
        }

        return false
    }

    private fun hasReadableChild(node: AccessibilityNodeInfo, nodeText: String): Boolean {
        val normalizedNodeText = normalizeAccessibilityToken(nodeText)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                val childText = child.text?.toString()
                    ?.replace(Regex("\\s+"), " ")
                    ?.trim()
                    .orEmpty()
                if (childText.isBlank()) {
                    continue
                }

                val normalizedChildText = normalizeAccessibilityToken(childText)
                if (normalizedChildText.isBlank()) {
                    continue
                }

                if (
                    normalizedChildText == normalizedNodeText ||
                    (normalizedNodeText.length > normalizedChildText.length &&
                        normalizedNodeText.contains(normalizedChildText))
                ) {
                    return true
                }
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun showToast(message: String) {
        mainHandler.post {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun registerAccessibilityButtonCallbackIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || accessibilityButtonCallback != null) {
            return
        }

        accessibilityButtonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
            override fun onClicked(controller: AccessibilityButtonController) {
                captureActiveWindowIntoRead()
            }
        }.also { callback ->
            accessibilityButtonController.registerAccessibilityButtonCallback(callback, mainHandler)
        }
    }

    private fun unregisterAccessibilityButtonCallbackIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        accessibilityButtonCallback?.let { callback ->
            accessibilityButtonController.unregisterAccessibilityButtonCallback(callback)
        }
        accessibilityButtonCallback = null
    }
}

private data class AccessibilityCaptureSession(
    val blocks: MutableList<String> = mutableListOf(),
    val seenTokens: MutableSet<String> = linkedSetOf(),
    var scrollStepCount: Int = 0,
    var stalledScrollCount: Int = 0
)

private data class WindowCaptureSummary(
    val newBlockCount: Int,
    val alreadyExposedEnough: Boolean
)

internal data class AccessibilityTextBlock(
    val text: String,
    val top: Int,
    val left: Int,
    val bottom: Int,
    val right: Int,
    val isHeading: Boolean,
    val isClickable: Boolean,
    val className: String,
    val packageName: String
)

private fun findBestScrollableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
    var bestNode: AccessibilityNodeInfo? = null
    var bestScore = Double.NEGATIVE_INFINITY

    fun visit(node: AccessibilityNodeInfo) {
        if (!node.isVisibleToUser) {
            return
        }

        if (node.isScrollable) {
            val score = scoreScrollableNode(node)
            if (score > bestScore) {
                bestNode?.recycle()
                bestNode = AccessibilityNodeInfo.obtain(node)
                bestScore = score
            }
        }

        for (index in 0 until node.childCount) {
            node.getChild(index)?.let { child ->
                try {
                    visit(child)
                } finally {
                    child.recycle()
                }
            }
        }
    }

    visit(root)
    return bestNode
}

private fun isLikelyAlreadyFullyExposed(
    visibleBlocks: List<String>,
    exposedBlocks: List<String>
): Boolean {
    if (exposedBlocks.isEmpty()) {
        return false
    }

    val visibleChars = visibleBlocks.sumOf { it.length }
    val exposedChars = exposedBlocks.sumOf { it.length }
    return exposedBlocks.size >= visibleBlocks.size + 4 &&
        exposedChars >= maxOf(1400, visibleChars + 800)
}

private fun scoreScrollableNode(node: AccessibilityNodeInfo): Double {
    val bounds = Rect().also(node::getBoundsInScreen)
    val width = (bounds.right - bounds.left).coerceAtLeast(0)
    val height = (bounds.bottom - bounds.top).coerceAtLeast(0)
    if (height < 180 || width < 120) {
        return Double.NEGATIVE_INFINITY
    }

    val className = node.className?.toString()?.lowercase(Locale.US).orEmpty()
    var score = height * 2.0 + width.toDouble()
    score += width.toDouble() * height.toDouble() / 2000.0

    if (className.contains("webview")) score += 900.0
    if (className.contains("recyclerview")) score += 700.0
    if (className.contains("scrollview")) score += 650.0
    if (className.contains("listview")) score += 500.0
    if (className.contains("nestedscrollview")) score += 550.0

    if (node.isClickable) {
        score -= 120.0
    }

    return score
}

internal fun mergeAccessibilityBlocks(blocks: List<AccessibilityTextBlock>): List<String> {
    if (blocks.isEmpty()) {
        return emptyList()
    }

    val ordered = blocks
        .filterNot(::looksLikeNoisyAccessibilityBlock)
        .distinctBy { normalizeAccessibilityToken(it.text) }
        .sortedWith(
            compareBy<AccessibilityTextBlock> { it.top }
                .thenBy { it.left }
                .thenByDescending { it.bottom - it.top }
        )

    val merged = mutableListOf<AccessibilityTextBlock>()
    for (block in ordered) {
        val previous = merged.lastOrNull()
        if (previous != null && shouldMergeAccessibilityBlocks(previous, block)) {
            merged[merged.lastIndex] = previous.copy(
                text = joinAccessibilityBlockText(previous.text, block.text),
                bottom = maxOf(previous.bottom, block.bottom),
                right = maxOf(previous.right, block.right),
                isHeading = previous.isHeading && block.isHeading,
                isClickable = previous.isClickable && block.isClickable
            )
        } else {
            merged += block
        }
    }

    return filterInlineAccessibilityNoise(trimPeripheralAccessibilityBlocks(merged))
        .map { it.text.replace(Regex("\\s+"), " ").trim() }
        .filter { it.isNotBlank() }
}

internal fun shouldMergeAccessibilityBlocks(
    previous: AccessibilityTextBlock,
    next: AccessibilityTextBlock
): Boolean {
    if (previous.isHeading || next.isHeading) {
        return false
    }

    if (looksLikeSeparateUiBlock(previous.text) || looksLikeSeparateUiBlock(next.text)) {
        return false
    }

    val verticalGap = next.top - previous.bottom
    if (verticalGap !in -6..42) {
        return false
    }

    if (abs(previous.left - next.left) > 96) {
        return false
    }

    val previousEndsSentence = previous.text.endsWith(".") || previous.text.endsWith("?") || previous.text.endsWith("!")
    val nextStartsLowercase = next.text.firstOrNull()?.isLowerCase() == true
    if (!previousEndsSentence) {
        return true
    }

    return previous.text.length < 110 && nextStartsLowercase
}

internal fun joinAccessibilityBlockText(previous: String, next: String): String {
    val trimmedPrevious = previous.trim()
    val trimmedNext = next.trim()
    val shouldJoinWithoutSpace =
        trimmedPrevious.endsWith("/") ||
            trimmedPrevious.endsWith("\u2014") ||
            trimmedPrevious.endsWith("\u2013")
    if (trimmedPrevious.isBlank()) {
        return trimmedNext
    }
    if (trimmedNext.isBlank()) {
        return trimmedPrevious
    }

    return when {
        trimmedPrevious.endsWith("-") && trimmedNext.firstOrNull()?.isLowerCase() == true -> {
            trimmedPrevious.dropLast(1) + trimmedNext
        }

        shouldJoinWithoutSpace -> {
            trimmedPrevious + trimmedNext
        }

        trimmedPrevious.endsWith("/") || trimmedPrevious.endsWith("—") || trimmedPrevious.endsWith("–") -> {
            trimmedPrevious + trimmedNext
        }

        else -> "$trimmedPrevious $trimmedNext"
    }.replace(Regex("\\s+"), " ")
        .replace(Regex("""\s+([,.;:!?])"""), "$1")
        .trim()
}

internal fun looksLikeNoisyAccessibilityBlock(block: AccessibilityTextBlock): Boolean {
    val text = block.text.replace(Regex("\\s+"), " ").trim()
    val lower = text.lowercase(Locale.US)
    val wordCount = text.split(' ').count { it.isNotBlank() }

    val badPatterns = listOf(
        "sign in", "log in", "sign up", "privacy policy", "cookie policy", "terms of service",
        "skip to content", "open menu", "close menu", "table of contents", "see all comments",
        "show more replies", "share this", "subscribe", "follow us", "advertisement",
        "open in app", "download app", "view all", "copy link", "save", "like", "repost"
    )
    if (badPatterns.any { lower == it || lower.startsWith("$it ") }) {
        return true
    }

    if (block.packageName.lowercase(Locale.US).contains("systemui")) {
        return true
    }

    if (block.isClickable && text.length < 36 && wordCount < 6) {
        return true
    }

    if (text.length < 18 && wordCount < 3 && !block.isHeading) {
        return true
    }

    return false
}

private fun trimPeripheralAccessibilityBlocks(blocks: List<AccessibilityTextBlock>): List<AccessibilityTextBlock> {
    if (blocks.isEmpty()) {
        return emptyList()
    }

    val firstSubstantialIndex = blocks.indexOfFirst(::isSubstantialAccessibilityBlock)
    val lastSubstantialIndex = blocks.indexOfLast(::isSubstantialAccessibilityBlock)
    if (firstSubstantialIndex < 0 || lastSubstantialIndex < firstSubstantialIndex) {
        return blocks
    }

    val trimmed = blocks.filterIndexed { index, block ->
        when {
            index < firstSubstantialIndex -> shouldKeepLeadingAccessibilityBlock(block)
            index > lastSubstantialIndex -> shouldKeepTrailingAccessibilityBlock(block)
            else -> true
        }
    }

    return trimmed.ifEmpty { blocks }
}

private fun filterInlineAccessibilityNoise(blocks: List<AccessibilityTextBlock>): List<AccessibilityTextBlock> {
    if (blocks.size < 3) {
        return blocks
    }

    val substantialBlocks = blocks.filter(::isSubstantialAccessibilityBlock)
    if (substantialBlocks.isEmpty()) {
        return blocks
    }

    val anchorLeft = substantialBlocks
        .map { it.left }
        .sorted()
        .let { sorted -> sorted[sorted.size / 2] }
    val anchorWidth = substantialBlocks
        .map { (it.right - it.left).coerceAtLeast(0) }
        .sorted()
        .let { sorted -> sorted[sorted.size / 2] }

    return blocks.filterIndexed { index, block ->
        shouldKeepAccessibilityBlock(
            previous = blocks.getOrNull(index - 1),
            current = block,
            next = blocks.getOrNull(index + 1),
            anchorLeft = anchorLeft,
            anchorWidth = anchorWidth
        )
    }
}

private fun shouldKeepAccessibilityBlock(
    previous: AccessibilityTextBlock?,
    current: AccessibilityTextBlock,
    next: AccessibilityTextBlock?,
    anchorLeft: Int,
    anchorWidth: Int
): Boolean {
    if (current.isHeading || isSubstantialAccessibilityBlock(current)) {
        return true
    }

    val normalized = current.text.replace(Regex("\\s+"), " ").trim()
    val wordCount = normalized.split(' ').count { it.isNotBlank() }
    val width = (current.right - current.left).coerceAtLeast(0)
    val leftDelta = abs(current.left - anchorLeft)
    val inlineNoiseBetweenParagraphs =
        previous != null &&
            next != null &&
            isSubstantialAccessibilityBlock(previous) &&
            isSubstantialAccessibilityBlock(next) &&
            !current.isClickable &&
            wordCount <= 8 &&
            normalized.length <= 52 &&
            leftDelta >= 100

    if (inlineNoiseBetweenParagraphs) {
        return false
    }

    if (current.isClickable && normalized.length <= 60 && wordCount <= 10) {
        return false
    }

    if (leftDelta >= 120 && normalized.length <= 90 && width <= (anchorWidth * 0.55f).toInt()) {
        return false
    }

    return true
}

private fun isSubstantialAccessibilityBlock(block: AccessibilityTextBlock): Boolean {
    if (block.isHeading) {
        return false
    }

    val normalized = block.text.replace(Regex("\\s+"), " ").trim()
    val wordCount = normalized.split(' ').count { it.isNotBlank() }
    return normalized.length >= 120 || wordCount >= 18
}

private fun shouldKeepLeadingAccessibilityBlock(block: AccessibilityTextBlock): Boolean {
    val normalized = block.text.replace(Regex("\\s+"), " ").trim()
    val wordCount = normalized.split(' ').count { it.isNotBlank() }
    return block.isHeading || looksLikeCapturedTitle(normalized) || (normalized.length >= 40 && wordCount >= 6 && !block.isClickable)
}

private fun shouldKeepTrailingAccessibilityBlock(block: AccessibilityTextBlock): Boolean {
    val normalized = block.text.replace(Regex("\\s+"), " ").trim()
    val wordCount = normalized.split(' ').count { it.isNotBlank() }
    return !block.isClickable && (normalized.length >= 60 || wordCount >= 10)
}

private fun looksLikeSeparateUiBlock(text: String): Boolean {
    val lower = text.lowercase(Locale.US).trim()
    return listOf(
        "share", "reply", "comment", "menu", "search", "home", "back", "next", "previous"
    ).any { lower == it }
}

internal fun normalizeAccessibilityToken(text: String): String {
    return text
        .lowercase(Locale.US)
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""[^a-z0-9 ]"""), "")
        .trim()
}

private fun looksLikeCapturedTitle(text: String): Boolean {
    if (text.length !in 6..140) {
        return false
    }

    val wordCount = text.split(Regex("\\s+")).count()
    if (wordCount !in 2..18) {
        return false
    }

    if (text.endsWith(".") || text.endsWith("?") || text.endsWith("!")) {
        return false
    }

    return text.any { it.isUpperCase() }
}
