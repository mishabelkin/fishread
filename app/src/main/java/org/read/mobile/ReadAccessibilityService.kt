package org.read.mobile

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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

internal fun looksLikeBrowserPackage(packageName: String): Boolean {
    val lower = packageName.trim().lowercase(Locale.US)
    if (lower.isBlank()) {
        return false
    }

    return lower.contains("chrome") ||
        lower.contains("firefox") ||
        lower.contains("brave") ||
        lower.contains("browser") ||
        lower.contains("opera") ||
        lower.contains("duckduckgo") ||
        lower.contains("emmx") ||
        lower.contains("edge")
}

internal data class TrackedBrowserUrl(
    val packageName: String,
    val url: String,
    val capturedAtMs: Long,
    val pageSignature: String
)

internal data class AccessibilityNodeTextCandidate(
    val text: String,
    val fromContentDescription: Boolean
)

internal fun resolveRecentTrackedBrowserUrl(
    activePackageName: String,
    trackedBrowserUrl: TrackedBrowserUrl?,
    nowMs: Long,
    maxAgeMs: Long,
    currentPageSignature: String?
): String? {
    val normalizedActivePackage = activePackageName.trim().lowercase(Locale.US)
    if (!looksLikeBrowserPackage(normalizedActivePackage)) {
        return null
    }

    val tracked = trackedBrowserUrl ?: return null
    if (tracked.packageName != normalizedActivePackage) {
        return null
    }
    if (!looksLikeBrowserOpenableUrl(tracked.url)) {
        return null
    }
    if (nowMs - tracked.capturedAtMs > maxAgeMs) {
        return null
    }
    val normalizedCurrentSignature = currentPageSignature
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    if (tracked.pageSignature != normalizedCurrentSignature) {
        return null
    }

    return tracked.url
}

private val BROWSER_RENDERED_CAPTURE_HOST_SUFFIXES = listOf(
    "wsj.com",
    "nytimes.com"
)

internal fun shouldPreferBrowserRenderedCaptureForUrl(url: String): Boolean {
    if (!looksLikeBrowserOpenableUrl(url) || looksLikePdfSessionDocumentId(url)) {
        return false
    }

    val host = runCatching {
        java.net.URI(url).host.orEmpty().lowercase(Locale.US)
    }.getOrDefault("")
    if (host.isBlank()) {
        return false
    }

    return BROWSER_RENDERED_CAPTURE_HOST_SUFFIXES.any { suffix ->
        host == suffix || host.endsWith(".$suffix")
    }
}

internal fun shouldPreferCapturedBrowserPage(
    activePackageName: String,
    currentUrl: String,
    combinedBlockCount: Int,
    combinedChars: Int
): Boolean {
    if (!looksLikeBrowserPackage(activePackageName)) {
        return false
    }
    if (!shouldPreferBrowserRenderedCaptureForUrl(currentUrl)) {
        return false
    }

    return combinedBlockCount >= 2 && combinedChars >= 240
}

internal fun shouldAttemptAccessibilityAutoScroll(
    activePackageName: String,
    hasScrollTarget: Boolean,
    combinedBlockCount: Int,
    combinedChars: Int,
    alreadyExposedEnough: Boolean
): Boolean {
    if (!hasScrollTarget) {
        return false
    }

    val packageName = activePackageName.trim().lowercase(Locale.US)
    if (packageName.contains("launcher") || packageName.contains("systemui")) {
        return false
    }

    if (!alreadyExposedEnough) {
        return true
    }

    return looksLikeBrowserPackage(packageName) &&
        (combinedBlockCount < 12 || combinedChars < 6_000)
}

