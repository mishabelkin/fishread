package org.read.mobile

data class BrowserOpenFallbackCapture(
    val text: String,
    val title: String?,
    val sourceLabel: String
)

private const val BROWSER_OPEN_FALLBACK_MIN_CHARS = 80
private const val BROWSER_OPEN_WEAK_REMOTE_MAX_CHARS = 220
private const val BROWSER_OPEN_WEAK_REMOTE_MAX_PARAGRAPHS = 1
private const val BROWSER_OPEN_SUBSTANTIVE_PARAGRAPH_CHARS = 180
private const val BROWSER_OPEN_SUBSTANTIVE_PARAGRAPH_CHARS_WITH_HEADING = 120
private const val BROWSER_OPEN_CAPTURE_SCORE_MARGIN = 160

private data class BrowserOpenFallbackQuality(
    val paragraphCount: Int,
    val paragraphChars: Int,
    val headingCount: Int,
    val metadataCount: Int,
    val totalChars: Int
) {
    val isSubstantive: Boolean
        get() = paragraphChars >= BROWSER_OPEN_SUBSTANTIVE_PARAGRAPH_CHARS ||
            paragraphCount >= 2 ||
            (
                paragraphCount >= 1 &&
                    headingCount >= 1 &&
                    paragraphChars >= BROWSER_OPEN_SUBSTANTIVE_PARAGRAPH_CHARS_WITH_HEADING
                )

    val score: Int
        get() = paragraphChars +
            (paragraphCount * 140) +
            (headingCount * 30) -
            (metadataCount * 20) +
            minOf(totalChars, 220)
}

internal fun normalizeBrowserOpenFallbackCapture(
    text: String?,
    title: String?,
    sourceLabel: String?,
    url: String
): BrowserOpenFallbackCapture? {
    val normalizedText = text
        ?.replace("\r\n", "\n")
        ?.replace('\r', '\n')
        ?.trim()
        .orEmpty()
        .takeIf { it.length >= BROWSER_OPEN_FALLBACK_MIN_CHARS }
        ?: return null

    return BrowserOpenFallbackCapture(
        text = normalizedText,
        title = title
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() },
        sourceLabel = sourceLabel
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: url
    )
}

internal fun choosePreferredBrowserOpenFallbackCapture(
    visibleCapture: BrowserOpenFallbackCapture?,
    sessionCapture: BrowserOpenFallbackCapture?
): BrowserOpenFallbackCapture? {
    if (visibleCapture == null) {
        return sessionCapture?.takeIf(::isSubstantiveBrowserOpenFallbackCapture)
    }
    if (sessionCapture == null) {
        return visibleCapture.takeIf(::isSubstantiveBrowserOpenFallbackCapture)
    }

    val visibleQuality = scoreBrowserOpenFallbackCapture(visibleCapture)
    val sessionQuality = scoreBrowserOpenFallbackCapture(sessionCapture)
    if (!visibleQuality.isSubstantive && !sessionQuality.isSubstantive) {
        return null
    }

    return when {
        sessionQuality.isSubstantive && !visibleQuality.isSubstantive -> sessionCapture
        visibleQuality.isSubstantive && !sessionQuality.isSubstantive -> visibleCapture
        sessionQuality.score > visibleQuality.score + BROWSER_OPEN_CAPTURE_SCORE_MARGIN -> sessionCapture
        visibleQuality.score > sessionQuality.score + BROWSER_OPEN_CAPTURE_SCORE_MARGIN -> visibleCapture
        sessionQuality.paragraphChars > visibleQuality.paragraphChars + 120 &&
            sessionQuality.paragraphCount >= visibleQuality.paragraphCount -> sessionCapture
        else -> visibleCapture
    }
}

internal fun isSubstantiveBrowserOpenFallbackCapture(
    capture: BrowserOpenFallbackCapture
): Boolean {
    return scoreBrowserOpenFallbackCapture(capture).isSubstantive
}

internal fun shouldPreferBrowserFallbackDocument(
    remoteDocument: ReaderDocument,
    fallbackCapture: BrowserOpenFallbackCapture?
): Boolean {
    val fallback = fallbackCapture ?: return false
    if (!isSubstantiveBrowserOpenFallbackCapture(fallback)) {
        return false
    }
    if (remoteDocument.kind != DocumentKind.WEB) {
        return false
    }

    val paragraphCount = remoteDocument.displayBlocks.count { it.type == ReaderBlockType.Paragraph }
    val headingCount = remoteDocument.displayBlocks.count { it.type == ReaderBlockType.Heading }
    val contentLength = remoteDocument.displayBlocks
        .filter { it.type == ReaderBlockType.Paragraph || it.type == ReaderBlockType.Heading }
        .sumOf { it.text.length }

    val weakRemoteDocument =
        paragraphCount <= BROWSER_OPEN_WEAK_REMOTE_MAX_PARAGRAPHS &&
            contentLength <= BROWSER_OPEN_WEAK_REMOTE_MAX_CHARS &&
            headingCount <= 3

    return weakRemoteDocument && fallback.text.length > contentLength + 40
}

private fun scoreBrowserOpenFallbackCapture(
    capture: BrowserOpenFallbackCapture
): BrowserOpenFallbackQuality {
    val parsed = parseCapturedTextReaderContent(
        rawText = capture.text,
        providedTitle = capture.title,
        fallbackTitle = capture.title ?: "Captured text"
    )
    val paragraphBlocks = parsed.blocks.filter { it.type == ReaderBlockType.Paragraph }
    return BrowserOpenFallbackQuality(
        paragraphCount = paragraphBlocks.size,
        paragraphChars = paragraphBlocks.sumOf { it.text.length },
        headingCount = parsed.blocks.count { it.type == ReaderBlockType.Heading },
        metadataCount = parsed.metadataBlocks.size,
        totalChars = capture.text.length
    )
}