internal fun shouldUseExposedAccessibilityBlocks(
    activePackageName: String,
    sourceLabel: String?
): Boolean {
    val normalizedSource = sourceLabel?.trim().orEmpty()
    if (!looksLikeBrowserPackage(activePackageName)) {
        return true
    }
    if (!shouldPreferBrowserRenderedCaptureForUrl(normalizedSource)) {
        return true
    }

    return false
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

internal fun resolveAccessibilityNodeTextCandidate(
    rawText: String?,
    rawContentDescription: String?
): AccessibilityNodeTextCandidate? {
    val normalizedText = normalizeAccessibilityNodeText(rawText)
    if (normalizedText.isNotBlank()) {
        return AccessibilityNodeTextCandidate(
            text = normalizedText,
            fromContentDescription = false
        )
    }

    val normalizedContentDescription = normalizeAccessibilityNodeText(rawContentDescription)
    if (!shouldUseAccessibilityContentDescriptionFallback(normalizedContentDescription)) {
        return null
    }

    return AccessibilityNodeTextCandidate(
        text = normalizedContentDescription,
        fromContentDescription = true
    )
}

private fun normalizeAccessibilityNodeText(raw: String?): String {
    return raw
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        .orEmpty()
}

internal fun shouldUseAccessibilityContentDescriptionFallback(text: String): Boolean {
    if (text.isBlank()) {
        return false
    }

    val normalized = text.replace(Regex("\\s+"), " ").trim()
    val lower = normalized.lowercase(Locale.US)
    if (lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.")) {
        return false
    }
    if (lower.contains("%2f") || lower.contains("%3a")) {
        return false
    }

    val punctuationCount = normalized.count { !it.isLetterOrDigit() && !it.isWhitespace() }
    return punctuationCount <= maxOf(4, normalized.length / 3)
}

internal fun normalizeBrowserPageSignature(text: String?): String? {
    val normalized = text
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        .orEmpty()
    if (normalized.length !in 4..220) {
        return null
    }

    val lower = normalized.lowercase(Locale.US)
    if (
        lower.startsWith("http://") ||
        lower.startsWith("https://") ||
        lower.startsWith("www.") ||
        lower in setOf("subscribe", "sign in", "search", "menu", "home", "back")
    ) {
        return null
    }

    return normalizeAccessibilityToken(normalized)
        .replace(Regex("\\s+"), " ")
        .takeIf { it.length >= 4 }
}

private fun sanitizeAccessibilityUrl(url: String): String {
    return url.trim().trimEnd('.', ',', ';', ':', '!', '?', ')', ']', '}')
}

class ReadAccessibilityService : AccessibilityService() {
    companion object {
        private const val LOG_TAG = "ReadAccessibility"
        private const val MAX_AUTO_SCROLL_STEPS = 10
        private const val MAX_STALLED_SCROLL_STEPS = 2
        private const val SCROLL_SETTLE_DELAY_MS = 650L
        private const val NEXT_SCROLL_DELAY_MS = 180L
        private const val BROWSER_URL_TRACK_MAX_AGE_MS = 60_000L
        private const val BROWSER_URL_TRACK_THROTTLE_MS = 1_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null
    private var activeCaptureSession: AccessibilityCaptureSession? = null
    private var lastTrackedBrowserUrl: TrackedBrowserUrl? = null
    private var lastBrowserUrlTrackAttemptAtMs: Long = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = serviceInfo?.apply {
            eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
            notificationTimeout = 0
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
        }
        registerAccessibilityButtonCallbackIfSupported()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        maybeTrackBrowserUrl(event)
    }

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

        val activePackageName = root.packageName?.toString().orEmpty()
        val activePageSignature = findBrowserPageSignature(root)
        val currentUrl = findBrowserCurrentUrl(root)?.also { resolvedUrl ->
            rememberTrackedBrowserUrl(activePackageName, resolvedUrl, activePageSignature)
        } ?: resolveRecentTrackedBrowserUrl(
            activePackageName = activePackageName,
            trackedBrowserUrl = lastTrackedBrowserUrl,
            nowMs = SystemClock.elapsedRealtime(),
            maxAgeMs = BROWSER_URL_TRACK_MAX_AGE_MS,
            currentPageSignature = activePageSignature
        )

        if (currentUrl != null && shouldReopenReaderForCurrentUrl(playbackState, currentUrl)) {
            startActivity(ReaderAccessibilityIntents.createOpenReaderIntent(this))
            showToast(getString(R.string.accessibility_capture_opened))
            return
        }

        if (currentUrl != null) {
            val session = AccessibilityCaptureSession(sourceLabel = currentUrl)
            val windowSummary = collectCurrentWindowBlocks(session, root)
            val initialScrollTarget = findBestScrollableNode(root)
            val shouldCaptureRenderedBrowserText = shouldPreferCapturedBrowserPage(
                activePackageName = activePackageName,
                currentUrl = currentUrl,
                combinedBlockCount = windowSummary.combinedBlockCount,
                combinedChars = windowSummary.combinedChars
            )

            if (!shouldCaptureRenderedBrowserText) {
                val browserFallbackCapture = buildImmediateBrowserFallbackCapture(
                    root = root,
                    currentUrl = currentUrl,
                    sessionBlocks = session.blocks
                )
                Log.i(
                    LOG_TAG,
                    "Opening browser URL with fallback backup for $currentUrl; backupChars=${browserFallbackCapture?.text?.length ?: 0}"
                )
                initialScrollTarget?.recycle()
                startActivity(
                    ReaderAccessibilityIntents.createOpenUrlIntent(
                        context = this,
                        url = currentUrl,
                        fallbackCapture = browserFallbackCapture
                    )
                )
                showToast(getString(R.string.accessibility_capture_opened))
                return
            }

            if (windowSummary.newBlockCount == 0 && initialScrollTarget == null) {
                val browserFallbackCapture = buildImmediateBrowserFallbackCapture(
                    root = root,
                    currentUrl = currentUrl,
                    sessionBlocks = session.blocks
                )
                Log.i(
                    LOG_TAG,
                    "Opening browser URL without scroll target for $currentUrl; backupChars=${browserFallbackCapture?.text?.length ?: 0}"
                )
                startActivity(
                    ReaderAccessibilityIntents.createOpenUrlIntent(
                        context = this,
                        url = currentUrl,
                        fallbackCapture = browserFallbackCapture
                    )
                )
                showToast(getString(R.string.accessibility_capture_opened))
                return
            }

            val shouldAutoScroll = shouldAttemptAccessibilityAutoScroll(
                activePackageName = activePackageName,
                hasScrollTarget = initialScrollTarget != null,
                combinedBlockCount = windowSummary.combinedBlockCount,
                combinedChars = windowSummary.combinedChars,
                alreadyExposedEnough = windowSummary.alreadyExposedEnough
            )
            initialScrollTarget?.recycle()
            if (shouldAutoScroll) {
                activeCaptureSession = session
                showToast(getString(R.string.accessibility_capture_collecting))
                scheduleNextAutoScrollStep()
            } else {
                finishCapture(session)
            }
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

        val shouldAutoScroll = shouldAttemptAccessibilityAutoScroll(
            activePackageName = activePackageName,
            hasScrollTarget = initialScrollTarget != null,
            combinedBlockCount = windowSummary.combinedBlockCount,
            combinedChars = windowSummary.combinedChars,
            alreadyExposedEnough = windowSummary.alreadyExposedEnough
        )
        initialScrollTarget?.recycle()
        if (shouldAutoScroll) {
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
        val activePackageName = root.packageName?.toString().orEmpty()
        val contentRoot = findBestScrollableNode(root)
        val visibleBlocks = (contentRoot ?: root).let { contentNode ->
            extractCapturedBlocks(contentNode, visibleOnly = true)
        }
        val exposedBlocks = if (shouldUseExposedAccessibilityBlocks(activePackageName, session.sourceLabel)) {
            contentRoot?.let { scrollTarget ->
                try {
                    extractCapturedBlocks(scrollTarget, visibleOnly = false)
                } finally {
                    scrollTarget.recycle()
                }
            }.orEmpty()
        } else {
            contentRoot?.recycle()
            emptyList()
        }

        var addedCount = 0
        val combinedBlocks = buildList {
            addAll(visibleBlocks)
            exposedBlocks.forEach { exposed ->
                if (visibleBlocks.none { normalizeAccessibilityToken(it) == normalizeAccessibilityToken(exposed) }) {
                    add(exposed)
                }
            }
        }
        val combinedChars = combinedBlocks.sumOf { it.length }

        combinedBlocks.forEach { block ->
            val token = normalizeAccessibilityToken(block)
            if (token.isNotBlank() && session.seenTokens.add(token)) {
                session.blocks += block
                addedCount += 1
            }
        }
        return WindowCaptureSummary(
            newBlockCount = addedCount,
            combinedBlockCount = combinedBlocks.size,
            combinedChars = combinedChars,
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
            session.sourceLabel
                ?.takeIf(::looksLikeBrowserOpenableUrl)
                ?.let { fallbackUrl ->
                    val fallbackCapture = buildBrowserFallbackCaptureFromBlocks(
                        currentUrl = fallbackUrl,
                        blocks = session.blocks
                    )
                    startActivity(
                        ReaderAccessibilityIntents.createOpenUrlIntent(
                            context = this,
                            url = fallbackUrl,
                            fallbackCapture = fallbackCapture
                        )
                    )
                    showToast(getString(R.string.accessibility_capture_opened))
                    return
                }
            showToast(getString(R.string.accessibility_capture_no_text))
            return
        }

        val title = session.blocks.firstOrNull()?.takeIf(::looksLikeCapturedTitle)
        startActivity(
            ReaderAccessibilityIntents.createOpenCapturedTextIntent(
                context = this,
                text = capturedText,
                title = title,
                sourceLabel = session.sourceLabel
            )
        )
        showToast(getString(R.string.accessibility_capture_opened))
    }

    private fun buildImmediateBrowserFallbackCapture(
        root: AccessibilityNodeInfo,
        currentUrl: String,
        sessionBlocks: List<String> = emptyList()
    ): BrowserOpenFallbackCapture? {
        val contentRoot = findBestScrollableNode(root)
        val visibleBlocks = try {
            trimBrowserFallbackBlocks(
                extractCapturedBlocks(contentRoot ?: root, visibleOnly = true)
                    .distinctBy(::normalizeAccessibilityToken)
            )
        } finally {
            contentRoot?.recycle()
        }
        val visibleCapture = normalizeBrowserOpenFallbackCapture(
            text = visibleBlocks.joinToString("\n\n").trim(),
            title = visibleBlocks.firstOrNull()?.takeIf(::looksLikeCapturedTitle),
            sourceLabel = currentUrl,
            url = currentUrl
        )

        val sessionSnapshotBlocks = trimBrowserFallbackBlocks(
            sessionBlocks
                .filter { it.isNotBlank() }
                .distinctBy(::normalizeAccessibilityToken)
        )
        val sessionCapture = buildBrowserFallbackCaptureFromBlocks(currentUrl, sessionSnapshotBlocks)

        return choosePreferredBrowserOpenFallbackCapture(
            visibleCapture = visibleCapture,
            sessionCapture = sessionCapture
        )
    }

    private fun buildBrowserFallbackCaptureFromBlocks(
        currentUrl: String,
        blocks: List<String>
    ): BrowserOpenFallbackCapture? {
        val trimmedBlocks = trimBrowserFallbackBlocks(
            blocks
                .filter { it.isNotBlank() }
                .distinctBy(::normalizeAccessibilityToken)
        )
        return normalizeBrowserOpenFallbackCapture(
            text = trimmedBlocks.joinToString("\n\n").trim(),
            title = trimmedBlocks.firstOrNull()?.takeIf(::looksLikeCapturedTitle),
            sourceLabel = currentUrl,
            url = currentUrl
        )?.takeIf(::isSubstantiveBrowserOpenFallbackCapture)
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

    private fun findBrowserPageSignature(root: AccessibilityNodeInfo): String? {
        var bestScore = Int.MIN_VALUE
        var bestSignature: String? = null

        fun visit(node: AccessibilityNodeInfo) {
            if (!node.isVisibleToUser) {
                return
            }

            val rawText = node.text?.toString()
            val signature = normalizeBrowserPageSignature(rawText)
            if (signature != null) {
                val className = node.className?.toString()?.lowercase(Locale.US).orEmpty()
                val isHeading = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && node.isHeading
                var score = signature.length.coerceAtMost(80)
                if (className.contains("webview")) {
                    score += 160
                }
                if (isHeading) {
                    score += 80
                }
                if (looksLikeCapturedTitle(rawText.orEmpty())) {
                    score += 40
                }
                if (score > bestScore) {
                    bestScore = score
                    bestSignature = signature
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
        return bestSignature
    }

    private fun collectCandidateBlocks(
        node: AccessibilityNodeInfo,
        output: MutableList<AccessibilityTextBlock>,
        visibleOnly: Boolean
    ) {
        if (visibleOnly && !node.isVisibleToUser) {
            return
        }

        val textCandidate = resolveAccessibilityNodeTextCandidate(
            rawText = node.text?.toString(),
            rawContentDescription = node.contentDescription?.toString()
        )

        if (textCandidate != null && shouldIncludeNodeText(node, textCandidate)) {
            val bounds = Rect().also(node::getBoundsInScreen)
            output += AccessibilityTextBlock(
                text = textCandidate.text,
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

    private fun shouldIncludeNodeText(
        node: AccessibilityNodeInfo,
        candidate: AccessibilityNodeTextCandidate
    ): Boolean {
        if (candidate.text.isBlank() || node.isPassword) {
            return false
        }

        val packageName = node.packageName?.toString().orEmpty().lowercase(Locale.US)
        if (packageName.contains("systemui")) {
            return false
        }

        val normalized = candidate.text.replace(Regex("\\s+"), " ").trim()
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

        if (hasReadableChild(node, normalized, candidate.fromContentDescription)) {
            return false
        }

        if (className.contains("button") || className.contains("checkbox") || className.contains("switch")) {
            return normalized.length >= 70 && wordCount >= 10
        }

        if (looksLikeInlineAccessibilityPunctuationToken(normalized)) {
            return true
        }

        if (normalized.length < 36 && wordCount < 6 && !isHeading) {
            return if (node.isClickable) {
                looksLikeInlineLinkedAccessibilityText(normalized)
            } else {
                looksLikeInlineArticleAccessibilityText(normalized)
            }
        }

        if (wordCount >= 8 || normalized.length >= 52) {
            return true
        }

        if (isHeading || looksLikeCapturedTitle(normalized)) {
            return true
        }

        return false
    }

    private fun hasReadableChild(
        node: AccessibilityNodeInfo,
        nodeText: String,
        fromContentDescription: Boolean
    ): Boolean {
        if (fromContentDescription && node.isClickable) {
            return false
        }

        val normalizedNodeText = normalizeAccessibilityToken(nodeText)
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            try {
                val childCandidate = resolveAccessibilityNodeTextCandidate(
                    rawText = child.text?.toString(),
                    rawContentDescription = child.contentDescription?.toString()
                ) ?: continue
                if (childCandidate.text.isBlank()) {
                    continue
                }

                val normalizedChildText = normalizeAccessibilityToken(childCandidate.text)
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

    private fun maybeTrackBrowserUrl(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString().orEmpty()
        if (!looksLikeBrowserPackage(packageName)) {
            return
        }

        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastBrowserUrlTrackAttemptAtMs < BROWSER_URL_TRACK_THROTTLE_MS) {
            return
        }
        lastBrowserUrlTrackAttemptAtMs = nowMs

        val root = rootInActiveWindow ?: return
        val pageSignature = findBrowserPageSignature(root)
        findBrowserCurrentUrl(root)?.let { currentUrl ->
            rememberTrackedBrowserUrl(packageName, currentUrl, pageSignature)
        }
    }

    private fun rememberTrackedBrowserUrl(
        packageName: String,
        url: String,
        pageSignature: String?
    ) {
        val normalizedPackageName = packageName.trim().lowercase(Locale.US)
        if (!looksLikeBrowserPackage(normalizedPackageName) || !looksLikeBrowserOpenableUrl(url)) {
            return
        }
        val normalizedPageSignature = pageSignature
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return

        lastTrackedBrowserUrl = TrackedBrowserUrl(
            packageName = normalizedPackageName,
            url = url,
            capturedAtMs = SystemClock.elapsedRealtime(),
            pageSignature = normalizedPageSignature
        )
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

internal fun trimBrowserFallbackBlocks(blocks: List<String>): List<String> {
    if (blocks.isEmpty()) {
        return emptyList()
    }

    val startIndex = findBrowserFallbackStartIndex(blocks)
    val afterStart = blocks.drop(startIndex)
    val trailingBoundaryIndex = afterStart.indexOfFirst(::looksLikeBrowserFallbackBoundaryBlock)
    val trimmed = if (trailingBoundaryIndex >= 0) {
        afterStart.take(trailingBoundaryIndex)
    } else {
        afterStart
    }

    return trimmed.ifEmpty { blocks }
}

private fun findBrowserFallbackStartIndex(blocks: List<String>): Int {
    val candidateLimit = minOf(blocks.lastIndex, 5)
    var bestIndex = 0
    var bestScore = Int.MIN_VALUE
    for (index in 0..candidateLimit) {
        val score = scoreBrowserFallbackTitleCandidate(blocks, index)
        if (score > bestScore) {
            bestScore = score
            bestIndex = index
        }
    }

    return if (bestScore >= 55) bestIndex else 0
}

private fun scoreBrowserFallbackTitleCandidate(blocks: List<String>, index: Int): Int {
    val candidate = blocks.getOrNull(index)
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        .orEmpty()
    if (!looksLikeCapturedTitle(candidate)) {
        return Int.MIN_VALUE
    }

    val normalizedCandidate = normalizeAccessibilityToken(candidate)
    var score = 30 - (index * 4)
    val lower = candidate.lowercase(Locale.US)
    if (looksLikeBrowserChromeTitleText(lower)) {
        score -= 18
    }
    if (lower.contains(" - by ")) {
        score -= 14
    }
    if (blocks.take(index).any { previous ->
            val normalizedPrevious = normalizeAccessibilityToken(previous)
            normalizedPrevious.length > normalizedCandidate.length &&
                normalizedPrevious.contains(normalizedCandidate)
        }
    ) {
        score += 28
    }

    val next = blocks.getOrNull(index + 1).orEmpty()
    val next2 = blocks.getOrNull(index + 2).orEmpty()
    val next3 = blocks.getOrNull(index + 3).orEmpty()
    if (looksLikeBrowserFallbackSubtitle(next)) {
        score += 12
    }
    if (looksLikeBrowserFallbackMetadata(next) || looksLikeBrowserFallbackMetadata(next2)) {
        score += 18
    }
    if (
        looksLikeBrowserFallbackParagraph(next) ||
        looksLikeBrowserFallbackParagraph(next2) ||
        looksLikeBrowserFallbackParagraph(next3)
    ) {
        score += 14
    }

    return score
}

private fun looksLikeBrowserChromeTitleText(lower: String): Boolean {
    return lower.contains(".com") ||
        lower.contains("www.") ||
        lower.contains("substack") ||
        lower.contains("arg min") ||
        lower.contains(" - by ")
}

private fun looksLikeBrowserFallbackSubtitle(text: String): Boolean {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    val wordCount = normalized.split(Regex("\\s+")).count { it.isNotBlank() }
    return normalized.length in 20..160 &&
        wordCount in 4..22 &&
        !looksLikeBrowserFallbackMetadata(normalized) &&
        !looksLikeBrowserFallbackParagraph(normalized)
}

private fun looksLikeBrowserFallbackMetadata(text: String): Boolean {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) {
        return false
    }

    val lower = normalized.lowercase(Locale.US)
    if (lower.startsWith("by ") || lower.startsWith("published ")) {
        return true
    }

    return Regex(
        """\b(?:jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)\b""",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(normalized)
}

private fun looksLikeBrowserFallbackParagraph(text: String): Boolean {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    val wordCount = normalized.split(Regex("\\s+")).count { it.isNotBlank() }
    return normalized.length >= 120 || wordCount >= 20
}

private fun looksLikeBrowserFallbackBoundaryBlock(text: String): Boolean {
    val normalized = text.replace(Regex("\\s+"), " ").trim().lowercase(Locale.US)
    if (normalized.isBlank()) {
        return false
    }

    if (normalized.startsWith("comment by ") || normalized.startsWith("reply by ")) {
        return true
    }
    if (Regex("""^\d+\s+repl(?:y|ies)\b""").containsMatchIn(normalized)) {
        return true
    }

    val markers = listOf(
        "discussion about this post",
        "select discussion type",
        "post preview for",
        "start your substack",
        "leave a comment",
        "add a comment",
        "see all comments"
    )
    return markers.any { marker -> normalized == marker || normalized.startsWith("$marker ") || normalized.contains(marker) }
}

private fun looksLikeInlineLinkedAccessibilityText(text: String): Boolean {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.length !in 3..72) {
        return false
    }

    val lower = normalized.lowercase(Locale.US)
    val blockedLabels = setOf(
        "back", "share", "reply", "comment", "comments", "menu", "search", "home", "next",
        "previous", "open", "close", "save", "follow", "subscribe", "like", "repost"
    )
    if (lower in blockedLabels) {
        return false
    }

    val wordCount = normalized.split(Regex("\\s+")).count { it.isNotBlank() }
    if (wordCount !in 1..6) {
        return false
    }

    val letterCount = normalized.count(Char::isLetter)
    if (letterCount < maxOf(3, normalized.length / 2)) {
        return false
    }

    if (wordCount == 1) {
        return normalized.length >= 3 && normalized.firstOrNull()?.isLetter() == true
    }

    return true
}

private fun looksLikeInlineArticleAccessibilityText(text: String): Boolean {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.length !in 4..72) {
        return false
    }

    val lower = normalized.lowercase(Locale.US)
    val blockedLabels = setOf(
        "back", "share", "reply", "comment", "comments", "menu", "search", "home", "next",
        "previous", "open", "close", "save", "follow", "subscribe", "like", "repost",
        "related", "recommended", "more", "view all"
    )
    if (lower in blockedLabels) {
        return false
    }

    val wordCount = normalized.split(Regex("\\s+")).count { it.isNotBlank() }
    if (wordCount !in 1..6) {
        return false
    }

    val letterCount = normalized.count(Char::isLetter)
    if (letterCount < maxOf(4, normalized.length / 2)) {
        return false
    }

    if (wordCount == 1) {
        return normalized.length >= 4 &&
            normalized.firstOrNull()?.isLetter() == true
    }

    val uppercaseInitials = normalized.split(Regex("\\s+"))
        .count { token -> token.firstOrNull()?.isUpperCase() == true }
    return uppercaseInitials >= 1 || normalized.contains(',')
}

private data class AccessibilityCaptureSession(
    val blocks: MutableList<String> = mutableListOf(),
    val seenTokens: MutableSet<String> = linkedSetOf(),
    val sourceLabel: String? = null,
    var scrollStepCount: Int = 0,
    var stalledScrollCount: Int = 0
)

private data class WindowCaptureSummary(
    val newBlockCount: Int,
    val combinedBlockCount: Int,
    val combinedChars: Int,
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
        .sortedWith(::compareAccessibilityBlocksForReadingOrder)

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
        } else if (previous != null && shouldReverseMergeAccessibilityBlocks(previous, block)) {
            merged[merged.lastIndex] = previous.copy(
                text = joinAccessibilityBlockText(block.text, previous.text),
                top = minOf(previous.top, block.top),
                left = minOf(previous.left, block.left),
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

private fun compareAccessibilityBlocksForReadingOrder(
    first: AccessibilityTextBlock,
    second: AccessibilityTextBlock
): Int {
    val sameVisualLine =
        first.top <= second.bottom + 12 &&
            first.bottom >= second.top - 12 &&
            abs(first.top - second.top) <= 14
    if (sameVisualLine) {
        val firstContinuation = looksLikeAccessibilityLineContinuation(first)
        val secondContinuation = looksLikeAccessibilityLineContinuation(second)
        if (firstContinuation != secondContinuation) {
            return if (firstContinuation) 1 else -1
        }

        if (abs(first.left - second.left) <= 16) {
            val firstHeight = (first.bottom - first.top).coerceAtLeast(0)
            val secondHeight = (second.bottom - second.top).coerceAtLeast(0)
            val heightComparison = firstHeight.compareTo(secondHeight)
            if (heightComparison != 0) {
                return heightComparison
            }
        }

        val leftComparison = first.left.compareTo(second.left)
        if (leftComparison != 0) {
            return leftComparison
        }
    }

    val topComparison = first.top.compareTo(second.top)
    if (topComparison != 0) {
        return topComparison
    }

    val leftComparison = first.left.compareTo(second.left)
    if (leftComparison != 0) {
        return leftComparison
    }

    return (second.bottom - second.top).compareTo(first.bottom - first.top)
}

private fun looksLikeAccessibilityLineContinuation(block: AccessibilityTextBlock): Boolean {
    val normalized = block.text.replace(Regex("\\s+"), " ").trimStart()
    val firstChar = normalized.firstOrNull() ?: return false
    if (firstChar in setOf(',', ';', ':', '.', ')', ']', '}')) {
        return true
    }

    if (!firstChar.isLowerCase()) {
        return false
    }

    val wordCount = normalized.split(Regex("\\s+")).count { it.isNotBlank() }
    return normalized.length >= 40 || wordCount >= 7
}

private fun looksLikeInlineAccessibilityPunctuationToken(text: String): Boolean {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    return normalized.isNotEmpty() && normalized.all { it in ".,;:!?" }
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

    val sameLineOverlap =
        next.top <= previous.bottom + 12 &&
            next.bottom >= previous.top - 12
    val previousLooksLikeContainer =
        (previous.right - previous.left).coerceAtLeast(0) >= 400 ||
            (previous.bottom - previous.top).coerceAtLeast(0) >= 100
    val sameLineInlineContinuation =
        sameLineOverlap &&
            (next.left - previous.right) in -8..160
    val nestedInlineFragment =
        sameLineOverlap &&
            previousLooksLikeContainer &&
            next.left >= previous.left - 12 &&
            next.right <= previous.right + 12 &&
            (
                looksLikeInlineLinkedAccessibilityText(next.text) ||
                    looksLikeInlineArticleAccessibilityText(next.text) ||
                    looksLikeInlineAccessibilityPunctuationToken(next.text) ||
                    looksLikeAccessibilityLineContinuation(next)
            )
    val fullWidthContinuation =
        sameLineOverlap &&
            previousLooksLikeContainer &&
            looksLikeAccessibilityLineContinuation(next) &&
            next.left <= previous.left + 24 &&
            next.right >= previous.right - 24
    val verticalGap = next.top - previous.bottom
    if (!sameLineInlineContinuation && !nestedInlineFragment && !fullWidthContinuation && verticalGap !in -6..42) {
        return false
    }

    if (!sameLineInlineContinuation && !nestedInlineFragment && !fullWidthContinuation && abs(previous.left - next.left) > 96) {
        return false
    }

    val previousEndsSentence = previous.text.endsWith(".") || previous.text.endsWith("?") || previous.text.endsWith("!")
    val nextStartsLowercase = next.text.firstOrNull()?.isLowerCase() == true
    if (!previousEndsSentence) {
        return true
    }

    return previous.text.length < 110 && nextStartsLowercase
}

private fun shouldReverseMergeAccessibilityBlocks(
    previous: AccessibilityTextBlock,
    next: AccessibilityTextBlock
): Boolean {
    if (previous.isHeading || next.isHeading) {
        return false
    }

    val sameLineReverseContinuation =
        next.top <= previous.bottom + 12 &&
            next.bottom >= previous.top - 12 &&
            (previous.left - next.right) in -8..48
    if (!sameLineReverseContinuation) {
        return false
    }

    return looksLikeInlineLinkedAccessibilityText(next.text) ||
        looksLikeInlineLinkedAccessibilityText(previous.text)
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

    if (block.isClickable && text.length < 36 && wordCount < 6 && !looksLikeInlineLinkedAccessibilityText(text)) {
        return true
    }

    val looksLikeInlineArticleText =
        (block.isClickable && looksLikeInlineLinkedAccessibilityText(text)) ||
            (!block.isClickable && looksLikeInlineArticleAccessibilityText(text))
    if (
        text.length < 18 &&
        wordCount < 3 &&
        !block.isHeading &&
        !looksLikeInlineArticleText &&
        !looksLikeInlineAccessibilityPunctuationToken(text)
    ) {
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

    if (
        current.isClickable &&
        looksLikeInlineLinkedAccessibilityText(normalized) &&
        previous != null &&
        next != null &&
        isSubstantialAccessibilityBlock(previous) &&
        isSubstantialAccessibilityBlock(next) &&
        leftDelta <= 72
    ) {
        return true
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
