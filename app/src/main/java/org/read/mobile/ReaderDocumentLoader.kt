package org.read.mobile

import android.annotation.SuppressLint
import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private val PDF_SELECTION_SUSPICIOUS_FRAGMENT_REGEX =
    Regex("""\b(?:con|de|inter|multi|non|pre|pro|re|sub|super|ti)-[a-z]{4,}\b""")
private val PDF_SELECTION_ARXIV_HEADER_REGEX =
    Regex(
        """arxiv:\d{4}\.\d{4,5}(?:v\d+)?\s+\[[^\]]+\]\s+\d{1,2}\s+[a-z]{3}\s+\d{4}""",
        RegexOption.IGNORE_CASE
    )

internal fun isLikelyArticleContentContainer(element: Element): Boolean {
    val tagName = element.tagName().lowercase(Locale.US)
    if (tagName == "article" || tagName == "main") {
        return true
    }

    if (element.selectFirst("article, main") != null) {
        return true
    }

    val classId = buildString {
        append(element.className())
        append(' ')
        append(element.id())
    }.lowercase(Locale.US)
    val paragraphCount = element.select("p").size
    val headingCount = element.select("h1, h2, h3, h4").size
    val textLength = normalizeWebBlockTextForHeuristics(element.text()).length
    val linkTextLength = normalizeWebBlockTextForHeuristics(element.select("a").text()).length
    val linkDensity = if (textLength == 0) 0.0 else linkTextLength.toDouble() / textLength.toDouble()
    val hasArticleMarker = listOf(
        "article",
        "content",
        "post",
        "story",
        "entry",
        "markdown",
        "body"
    ).any { classId.contains(it) }

    return paragraphCount >= 5 &&
        textLength >= 1000 &&
        linkDensity < 0.35 &&
        (headingCount >= 1 || hasArticleMarker)
}

internal fun looksLikeNoisyWebContainerForHeuristics(element: Element): Boolean {
    if (isLikelyArticleContentContainer(element)) {
        return false
    }

    val marker = normalizeWebMarkerText(
        element.className(),
        element.id(),
        element.attr("role"),
        element.attr("aria-label"),
        element.attr("data-testid")
    )

    if (marker.isBlank()) {
        return false
    }

    val noisyPhrases = listOf(
        "nav", "menu", "footer", "header", "sidebar", "share", "social", "cookie",
        "consent", "banner", "promo", "newsletter", "comment", "comments", "discussion",
        "related", "recommended", "breadcrumb", "toolbar", "pagination", "subscribe",
        "modal", "popup", "outbrain", "taboola", "rail", "masthead", "safeframe",
        "doubleclick", "googlesyndication", "advert", "advertisement", "ad slot"
    )

    return noisyPhrases.any { webMarkerContainsPhrase(marker, it) }
}

internal fun scorePdfExtractionForSelection(formatted: FormattedReaderContent): Int {
    val contentParagraphs = formatted.contentBlocks.count { it.type == ReaderBlockType.Paragraph }
    val headings = formatted.contentBlocks.count { it.type == ReaderBlockType.Heading }
    val contentChars = formatted.contentBlocks.sumOf { it.text.length }
    val footnoteChars = formatted.footnoteBlocks.sumOf { it.text.length }
    val suspiciousFragments = formatted.contentBlocks.sumOf { block ->
        PDF_SELECTION_SUSPICIOUS_FRAGMENT_REGEX.findAll(block.text).count()
    }

    return (contentChars * 4) +
        (contentParagraphs * 150) +
        (headings * 220) +
        scorePdfMetadataQuality(formatted) -
        (footnoteChars / 2) -
        (suspiciousFragments * 180) -
        scorePdfBodyNoise(formatted)
}

private fun scorePdfMetadataQuality(formatted: FormattedReaderContent): Int {
    val metadataTexts = formatted.metadataBlocks
        .map { it.text.replace(Regex("""\s+"""), " ").trim() }
        .filter { it.isNotBlank() }
    if (metadataTexts.isEmpty()) {
        return 0
    }

    val metadataText = metadataTexts.joinToString(" ")
    val metadataLower = metadataText.lowercase(Locale.US)
    var score = minOf(metadataText.length, 1200) / 2

    if (metadataTexts.any(::looksLikePdfSelectionAuthorLine)) {
        score += 520
    }

    if (metadataTexts.any(::looksLikePdfSelectionAffiliationLine)) {
        score += 180
    }

    if (metadataLower.contains("abstract")) {
        score += 420
    }

    return score
}

private fun scorePdfBodyNoise(formatted: FormattedReaderContent): Int {
    return formatted.contentBlocks.sumOf { block ->
        val clean = block.text.replace(Regex("""\s+"""), " ").trim()
        when {
            clean.isBlank() -> 0
            PDF_SELECTION_ARXIV_HEADER_REGEX.containsMatchIn(clean) -> 900
            looksLikePdfSelectionRunningHeader(clean) -> 500
            else -> 0
        }
    }
}

private fun looksLikePdfSelectionRunningHeader(text: String): Boolean {
    val clean = text.trim()
    if (clean.length !in 6..140) {
        return false
    }

    if (clean.endsWith(".") || clean.endsWith("!") || clean.endsWith("?")) {
        return false
    }

    if (looksLikePdfSelectionAuthorLine(clean) || looksLikePdfSelectionAffiliationLine(clean)) {
        return true
    }

    return Regex("""^\d+\s+[A-Z][A-Z\s,&.'-]{8,}$""").matches(clean)
}

private fun looksLikePdfSelectionAffiliationLine(text: String): Boolean {
    val lower = text.lowercase(Locale.US)
    return listOf(
        "university",
        "institute",
        "department",
        "school",
        "college",
        "laboratory",
        "center",
        "centre",
        "google",
        "deepmind",
        "microsoft",
        "openai",
        "hospital"
    ).any(lower::contains)
}

private fun looksLikePdfSelectionAuthorLine(text: String): Boolean {
    val clean = text.replace(Regex("""\s+"""), " ").trim()
    if (clean.length !in 8..140 || clean.contains('@') || clean.any(Char::isDigit)) {
        return false
    }

    val lower = clean.lowercase(Locale.US)
    if (
        listOf(
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
            "doi"
        ).any(lower::contains)
    ) {
        return false
    }

    val segments = clean
        .split(Regex("""\s*,\s*|\s+(?:and|&)\s+""", RegexOption.IGNORE_CASE))
        .map { it.trim() }
        .filter { it.isNotBlank() }

    fun isNameWord(word: String): Boolean {
        val normalized = word.trim(',', ';', ':', '.', '(', ')')
        val letters = normalized.filter(Char::isLetter)
        return letters.length in 2..20 &&
            (
                letters == letters.uppercase(Locale.US) ||
                    (letters.firstOrNull()?.isUpperCase() == true && letters.drop(1).all { it.isLowerCase() })
                )
    }

    if (segments.isEmpty()) {
        return false
    }

    return segments.all { segment ->
        val words = segment.split(Regex("""\s+""")).filter { it.isNotBlank() }
        words.size in 2..4 && words.all(::isNameWord)
    }
}

private fun normalizeWebMarkerText(vararg parts: String): String {
    return parts
        .asSequence()
        .filter { it.isNotBlank() }
        .joinToString(" ")
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase(Locale.US)
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun webMarkerContainsPhrase(marker: String, phrase: String): Boolean {
    if (marker.isBlank()) {
        return false
    }
    val normalizedPhrase = normalizeWebMarkerText(phrase)
    if (normalizedPhrase.isBlank()) {
        return false
    }
    return marker == normalizedPhrase ||
        marker.startsWith("$normalizedPhrase ") ||
        marker.endsWith(" $normalizedPhrase") ||
        marker.contains(" $normalizedPhrase ")
}

internal fun normalizeWebBlockTextForHeuristics(text: String): String {
    return text
        .replace('\u00A0', ' ')
        .replace('\u00AD', ' ')
        .replace(Regex("\\s+"), " ")
        .replace(Regex("""\s+([,.;:!?])"""), "$1")
        .trim()
}

internal fun resolveRedirectUrlPreservingWwwForHeuristics(
    currentUrl: String,
    redirectLocation: String
): String {
    val currentUri = URI(currentUrl)
    val resolved = currentUri.resolve(redirectLocation)
    val currentHost = currentUri.host?.lowercase(Locale.US).orEmpty()
    val resolvedHost = resolved.host?.lowercase(Locale.US).orEmpty()

    if (
        currentHost.startsWith("www.") &&
        resolvedHost == currentHost.removePrefix("www.")
    ) {
        return URI(
            resolved.scheme ?: currentUri.scheme,
            resolved.userInfo,
            currentUri.host,
            resolved.port,
            resolved.path,
            resolved.query,
            resolved.fragment
        ).toString()
    }

    return resolved.toString()
}

internal fun retryUrlWithWwwHostForHeuristics(urlString: String): String? {
    val uri = runCatching { URI(urlString) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase(Locale.US).orEmpty()
    val host = uri.host?.trim().orEmpty()
    if ((scheme != "http" && scheme != "https") || host.isBlank()) {
        return null
    }

    val normalizedHost = host.lowercase(Locale.US)
    if (normalizedHost.startsWith("www.") || normalizedHost.count { it == '.' } != 1) {
        return null
    }

    return URI(
        uri.scheme,
        uri.userInfo,
        "www.$host",
        uri.port,
        uri.path,
        uri.query,
        uri.fragment
    ).toString()
}

internal fun looksLikeUnknownHostFailureForHeuristics(error: Throwable): Boolean {
    generateSequence(error) { it.cause }.forEach { current ->
        if (current is java.net.UnknownHostException) {
            return true
        }

        val message = current.message?.lowercase(Locale.US).orEmpty()
        if (
            message.contains("unable to resolve host") ||
            message.contains("unknown host")
        ) {
            return true
        }
    }

    return false
}

internal fun cleanupExtractedWebBlocksForHeuristics(
    blocks: List<ReaderBlock>,
    titleText: String,
    metadataTexts: List<String> = emptyList()
): List<ReaderBlock> {
    if (blocks.isEmpty()) {
        return emptyList()
    }

    val ignoredLeadingSignatures = buildSet {
        normalizeWebDedupeKeyForHeuristics(titleText).takeIf { it.isNotBlank() }?.let(::add)
        metadataTexts
            .map(::normalizeWebDedupeKeyForHeuristics)
            .filter { it.isNotBlank() }
            .forEach(::add)
    }

    val cleaned = mutableListOf<ReaderBlock>()
    val seen = linkedSetOf<String>()
    var startedContent = false

    blocks.forEach { block ->
        val normalizedText = stripInlineWebBoilerplateFragments(
            normalizeWebBlockTextForHeuristics(block.text)
        )
        if (normalizedText.isBlank()) {
            return@forEach
        }

        val normalizedType = when (block.type) {
            ReaderBlockType.Heading -> ReaderBlockType.Heading
            ReaderBlockType.Paragraph -> ReaderBlockType.Paragraph
            else -> ReaderBlockType.Paragraph
        }
        val dedupeKey = normalizeWebDedupeKeyForHeuristics(normalizedText.take(220))
        if (dedupeKey.isBlank()) {
            return@forEach
        }

        if (!startedContent && dedupeKey in ignoredLeadingSignatures) {
            return@forEach
        }

        if (looksLikeWebBoilerplateTextForHeuristics(normalizedText, normalizedType)) {
            return@forEach
        }

        if (shouldStopWebFlowForHeuristics(normalizedText, normalizedType)) {
            return@forEach
        }

        val previous = cleaned.lastOrNull()
        if (
            previous?.type == ReaderBlockType.Paragraph &&
            normalizedType == ReaderBlockType.Paragraph &&
            shouldMergeWebParagraphs(previous.text, normalizedText)
        ) {
            cleaned[cleaned.lastIndex] = previous.copy(
                text = mergeWebParagraphText(previous.text, normalizedText)
            )
            startedContent = true
            return@forEach
        }

        if (shouldReplacePreviousDuplicateWebBlock(previous, normalizedText, normalizedType)) {
            cleaned[cleaned.lastIndex] = previous!!.copy(text = normalizedText)
            startedContent = true
            return@forEach
        }

        if (shouldSkipAsDuplicateWebBlock(cleaned, normalizedText, normalizedType)) {
            return@forEach
        }

        if (!seen.add(dedupeKey)) {
            return@forEach
        }

        cleaned += ReaderBlock(
            type = normalizedType,
            text = if (normalizedType == ReaderBlockType.Heading) normalizedText.removeSuffix(":") else normalizedText
        )
        startedContent = true
    }

    return cleaned
}

private fun stripInlineWebBoilerplateFragments(text: String): String {
    var current = text.trim()
    if (current.isBlank()) {
        return ""
    }

    current = stripLeadingPublisherLabelFragment(current)
    current = stripLeadingCopyrightFooterFragment(current)
    current = stripLeadingPublisherFooterFragment(current)
    current = stripTrailingCopyrightFooterFragment(current)
    current = stripTrailingPublisherFooterFragment(current)

    return current.trim()
}

private fun dedupeWebDebugLinks(links: List<WebExtractionDebugLink>): List<WebExtractionDebugLink> {
    val seen = linkedSetOf<String>()
    return links.filter { link ->
        val key = "${link.label}\u0000${link.href}"
        seen.add(key)
    }
}

private fun mergeWebDebugLinks(
    first: List<WebExtractionDebugLink>,
    second: List<WebExtractionDebugLink>
): List<WebExtractionDebugLink> = dedupeWebDebugLinks(first + second)

private fun cleanupExtractedWebDebugBlocksForHeuristics(
    blocks: List<WebExtractionDebugBlock>,
    titleText: String,
    metadataTexts: List<String> = emptyList()
): List<WebExtractionDebugBlock> {
    if (blocks.isEmpty()) {
        return emptyList()
    }

    val ignoredLeadingSignatures = buildSet {
        normalizeWebDedupeKeyForHeuristics(titleText).takeIf { it.isNotBlank() }?.let(::add)
        metadataTexts
            .map(::normalizeWebDedupeKeyForHeuristics)
            .filter { it.isNotBlank() }
            .forEach(::add)
    }

    val cleaned = mutableListOf<WebExtractionDebugBlock>()
    val seen = linkedSetOf<String>()
    var startedContent = false

    blocks.forEach { block ->
        val normalizedText = stripInlineWebBoilerplateFragments(
            normalizeWebBlockTextForHeuristics(block.text)
        )
        if (normalizedText.isBlank()) {
            return@forEach
        }

        val normalizedType = when (block.type) {
            ReaderBlockType.Heading -> ReaderBlockType.Heading
            ReaderBlockType.Paragraph -> ReaderBlockType.Paragraph
            else -> ReaderBlockType.Paragraph
        }
        val dedupeKey = normalizeWebDedupeKeyForHeuristics(normalizedText.take(220))
        if (dedupeKey.isBlank()) {
            return@forEach
        }

        if (!startedContent && dedupeKey in ignoredLeadingSignatures) {
            return@forEach
        }

        if (looksLikeWebBoilerplateTextForHeuristics(normalizedText, normalizedType)) {
            return@forEach
        }

        if (shouldStopWebFlowForHeuristics(normalizedText, normalizedType)) {
            return@forEach
        }

        val previous = cleaned.lastOrNull()
        if (
            previous?.type == ReaderBlockType.Paragraph &&
            normalizedType == ReaderBlockType.Paragraph &&
            shouldMergeWebParagraphs(previous.text, normalizedText)
        ) {
            cleaned[cleaned.lastIndex] = previous.copy(
                text = mergeWebParagraphText(previous.text, normalizedText),
                links = mergeWebDebugLinks(previous.links, block.links)
            )
            startedContent = true
            return@forEach
        }

        if (previous != null && shouldReplacePreviousDuplicateWebBlock(previous.toReaderBlock(), normalizedText, normalizedType)) {
            cleaned[cleaned.lastIndex] = WebExtractionDebugBlock(
                type = previous.type,
                text = normalizedText,
                links = dedupeWebDebugLinks(block.links)
            )
            startedContent = true
            return@forEach
        }

        if (shouldSkipAsDuplicateWebBlock(cleaned.map { it.toReaderBlock() }, normalizedText, normalizedType)) {
            return@forEach
        }

        if (!seen.add(dedupeKey)) {
            return@forEach
        }

        cleaned += WebExtractionDebugBlock(
            type = normalizedType,
            text = if (normalizedType == ReaderBlockType.Heading) normalizedText.removeSuffix(":") else normalizedText,
            links = dedupeWebDebugLinks(block.links)
        )
        startedContent = true
    }

    return cleaned
}

private fun sanitizeWebExtractionElement(element: Element): Element {
    val cleaned = element.clone()
    cleaned.getAllElements()
        .drop(1)
        .asReversed()
        .forEach { descendant ->
            if (shouldRemoveInlineWebUiElement(descendant)) {
                descendant.remove()
            }
        }
    return cleaned
}

private fun extractWebDebugLinks(element: Element): List<WebExtractionDebugLink> {
    return element.select("a[href]")
        .mapNotNull { anchor ->
            val label = normalizeWebBlockTextForHeuristics(anchor.text())
            val href = anchor.absUrl("href").ifBlank { anchor.attr("href").trim() }
            if (label.isBlank() || href.isBlank()) {
                null
            } else {
                WebExtractionDebugLink(label = label, href = href)
            }
        }
        .let(::dedupeWebDebugLinks)
}

private fun debugBlocksFromReaderBlocks(blocks: List<ReaderBlock>): List<WebExtractionDebugBlock> {
    return blocks.map { block ->
        WebExtractionDebugBlock(type = block.type, text = block.text)
    }
}

private fun shouldRemoveInlineWebUiElement(element: Element): Boolean {
    val tagName = element.tagName().lowercase(Locale.US)
    if (tagName in setOf("script", "style", "noscript", "svg", "path", "use", "form", "input")) {
        return true
    }

    val fullText = normalizeWebBlockTextForHeuristics(element.text())
    val ownText = normalizeWebBlockTextForHeuristics(element.ownText().ifBlank { fullText })
    val marker = normalizeWebMarkerText(
        element.className(),
        element.id(),
        element.attr("aria-label"),
        element.attr("data-testid")
    )

    val utilityLike = ownText.isNotBlank() && (
        looksLikeSocialShareLine(ownText) ||
            looksLikeActionOrUtilityLine(ownText, ReaderBlockType.Paragraph) ||
            looksLikeSiteInfoLine(ownText) ||
            looksLikeAdTechOrPromoLine(ownText, ReaderBlockType.Paragraph) ||
            looksLikeStandaloneMediaCredit(ownText, ReaderBlockType.Paragraph)
        )
    if (utilityLike && ownText.length <= 260) {
        return true
    }

    val noisyMarkerPhrases = listOf(
        "share", "social", "comment", "comments", "discussion", "reply", "footer", "site info",
        "site information", "newsletter", "subscribe", "breadcrumb", "safeframe",
        "advertisement", "advert", "sponsored", "promo"
    )
    val hasNoisyMarker = marker.isNotBlank() && noisyMarkerPhrases.any { phrase ->
        webMarkerContainsPhrase(marker, phrase)
    }
    if (hasNoisyMarker) {
        if (fullText.isBlank() || !looksLikeArticleParagraphRemainder(fullText) || ownText.length <= 120) {
            return true
        }
    }

    return tagName == "button" && (fullText.isBlank() || ownText.length <= 160 || utilityLike)
}

private fun stripLeadingPublisherLabelFragment(text: String): String {
    val publisherPrefix = Regex(
        """^(?:the wall street journal|new york times|washington post|bloomberg|associated press)\b[\s:,\-–—]+""",
        setOf(RegexOption.IGNORE_CASE)
    )
    val match = publisherPrefix.find(text) ?: return text
    val remainder = text.substring(match.range.last + 1).trim()
    return if (looksLikeArticleOrTitleRemainder(remainder)) remainder else text
}

private fun stripLeadingCopyrightFooterFragment(text: String): String {
    val lower = text.lowercase(Locale.US)
    val hasCopyrightMarker = lower.startsWith("copyright") || text.startsWith('©')
    val hasRightsMarker = lower.contains("all rights reserved")
    if (!hasCopyrightMarker || !hasRightsMarker) {
        return text
    }

    val publisherMatches = listOf(
        "the wall street journal",
        "dow jones & company, inc.",
        "dow jones",
        "new york times",
        "washington post",
        "bloomberg",
        "associated press"
    )
        .mapNotNull { marker ->
            lower.indexOf(marker).takeIf { it >= 0 }?.let { marker to it }
        }
        .sortedBy { it.second }

    val stripEndExclusive = when {
        publisherMatches.isNotEmpty() -> {
            val (publisher, index) = publisherMatches.last()
            index + publisher.length
        }

        else -> Regex("""all rights reserved\.?""", RegexOption.IGNORE_CASE)
            .find(text)
            ?.range
            ?.last
            ?.plus(1)
            ?: return text
    }

    val trailingNoise = Regex("""^(?:\s+|\s*[a-f0-9]{24,}\s*)+""", RegexOption.IGNORE_CASE)
    val remainder = text.substring(stripEndExclusive).replaceFirst(trailingNoise, "").trim()
    return if (looksLikeArticleParagraphRemainder(remainder)) remainder else text
}

private fun looksLikeArticleParagraphRemainder(text: String): Boolean {
    if (text.length < 40) {
        return false
    }

    val wordCount = text.split(Regex("""\s+""")).count { it.isNotBlank() }
    if (wordCount < 8) {
        return false
    }

    return text.any { it.isLowerCase() } && (
        text.contains('.') || text.contains(',') || text.contains(';') || text.contains(':')
        )
}

private fun stripLeadingPublisherFooterFragment(text: String): String {
    val publisherPrefix = Regex(
        """^(?:\s*[a-f0-9]{24,}\s+)?(?:the wall street journal|dow jones(?:\s*&\s*company,\s*inc\.)?|dow jones|new york times|washington post|bloomberg|associated press)\b[\s:,-]*""",
        setOf(RegexOption.IGNORE_CASE)
    )
    val match = publisherPrefix.find(text) ?: return text
    val remainder = text.substring(match.range.last + 1).trim()
    return if (looksLikeArticleOrTitleRemainder(remainder)) remainder else text
}

private fun stripTrailingCopyrightFooterFragment(text: String): String {
    val footerRegex = Regex(
        """(?:\s+|[\u2014\-:]\s*)(?:the wall street journal\s+)?copyright(?:\s+|[\u00A9©])?.*?(?:all rights reserved\.?)(?:\s+[a-f0-9]{24,})?(?:\s+the wall street journal)?\s*$""",
        setOf(RegexOption.IGNORE_CASE)
    )
    val match = footerRegex.find(text) ?: return text
    val remainder = text.substring(0, match.range.first).trim()
    return if (looksLikeArticleOrTitleRemainder(remainder)) remainder else text
}

private fun stripTrailingPublisherFooterFragment(text: String): String {
    val footerRegex = Regex(
        """(?:\s+|[\u2014\-:]\s*)(?:[a-f0-9]{24,}\s+)?(?:the wall street journal|dow jones(?:\s*&\s*company,\s*inc\.)?|dow jones|new york times|washington post|bloomberg|associated press)\s*$""",
        setOf(RegexOption.IGNORE_CASE)
    )
    val match = footerRegex.find(text) ?: return text
    val remainder = text.substring(0, match.range.first).trim()
    return if (looksLikeArticleOrTitleRemainder(remainder)) remainder else text
}

private fun looksLikeArticleOrTitleRemainder(text: String): Boolean {
    if (looksLikeArticleParagraphRemainder(text)) {
        return true
    }

    if (text.length !in 12..220) {
        return false
    }

    val wordCount = text.split(Regex("""\s+""")).count { it.isNotBlank() }
    if (wordCount !in 2..24) {
        return false
    }

    val lowercaseWords = text.split(Regex("""\s+""")).count { token ->
        token.any { it.isLowerCase() }
    }
    return lowercaseWords >= 1
}

private fun normalizeWebDedupeKeyForHeuristics(text: String): String {
    return normalizeWebBlockTextForHeuristics(text)
        .lowercase(Locale.US)
        .replace(Regex("""[^a-z0-9 ]"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun looksLikeWebBoilerplateTextForHeuristics(
    text: String,
    blockType: ReaderBlockType
): Boolean {
    val normalized = normalizeWebBlockTextForHeuristics(text)
    val lower = normalized.lowercase(Locale.US)
    val wordCount = normalized.split(' ').count { it.isNotBlank() }
    val shortText = normalized.length <= 180

    val hardPrefixes = listOf(
        "share this",
        "share article",
        "share on",
        "share via",
        "copy link",
        "subscribe",
        "sign up",
        "newsletter",
        "advertisement",
        "cookie policy",
        "all rights reserved",
        "follow us",
        "related articles",
        "related reading",
        "recommended reading",
        "read more",
        "more from",
        "about the author",
        "about the authors",
        "author bio",
        "leave a comment",
        "comments",
        "discussion",
        "site index",
        "site information",
        "follow on",
        "follow us on",
        "more to explore"
    )

    if (hardPrefixes.any { lower.startsWith(it) }) {
        return true
    }

    if (blockType == ReaderBlockType.Heading && shortText) {
        val hardHeadingMatches = listOf(
            "related articles",
            "read more",
            "recommended reading",
            "more from",
            "about the author",
            "about the authors",
            "comments",
            "discussion",
            "newsletter",
            "subscribe"
        )
        if (hardHeadingMatches.any { lower == it || lower.contains(it) }) {
            return true
        }
    }

    if (wordCount <= 6 && shortText) {
        val uiLikePhrases = listOf(
            "open in app",
            "view all",
            "show more",
            "continue reading",
            "back to top",
            "next article",
            "previous article",
            "site index",
            "site information",
            "comments",
            "share",
            "print",
            "email",
            "facebook",
            "twitter",
            "x",
            "linkedin",
            "reddit"
        )
        if (uiLikePhrases.any { lower == it || lower.startsWith(it) }) {
            return true
        }
    }

    if (looksLikeSocialShareLine(normalized)) {
        return true
    }

    if (looksLikeAdTechOrPromoLine(normalized, blockType)) {
        return true
    }

    if (looksLikeCopyrightFooterLine(normalized)) {
        return true
    }

    if (looksLikePublisherFooterLine(normalized)) {
        return true
    }

    if (looksLikeActionOrUtilityLine(normalized, blockType)) {
        return true
    }

    if (looksLikeSiteInfoLine(normalized)) {
        return true
    }

    if (looksLikeIndexOrNavLine(normalized)) {
        return true
    }

    if (looksLikeStandaloneMediaCredit(normalized, blockType)) {
        return true
    }

    if (looksLikeStandaloneAcknowledgement(normalized, blockType)) {
        return true
    }

    return false
}

private fun shouldStopWebFlowForHeuristics(
    text: String,
    blockType: ReaderBlockType
): Boolean {
    val lower = normalizeWebBlockTextForHeuristics(text).lowercase(Locale.US)

    if (blockType == ReaderBlockType.Paragraph && looksLikeStandaloneAcknowledgement(text, blockType)) {
        return true
    }

    if (blockType != ReaderBlockType.Heading) {
        return false
    }

    val stopMarkers = listOf(
        "related articles",
        "related reading",
        "recommended reading",
        "read more",
        "more from",
        "about the author",
        "about the authors",
        "comments",
        "discussion",
        "newsletter",
        "subscribe",
        "latest posts",
        "latest news",
        "site index",
        "site information",
        "about this site",
        "follow us"
    )

    return stopMarkers.any { lower == it || lower.contains(it) }
}

private fun shouldMergeWebParagraphs(first: String, second: String): Boolean {
    val left = normalizeWebBlockTextForHeuristics(first)
    val right = normalizeWebBlockTextForHeuristics(second)
    if (left.isBlank() || right.isBlank()) {
        return false
    }

    if (left.length > 420 || right.length > 420) {
        return false
    }

    val leftEndsSentence = left.endsWith(".") || left.endsWith("!") || left.endsWith("?")
    if (leftEndsSentence) {
        return false
    }

    val rightStartsContinuation = right.firstOrNull()?.let { it.isLowerCase() || it.isDigit() || it == '"' || it == '\'' || it == '(' } == true
    return rightStartsContinuation
}

private fun mergeWebParagraphText(first: String, second: String): String {
    val left = normalizeWebBlockTextForHeuristics(first)
    val right = normalizeWebBlockTextForHeuristics(second)
    return when {
        left.endsWith("-") -> left.dropLast(1) + right
        else -> "$left $right"
    }.replace(Regex("""\s+"""), " ").trim()
}

private fun shouldSkipAsDuplicateWebBlock(
    cleaned: List<ReaderBlock>,
    candidateText: String,
    candidateType: ReaderBlockType
): Boolean {
    val candidateKey = normalizeWebDedupeKeyForHeuristics(candidateText)
    if (candidateKey.isBlank()) {
        return true
    }

    return cleaned.any { existing ->
        if (existing.type != candidateType) {
            return@any false
        }

        val existingKey = normalizeWebDedupeKeyForHeuristics(existing.text)
        if (existingKey.isBlank()) {
            return@any false
        }

        existingKey == candidateKey ||
            existingKey.contains(candidateKey) && candidateKey.length >= 40 ||
            candidateKey.contains(existingKey) && existingKey.length >= 40
    }
}

private fun shouldReplacePreviousDuplicateWebBlock(
    previous: ReaderBlock?,
    candidateText: String,
    candidateType: ReaderBlockType
): Boolean {
    if (previous == null || previous.type != candidateType) {
        return false
    }

    val previousKey = normalizeWebDedupeKeyForHeuristics(previous.text)
    val candidateKey = normalizeWebDedupeKeyForHeuristics(candidateText)
    if (previousKey.isBlank() || candidateKey.isBlank()) {
        return false
    }

    return candidateKey.length > previousKey.length &&
        candidateKey.contains(previousKey) &&
        previousKey.length >= 40
}

private fun looksLikeStandaloneMediaCredit(
    text: String,
    blockType: ReaderBlockType
): Boolean {
    if (blockType != ReaderBlockType.Paragraph) {
        return false
    }

    val normalized = normalizeWebBlockTextForHeuristics(text)
    val lower = normalized.lowercase(Locale.US)
    val shortEnough = normalized.length <= 160
    if (!shortEnough) {
        return false
    }

    val creditPrefixes = listOf(
        "photo",
        "photograph by",
        "photo by",
        "photo illustration by",
        "image",
        "image credit",
        "photo credit",
        "credit",
        "credits",
        "illustration by",
        "video by",
        "source:"
    )

    return creditPrefixes.any { prefix ->
        lower == prefix || lower.startsWith("$prefix ") || lower.startsWith("$prefix:")
    }
}

private fun looksLikeStandaloneAcknowledgement(
    text: String,
    blockType: ReaderBlockType
): Boolean {
    if (blockType != ReaderBlockType.Paragraph && blockType != ReaderBlockType.Heading) {
        return false
    }

    val normalized = normalizeWebBlockTextForHeuristics(text)
    val lower = normalized.lowercase(Locale.US)
    val wordCount = normalized.split(' ').count { it.isNotBlank() }
    if (normalized.length > 220 || wordCount > 28) {
        return false
    }

    val acknowledgementPrefixes = listOf(
        "reporting was contributed by",
        "reporting contributed by",
        "additional reporting by",
        "contributed by",
        "compiled by",
        "produced by",
        "edited by",
        "fact-checked by",
        "research by",
        "photographs by",
        "photos by",
        "graphics by",
        "video by"
    )

    return acknowledgementPrefixes.any { prefix ->
        lower == prefix || lower.startsWith("$prefix ")
    }
}

private fun looksLikeSocialShareLine(text: String): Boolean {
    val normalized = normalizeWebBlockTextForHeuristics(text)
    val lower = normalized.lowercase(Locale.US)
    if (normalized.length > 220) {
        return false
    }

    val socialTokens = listOf(
        "facebook", "twitter", "x", "linkedin", "reddit", "whatsapp", "telegram",
        "email", "print", "copy link", "share", "share this", "share article"
    )

    val matchedTokens = socialTokens.count { token ->
        lower == token || lower.contains(" $token") || lower.startsWith("$token ")
    }

    if (matchedTokens >= 2) {
        return true
    }

    return lower.startsWith("share on ") ||
        lower.startsWith("share via ") ||
        lower.startsWith("follow on ")
}

private fun looksLikeAdTechOrPromoLine(
    text: String,
    blockType: ReaderBlockType
): Boolean {
    val normalized = normalizeWebBlockTextForHeuristics(text)
    val lower = normalized.lowercase(Locale.US)
    if (normalized.length > 180) {
        return false
    }

    val exactMarkers = listOf(
        "safeframe container",
        "advertisement",
        "sponsored",
        "promoted content",
        "ad choices"
    )
    if (exactMarkers.any { lower == it }) {
        return true
    }

    val adTechTokens = listOf(
        "safeframe",
        "doubleclick",
        "googlesyndication",
        "ad choices",
        "advertisement",
        "sponsored"
    )
    val tokenMatches = adTechTokens.count { token -> lower.contains(token) }
    if (tokenMatches == 0) {
        return false
    }

    return normalized.split(' ').count { it.isNotBlank() } <= 8 ||
        (blockType == ReaderBlockType.Heading && normalized.length <= 80)
}

private fun looksLikeActionOrUtilityLine(
    text: String,
    blockType: ReaderBlockType
): Boolean {
    val normalized = normalizeWebBlockTextForHeuristics(text)
    if (normalized.length > 180) {
        return false
    }

    val tokens = normalized
        .lowercase(Locale.US)
        .split(Regex("""\s+"""))
        .map { it.trim(',', '.', ':', ';', '!', '?', '(', ')', '[', ']', '"', '\'') }
        .filter { it.isNotBlank() }
    if (tokens.isEmpty()) {
        return false
    }

    val actionTokens = setOf(
        "share", "sharing", "comment", "comments", "reply", "replies", "save", "bookmark",
        "print", "email", "copy", "link", "follow", "post", "facebook", "twitter", "x",
        "linkedin", "reddit", "whatsapp", "telegram", "threads", "open", "app", "menu"
    )
    val utilityTokens = setOf(
        "site", "index", "information", "privacy", "policy", "terms", "service", "contact",
        "accessibility", "about", "help", "center", "customer", "support"
    )

    val actionMatches = tokens.count { it in actionTokens }
    val utilityMatches = tokens.count { it in utilityTokens }
    val shortWords = tokens.count { it.length <= 14 }
    val mostlyShortWords = shortWords >= tokens.size - 1

    if (mostlyShortWords && actionMatches >= 2) {
        return true
    }

    if (blockType == ReaderBlockType.Heading && actionMatches >= 1 && tokens.size <= 4) {
        return true
    }

    return mostlyShortWords &&
        utilityMatches >= 3 &&
        tokens.size <= 10
}

private fun looksLikeSiteInfoLine(text: String): Boolean {
    val normalized = normalizeWebBlockTextForHeuristics(text)
    val lower = normalized.lowercase(Locale.US)
    if (normalized.length > 260) {
        return false
    }

    val siteInfoTokens = listOf(
        "privacy policy",
        "terms of service",
        "terms of use",
        "contact us",
        "about us",
        "advertise",
        "accessibility",
        "newsletters",
        "help center",
        "customer service",
        "site information",
        "site index",
        "all rights reserved"
    )

    val tokenMatches = siteInfoTokens.count { token ->
        lower == token || lower.contains(token)
    }

    return tokenMatches >= 2 || lower.startsWith("site information") || lower.startsWith("site index")
}

private fun looksLikeCopyrightFooterLine(text: String): Boolean {
    val normalized = normalizeWebBlockTextForHeuristics(text)
    val lower = normalized.lowercase(Locale.US)
    if (normalized.length > 360) {
        return false
    }

    val hasCopyrightMarker = lower.contains("copyright") || normalized.contains('©')
    val hasRightsMarker = lower.contains("all rights reserved")
    val hasPublisherMarker = listOf(
        "wall street journal",
        "dow jones",
        "associated press",
        "new york times",
        "washington post",
        "bloomberg"
    ).any { lower.contains(it) }
    val hasTrackingHash = Regex("""\b[a-f0-9]{24,}\b""").containsMatchIn(lower)

    return (hasCopyrightMarker && hasRightsMarker) ||
        (hasCopyrightMarker && hasPublisherMarker) ||
        (hasRightsMarker && (hasPublisherMarker || hasTrackingHash))
}

private fun looksLikePublisherFooterLine(text: String): Boolean {
    val normalized = normalizeWebBlockTextForHeuristics(text)
    val lower = normalized.lowercase(Locale.US)
    if (normalized.length > 220) {
        return false
    }

    val hasPublisherMarker = listOf(
        "the wall street journal",
        "dow jones & company, inc.",
        "dow jones",
        "new york times",
        "washington post",
        "bloomberg",
        "associated press"
    ).any { lower.contains(it) }
    if (!hasPublisherMarker) {
        return false
    }

    val hasTrackingHash = Regex("""\b[a-f0-9]{24,}\b""").containsMatchIn(lower)
    val tokenCount = normalized.split(Regex("""\s+""")).count { it.isNotBlank() }
    val hasSentencePunctuation = normalized.contains('.') || normalized.contains('!') || normalized.contains('?')
    return hasTrackingHash || (tokenCount <= 10 && !hasSentencePunctuation)
}

private fun looksLikeIndexOrNavLine(text: String): Boolean {
    val normalized = normalizeWebBlockTextForHeuristics(text)
    if (normalized.length > 220) {
        return false
    }

    val separators = listOf(" | ", " · ", " • ", " / ")
    val parts = separators.firstNotNullOfOrNull { separator ->
        normalized.split(separator).takeIf { it.size >= 3 }
    } ?: return false

    val shortParts = parts.count { part ->
        val trimmed = part.trim()
        trimmed.isNotBlank() && trimmed.length <= 22 && trimmed.split(' ').count { it.isNotBlank() } <= 3
    }

    return shortParts >= 3
}

internal fun looksLikeBrowserChallengeHtmlForHeuristics(html: String): Boolean {
    if (html.isBlank()) {
        return false
    }

    val normalized = html.lowercase(Locale.US)
    return listOf(
        "<title>checking your browser",
        "javascript required",
        "__challenge",
        "i am human",
        "confirm you are human",
        "we need to check you're not a robot",
        "x-hashcash-solution"
    ).any { normalized.contains(it) }
}

private data class PdfInput(
    val title: String,
    val sourceLabel: String,
    val bytes: ByteArray
)

internal data class WebExtractionDebugCandidateSummary(
    val label: String,
    val score: Double,
    val paragraphCount: Int,
    val headingCount: Int,
    val textLength: Int,
    val linkDensity: Double
)

internal data class WebExtractionDebugLink(
    val label: String,
    val href: String
)

internal data class WebExtractionDebugBlock(
    val type: ReaderBlockType,
    val text: String,
    val links: List<WebExtractionDebugLink> = emptyList()
) {
    fun toReaderBlock(): ReaderBlock = ReaderBlock(type = type, text = text)
}

internal data class WebExtractionDebugStrategy(
    val name: String,
    val score: Double,
    val blockCount: Int,
    val totalChars: Int,
    val blocks: List<WebExtractionDebugBlock>
)

internal data class WebExtractionDebugReport(
    val title: String,
    val metadataBlocks: List<ReaderBlock>,
    val candidateSummaries: List<WebExtractionDebugCandidateSummary>,
    val strategies: List<WebExtractionDebugStrategy>,
    val chosenStrategy: String,
    val sentenceFallbackUsed: Boolean,
    val rawBlocks: List<WebExtractionDebugBlock>,
    val finalBlocks: List<ReaderBlock>
)

internal data class PdfExtractionDebugCandidate(
    val engine: PdfTextEngine,
    val score: Int,
    val pageCount: Int,
    val displayTitle: String,
    val metadataBlocks: List<ReaderBlock>,
    val footnoteBlocks: List<ReaderBlock>,
    val contentBlocks: List<ReaderBlock>
)

internal data class PdfExtractionDebugReport(
    val selectedEngine: PdfTextEngine,
    val selectedScore: Int,
    val document: ReaderDocument,
    val candidates: List<PdfExtractionDebugCandidate>
)

internal sealed interface ReaderExtractionDebugArtifact {
    val sourceLabel: String

    data class Web(
        override val sourceLabel: String,
        val html: String,
        val report: WebExtractionDebugReport
    ) : ReaderExtractionDebugArtifact

    data class Pdf(
        override val sourceLabel: String,
        val report: PdfExtractionDebugReport
    ) : ReaderExtractionDebugArtifact
}

private sealed class RemoteDocument {
    data class Pdf(
        val title: String,
        val bytes: ByteArray,
        val resolvedUrl: String
    ) : RemoteDocument()

    data class WebPage(
        val title: String,
        val html: String,
        val resolvedUrl: String
    ) : RemoteDocument()
}

class ReaderDocumentLoader(
    private val context: Context
) {
    private val muPdfPdfExtractor by lazy { MuPdfPdfExtractor() }
    private val pdfBoxPdfExtractor by lazy { PdfBoxPdfExtractor() }

    companion object {
        private const val MAX_PDF_BYTES = 32 * 1024 * 1024
        private const val NETWORK_TIMEOUT_MS = 30_000
        private const val MAX_HTTP_REDIRECTS = 6
        private const val HTML_ACCEPT_HEADER =
            "text/html,application/xhtml+xml,application/xml;q=0.9,application/pdf;q=0.8,*/*;q=0.7"
        private const val ACCEPT_LANGUAGE_HEADER = "en-US,en;q=0.9"
        private const val DESKTOP_BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
        private const val BROWSER_REFERRER = "https://www.google.com/"
        private val SUSPICIOUS_PDF_FRAGMENT_REGEX =
            Regex("""\b(?:con|de|inter|multi|non|pre|pro|re|sub|super|ti)-[a-z]{4,}\b""")
        private val HTTP_REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
    }

    fun loadFromUrl(urlString: String): ReaderDocument {
        requireSupportedRemoteUrl(urlString)
        return when (val remoteDocument = downloadRemoteDocumentWithFallback(urlString)) {
            is RemoteDocument.Pdf -> extractPdfReaderDocument(
                bytes = remoteDocument.bytes,
                title = remoteDocument.title,
                sourceLabel = remoteDocument.resolvedUrl
            )
            is RemoteDocument.WebPage -> extractWebReaderDocument(
                title = remoteDocument.title,
                sourceLabel = remoteDocument.resolvedUrl,
                html = remoteDocument.html
            )
        }
    }

    fun loadFromUri(contentResolver: ContentResolver, uri: Uri): ReaderDocument {
        val selected = readPdfFromUri(contentResolver, uri)
        return extractPdfReaderDocument(
            bytes = selected.bytes,
            title = selected.title,
            sourceLabel = selected.sourceLabel
        )
    }

    fun loadFromPlainText(
        text: String,
        title: String? = null,
        sourceLabel: String? = null
    ): ReaderDocument {
        val parsed = parseCapturedTextReaderContent(
            rawText = text,
            providedTitle = title,
            fallbackTitle = context.getString(R.string.captured_text_title)
        )
        val resolvedSourceLabel = sourceLabel
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: ReaderAccessibilityIntents.createCapturedTextSourceLabel(parsed.title)

        return ReaderDocument(
            title = parsed.title,
            sourceLabel = resolvedSourceLabel,
            kind = DocumentKind.WEB,
            pageCount = 1,
            paragraphCount = parsed.blocks.count { it.type == ReaderBlockType.Paragraph },
            headingCount = parsed.blocks.count { it.type == ReaderBlockType.Heading },
            metadataBlocks = parsed.metadataBlocks,
            footnoteBlocks = emptyList(),
            blocks = parsed.blocks
        )
    }

    internal fun loadFromHtml(
        html: String,
        sourceLabel: String,
        title: String? = null
    ): ReaderDocument {
        val fallbackTitle = title?.takeIf { it.isNotBlank() } ?: resolveTitle(null, sourceLabel)
        return buildWebExtractionDebugReport(
            html = html,
            sourceLabel = sourceLabel,
            title = fallbackTitle
        ).toReaderDocument(sourceLabel)
    }

    internal fun buildWebExtractionDebugReport(
        html: String,
        sourceLabel: String,
        title: String? = null
    ): WebExtractionDebugReport {
        val fallbackTitle = title?.takeIf { it.isNotBlank() } ?: resolveTitle(null, sourceLabel)
        return analyzeWebExtraction(
            title = fallbackTitle,
            sourceLabel = sourceLabel,
            html = html
        )
    }

    internal fun buildPdfExtractionDebugReport(
        bytes: ByteArray,
        title: String,
        sourceLabel: String
    ): PdfExtractionDebugReport {
        val evaluations = buildPdfExtractionEvaluations(bytes, title)
        val selected = evaluations
            .maxWithOrNull(
                compareBy<PdfExtractionEvaluation> { it.score }
                    .thenByDescending { it.candidate.engine == PdfTextEngine.MuPdf }
            )
            ?: error(context.getString(R.string.error_open_selected_pdf))

        val document = selected.toReaderDocument(sourceLabel)
        return PdfExtractionDebugReport(
            selectedEngine = selected.candidate.engine,
            selectedScore = selected.score,
            document = document,
            candidates = evaluations.map { evaluation ->
                PdfExtractionDebugCandidate(
                    engine = evaluation.candidate.engine,
                    score = evaluation.score,
                    pageCount = evaluation.candidate.pageCount,
                    displayTitle = evaluation.formatted.displayTitle,
                    metadataBlocks = evaluation.formatted.metadataBlocks,
                    footnoteBlocks = evaluation.formatted.footnoteBlocks,
                    contentBlocks = evaluation.formatted.contentBlocks
                )
            }
        )
    }

    internal fun buildRemoteExtractionDebugArtifact(urlString: String): ReaderExtractionDebugArtifact {
        requireSupportedRemoteUrl(urlString)
        return when (val remoteDocument = downloadRemoteDocumentWithFallback(urlString)) {
            is RemoteDocument.Pdf -> ReaderExtractionDebugArtifact.Pdf(
                sourceLabel = remoteDocument.resolvedUrl,
                report = buildPdfExtractionDebugReport(
                    bytes = remoteDocument.bytes,
                    title = remoteDocument.title,
                    sourceLabel = remoteDocument.resolvedUrl
                )
            )

            is RemoteDocument.WebPage -> ReaderExtractionDebugArtifact.Web(
                sourceLabel = remoteDocument.resolvedUrl,
                html = remoteDocument.html,
                report = buildWebExtractionDebugReport(
                    html = remoteDocument.html,
                    sourceLabel = remoteDocument.resolvedUrl,
                    title = remoteDocument.title
                )
            )
        }
    }

    private fun readPdfFromUri(contentResolver: ContentResolver, uri: Uri): PdfInput {
        var displayName: String? = null
        var declaredSize: Long? = null
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0) {
                    displayName = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    declaredSize = cursor.getLong(sizeIndex)
                }
            }
        }

        if ((declaredSize ?: 0L) > MAX_PDF_BYTES.toLong()) {
            error(pdfTooLargeMessage())
        }

        val bytes = contentResolver.openInputStream(uri)?.use { input ->
            readBytesWithLimit(input, MAX_PDF_BYTES)
        } ?: error(context.getString(R.string.error_open_selected_pdf))

        val title = displayName ?: context.getString(R.string.local_pdf_title)

        return PdfInput(
            title = title,
            sourceLabel = uri.toString(),
            bytes = bytes
        )
    }

    private fun downloadRemoteDocumentWithFallback(urlString: String): RemoteDocument {
        return runCatching { downloadRemoteDocument(urlString) }
            .recoverCatching { error ->
                val retryUrl = retryUrlWithWwwHostForHeuristics(urlString)
                    ?.takeIf { looksLikeUnknownHostFailureForHeuristics(error) }
                    ?: throw error
                downloadRemoteDocument(retryUrl)
            }
            .getOrThrow()
    }

    private fun downloadRemoteDocument(urlString: String): RemoteDocument {
        val prefersPdf = urlString.lowercase(Locale.US).endsWith(".pdf")
        var currentUrl = urlString
        repeat(MAX_HTTP_REDIRECTS + 1) { redirectCount ->
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = NETWORK_TIMEOUT_MS
                readTimeout = NETWORK_TIMEOUT_MS
                requestMethod = "GET"
                setRequestProperty("User-Agent", DESKTOP_BROWSER_USER_AGENT)
                setRequestProperty("Accept", HTML_ACCEPT_HEADER)
                setRequestProperty("Accept-Language", ACCEPT_LANGUAGE_HEADER)
                setRequestProperty("Referer", BROWSER_REFERRER)
                setRequestProperty("Upgrade-Insecure-Requests", "1")
            }

            try {
                connection.connect()
                val responseCode = connection.responseCode
                if (responseCode in HTTP_REDIRECT_STATUS_CODES) {
                    val location = connection.getHeaderField("Location")
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: error(context.getString(R.string.error_download_status, responseCode))
                    if (redirectCount >= MAX_HTTP_REDIRECTS) {
                        error(context.getString(R.string.error_unable_to_open_url))
                    }
                    currentUrl = resolveRedirectUrlPreservingWwwForHeuristics(currentUrl, location)
                    return@repeat
                }

                if (responseCode !in 200..299) {
                    if (!prefersPdf && shouldRetryHtmlFetch(responseCode)) {
                        return downloadHtmlDocumentWithJsoup(currentUrl)
                    }
                    error(context.getString(R.string.error_download_status, responseCode))
                }

                val contentType = connection.contentType ?: ""
                val resolvedTitle = resolveTitle(connection.getHeaderField("Content-Disposition"), currentUrl)
                val isPdf = contentType.contains("pdf", ignoreCase = true) || prefersPdf
                if (isPdf) {
                    if (connection.contentLengthLong > MAX_PDF_BYTES.toLong()) {
                        error(pdfTooLargeMessage())
                    }
                    val bytes = BufferedInputStream(connection.inputStream).use { input ->
                        readBytesWithLimit(input, MAX_PDF_BYTES)
                    }
                    return RemoteDocument.Pdf(
                        title = resolvedTitle,
                        bytes = bytes,
                        resolvedUrl = currentUrl
                    )
                }

                val html = connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
                if (looksLikeBrowserChallengeHtml(html)) {
                    return downloadHtmlDocumentWithWebView(currentUrl)
                }
                return RemoteDocument.WebPage(
                    title = resolvedTitle,
                    html = html,
                    resolvedUrl = currentUrl
                )
            } finally {
                connection.disconnect()
            }
        }
        error(context.getString(R.string.error_unable_to_open_url))
    }

    private fun downloadHtmlDocumentWithJsoup(urlString: String): RemoteDocument.WebPage {
        return runCatching {
            val document = Jsoup.connect(urlString)
                .userAgent(DESKTOP_BROWSER_USER_AGENT)
                .referrer(BROWSER_REFERRER)
                .header("Accept", HTML_ACCEPT_HEADER)
                .header("Accept-Language", ACCEPT_LANGUAGE_HEADER)
                .header("Upgrade-Insecure-Requests", "1")
                .followRedirects(true)
                .timeout(NETWORK_TIMEOUT_MS)
                .maxBodySize(0)
                .get()

            val resolvedTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[name=twitter:title]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: document.title().takeIf { it.isNotBlank() }
                ?: resolveTitle(null, urlString)
            val html = document.outerHtml()
            if (looksLikeBrowserChallengeHtml(html)) {
                return downloadHtmlDocumentWithWebView(urlString)
            }

            RemoteDocument.WebPage(
                title = resolvedTitle,
                html = html,
                resolvedUrl = document.location().takeIf { it.isNotBlank() } ?: urlString
            )
        }.getOrElse {
            downloadHtmlDocumentWithWebView(urlString)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun downloadHtmlDocumentWithWebView(urlString: String): RemoteDocument.WebPage {
        val latch = CountDownLatch(1)
        val htmlRef = AtomicReference<String?>()
        val errorRef = AtomicReference<Throwable?>()
        val finalUrlRef = AtomicReference<String?>()
        val webViewRef = AtomicReference<WebView?>()

        Handler(Looper.getMainLooper()).post {
            val webView = WebView(context)
            webViewRef.set(webView)
            CookieManager.getInstance().setAcceptCookie(true)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.loadsImagesAutomatically = false
            webView.settings.blockNetworkImage = true
            webView.settings.userAgentString = DESKTOP_BROWSER_USER_AGENT

            fun finishWithHtml(encodedHtml: String?) {
                if (latch.count == 0L) {
                    return
                }
                val decodedHtml = decodeJavascriptValue(encodedHtml)
                if (decodedHtml.isNullOrBlank()) {
                    errorRef.compareAndSet(null, IOException(context.getString(R.string.error_unable_to_open_url)))
                } else {
                    htmlRef.compareAndSet(null, decodedHtml)
                }
                latch.countDown()
                webView.destroy()
            }

            fun finishWithError(error: Throwable) {
                if (latch.count == 0L) {
                    return
                }
                errorRef.compareAndSet(null, error)
                latch.countDown()
                webView.destroy()
            }

            webView.webViewClient = object : WebViewClient() {
                private var resolved = false
                private var htmlCaptureInFlight = false
                private var pendingMainFrameHttpStatus: Int? = null

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    finalUrlRef.set(url)
                    if (resolved || htmlCaptureInFlight) {
                        return
                    }
                    htmlCaptureInFlight = true
                    view.evaluateJavascript(
                        "(function(){ return document.documentElement.outerHTML; })();"
                    ) { htmlValue ->
                        htmlCaptureInFlight = false
                        val decodedHtml = decodeJavascriptValue(htmlValue)
                        if (decodedHtml.isNullOrBlank()) {
                            pendingMainFrameHttpStatus?.let { statusCode ->
                                if (!resolved) {
                                    resolved = true
                                    finishWithError(
                                        IOException(
                                            context.getString(R.string.error_download_status, statusCode)
                                        )
                                    )
                                }
                                return@evaluateJavascript
                            }
                        }
                        if (!resolved && looksLikeBrowserChallengeHtml(decodedHtml.orEmpty())) {
                            return@evaluateJavascript
                        }
                        resolved = true
                        finishWithHtml(htmlValue)
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse
                ) {
                    super.onReceivedHttpError(view, request, errorResponse)
                    if (request.isForMainFrame && errorResponse.statusCode >= 400 && !resolved) {
                        pendingMainFrameHttpStatus = errorResponse.statusCode
                    }
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    super.onReceivedError(view, request, error)
                    if (request.isForMainFrame && !resolved) {
                        resolved = true
                        finishWithError(IOException(error.description?.toString().orEmpty()))
                    }
                }
            }

            webView.loadUrl(
                urlString,
                mapOf(
                    "Accept-Language" to ACCEPT_LANGUAGE_HEADER,
                    "Referer" to BROWSER_REFERRER
                )
            )
        }

        if (!latch.await((NETWORK_TIMEOUT_MS + 5_000).toLong(), TimeUnit.MILLISECONDS)) {
            Handler(Looper.getMainLooper()).post {
                webViewRef.getAndSet(null)?.destroy()
            }
            error(context.getString(R.string.error_unable_to_open_url))
        }

        errorRef.get()?.let { throw it }
        val html = htmlRef.get()?.takeIf { it.isNotBlank() }
            ?: error(context.getString(R.string.error_unable_to_open_url))

        val parsed = Jsoup.parse(html, urlString)
        val resolvedTitle = parsed.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: parsed.selectFirst("meta[name=twitter:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: parsed.title().takeIf { it.isNotBlank() }
            ?: resolveTitle(null, urlString)

        return RemoteDocument.WebPage(
            title = resolvedTitle,
            html = html,
            resolvedUrl = finalUrlRef.get()?.takeIf { it.isNotBlank() } ?: urlString
        )
    }

    private fun resolveTitle(contentDispositionHeader: String?, urlString: String): String {
        val fromHeader = contentDispositionHeader
            ?.substringAfter("filename=", "")
            ?.trim('"', '\'')
            ?.takeIf { it.isNotBlank() }

        if (fromHeader != null) {
            return fromHeader
        }

        val segment = URL(urlString).path.substringAfterLast('/').ifBlank {
            context.getString(R.string.remote_pdf_title)
        }
        return URLDecoder.decode(segment, StandardCharsets.UTF_8.name())
    }

    private fun shouldRetryHtmlFetch(responseCode: Int): Boolean {
        return responseCode in listOf(401, 403, 406, 429)
    }

    private fun decodeJavascriptValue(value: String?): String? {
        if (value == null || value == "null") {
            return null
        }

        return runCatching {
            JSONObject("""{"value":$value}""").getString("value")
        }.getOrNull()
    }

    private fun extractPdfReaderDocument(
        bytes: ByteArray,
        title: String,
        sourceLabel: String
    ): ReaderDocument {
        val selected = buildPdfExtractionEvaluations(bytes, title)
            .maxWithOrNull(
                compareBy<PdfExtractionEvaluation> { it.score }
                    .thenByDescending { it.candidate.engine == PdfTextEngine.MuPdf }
            )
            ?: error(context.getString(R.string.error_open_selected_pdf))

        return selected.toReaderDocument(sourceLabel)
    }

    private data class PdfExtractionEvaluation(
        val candidate: PdfExtractionCandidate,
        val formatted: FormattedReaderContent,
        val score: Int
    )

    private fun buildPdfExtractionEvaluations(bytes: ByteArray, title: String): List<PdfExtractionEvaluation> {
        val candidates = buildList {
            runCatching { muPdfPdfExtractor.extract(bytes) }.getOrNull()?.let(::add)
            runCatching { pdfBoxPdfExtractor.extract(bytes) }.getOrNull()?.let(::add)
        }

        return candidates.map { candidate ->
            val formatted = org.read.mobile.formatReaderContent(candidate.pageTexts, title)
            PdfExtractionEvaluation(
                candidate = candidate,
                formatted = formatted,
                score = scorePdfExtractionForSelection(formatted)
            )
        }
    }

    private fun extractWebReaderDocument(
        title: String,
        sourceLabel: String,
        html: String
    ): ReaderDocument {
        return analyzeWebExtraction(title, sourceLabel, html).toReaderDocument(sourceLabel)
    }

    private fun analyzeWebExtraction(
        title: String,
        sourceLabel: String,
        html: String
    ): WebExtractionDebugReport {
        val parsed = Jsoup.parse(html, sourceLabel)

        val titleText = parsed.selectFirst("meta[property=og:title]")?.attr("content")
            ?.takeIf { it.isNotBlank() }
            ?: parsed.selectFirst("meta[name=twitter:title]")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: parsed.title().takeIf { it.isNotBlank() }
            ?: title

        val metadataBlocks = extractWebMetadataBlocks(parsed, titleText)

        val metadataTexts = metadataBlocks.map { it.text }
        val candidateSummaries = mutableListOf<WebExtractionDebugCandidateSummary>()
        val strategies = mutableListOf<WebExtractionDebugStrategy>()

        fun addStrategy(name: String, blocks: List<WebExtractionDebugBlock>) {
            if (blocks.isEmpty()) {
                return
            }
            strategies += WebExtractionDebugStrategy(
                name = name,
                score = scoreWebBlockSet(blocks.map(WebExtractionDebugBlock::toReaderBlock)),
                blockCount = blocks.size,
                totalChars = blocks.sumOf { it.text.length },
                blocks = blocks
            )
        }

        extractSubstackDebugBlocks(parsed, html).takeIf(::isUsableSubstackBlockSet)?.let { substackBlocks ->
            val cleanedSubstackBlocks = cleanupExtractedWebDebugBlocksForHeuristics(
                blocks = substackBlocks,
                titleText = titleText,
                metadataTexts = metadataTexts
            )
            addStrategy("substack", substackBlocks)
            return WebExtractionDebugReport(
                title = titleText,
                metadataBlocks = metadataBlocks,
                candidateSummaries = emptyList(),
                strategies = strategies.toList(),
                chosenStrategy = "substack",
                sentenceFallbackUsed = false,
                rawBlocks = substackBlocks,
                finalBlocks = cleanedSubstackBlocks.map(WebExtractionDebugBlock::toReaderBlock)
            )
        }

        val cleanedParsed = parsed.clone()
        cleanedParsed.select(
            """
            script, style, nav, footer, header, form, noscript, aside, iframe, svg, canvas,
            button, input, select, textarea, dialog, [aria-hidden=true], .sr-only, .visually-hidden,
            #discussion, .post-footer, .single-post-section, .footer-wrap, [data-testid*=comments],
            [role=navigation], [role=complementary]
            """.trimIndent().replace("\n", " ")
        ).remove()
        cleanedParsed.select("*").toList().forEach { element ->
            if (looksLikeNoisyWebContainer(element)) {
                element.remove()
            }
        }

        val candidateContainers = selectCandidateWebContentContainers(cleanedParsed)
        val cleanedContainerCandidates = candidateContainers.mapIndexed { index, container ->
                candidateSummaries += summarizeWebContentContainerForDebug(container)
            val cleanedBlocks = cleanupExtractedWebDebugBlocksForHeuristics(
                blocks = extractWebDebugBlocksFromContainer(cleanWebContentContainer(container)),
                titleText = titleText,
                metadataTexts = metadataTexts
            )
            addStrategy("candidate_${index + 1}_${describeWebElementForDebug(container)}", cleanedBlocks)
            cleanedBlocks
        }
        val fallbackCandidates = buildList<Pair<String, List<WebExtractionDebugBlock>>> {
            cleanedContainerCandidates
                .filter { it.isNotEmpty() }
                .forEachIndexed { index, blocks ->
                    add("candidate_${index + 1}" to blocks)
                }
            cleanupExtractedWebDebugBlocksForHeuristics(
                blocks = extractTitleAnchoredWebDebugBlocks(cleanedParsed, titleText, sourceLabel),
                titleText = titleText,
                metadataTexts = metadataTexts
            ).takeIf { isStrongWebFallback(it.map(WebExtractionDebugBlock::toReaderBlock)) }?.let {
                addStrategy("title_anchored", it)
                add("title_anchored" to it)
            }
            cleanupExtractedWebDebugBlocksForHeuristics(
                blocks = extractJsonLdArticleDebugBlocks(parsed),
                titleText = titleText,
                metadataTexts = metadataTexts
            ).takeIf { isStrongWebFallback(it.map(WebExtractionDebugBlock::toReaderBlock)) }?.let {
                addStrategy("json_ld", it)
                add("json_ld" to it)
            }
            cleanupExtractedWebDebugBlocksForHeuristics(
                blocks = extractEmbeddedWebBodyDebugBlocks(html),
                titleText = titleText,
                metadataTexts = metadataTexts
            ).takeIf { isStrongWebFallback(it.map(WebExtractionDebugBlock::toReaderBlock)) }?.let {
                addStrategy("embedded_body", it)
                add("embedded_body" to it)
            }
        }

        var chosenStrategy = fallbackCandidates
            .maxByOrNull { scoreWebBlockSet(it.second.map(WebExtractionDebugBlock::toReaderBlock)) }
            ?.first
            ?: if (cleanedContainerCandidates.firstOrNull()?.isNotEmpty() == true) "candidate_1" else "empty"
        var resolvedBlocks = fallbackCandidates
            .maxByOrNull { scoreWebBlockSet(it.second.map(WebExtractionDebugBlock::toReaderBlock)) }
            ?.second
            ?.toMutableList()
            ?: cleanedContainerCandidates.firstOrNull()?.toMutableList()
            ?: mutableListOf()

        val fallbackSource = candidateContainers.firstOrNull()?.let(::cleanWebContentContainer)
            ?: cleanedParsed.body()?.let(::cleanWebContentContainer)

        var sentenceFallbackUsed = false
        if (
            fallbackSource != null &&
            shouldUseSentenceFallbackForWebExtraction(
                blocks = resolvedBlocks.map(WebExtractionDebugBlock::toReaderBlock),
                fallbackSourceText = fallbackSource.text()
            )
        ) {
            sentenceFallbackUsed = true
            resolvedBlocks = debugBlocksFromReaderBlocks(
                buildSentenceFallbackWebBlocks(
                    source = fallbackSource,
                    titleText = titleText,
                    metadataTexts = metadataTexts
                )
            ).toMutableList()
            chosenStrategy = "sentence_fallback"
            addStrategy("sentence_fallback", resolvedBlocks)
        }

        val finalBlocks = cleanupExtractedWebDebugBlocksForHeuristics(
            blocks = resolvedBlocks,
            titleText = titleText,
            metadataTexts = metadataTexts
        ).map(WebExtractionDebugBlock::toReaderBlock)

        return WebExtractionDebugReport(
            title = titleText,
            metadataBlocks = metadataBlocks,
            candidateSummaries = candidateSummaries
                .sortedByDescending { it.score },
            strategies = strategies
                .sortedByDescending { it.score },
            chosenStrategy = chosenStrategy,
            sentenceFallbackUsed = sentenceFallbackUsed,
            rawBlocks = resolvedBlocks,
            finalBlocks = finalBlocks
        )
    }

    private fun WebExtractionDebugReport.toReaderDocument(sourceLabel: String): ReaderDocument {
        return ReaderDocument(
            title = title,
            sourceLabel = sourceLabel,
            kind = DocumentKind.WEB,
            pageCount = 1,
            paragraphCount = finalBlocks.count { it.type == ReaderBlockType.Paragraph },
            headingCount = finalBlocks.count { it.type == ReaderBlockType.Heading },
            metadataBlocks = metadataBlocks,
            footnoteBlocks = emptyList(),
            blocks = finalBlocks
        )
    }

    private fun PdfExtractionEvaluation.toReaderDocument(sourceLabel: String): ReaderDocument {
        return ReaderDocument(
            title = formatted.displayTitle,
            sourceLabel = sourceLabel,
            kind = DocumentKind.PDF,
            pageCount = candidate.pageCount,
            paragraphCount = formatted.contentBlocks.count { it.type == ReaderBlockType.Paragraph },
            headingCount = formatted.contentBlocks.count { it.type == ReaderBlockType.Heading },
            metadataBlocks = formatted.metadataBlocks,
            footnoteBlocks = formatted.footnoteBlocks,
            blocks = formatted.contentBlocks
        )
    }

    private fun summarizeWebContentContainerForDebug(container: Element): WebExtractionDebugCandidateSummary {
        val extractedBlocks = extractWebBlocksFromContainer(cleanWebContentContainer(container))
        val textLength = normalizeWebBlockText(container.text()).length
        val linkTextLength = normalizeWebBlockText(container.select("a").text()).length
        return WebExtractionDebugCandidateSummary(
            label = describeWebElementForDebug(container),
            score = scoreWebContentContainer(container),
            paragraphCount = extractedBlocks.count { it.type == ReaderBlockType.Paragraph },
            headingCount = extractedBlocks.count { it.type == ReaderBlockType.Heading },
            textLength = textLength,
            linkDensity = if (textLength <= 0) 0.0 else linkTextLength.toDouble() / textLength.toDouble()
        )
    }

    private fun describeWebElementForDebug(element: Element): String {
        val idPart = element.id()
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let { "#$it" }
            .orEmpty()
        val classPart = element.classNames()
            .take(3)
            .joinToString(separator = "") { ".${it.trim()}" }
        return "${element.tagName().lowercase(Locale.US)}$idPart$classPart"
            .takeIf { it.isNotBlank() }
            ?: element.tagName().lowercase(Locale.US)
    }

    private fun extractSubstackBlocks(
        parsed: org.jsoup.nodes.Document,
        html: String
    ): List<ReaderBlock> {
        return extractSubstackDebugBlocks(parsed, html).map { it.toReaderBlock() }
    }

    private fun extractSubstackDebugBlocks(
        parsed: org.jsoup.nodes.Document,
        html: String
    ): List<WebExtractionDebugBlock> {
        val substackContainers = linkedSetOf<Element>()
        listOf(
            ".available-content .body.markup",
            ".available-content .markup",
            "[data-testid*=post-content] .body.markup",
            "[data-testid*=post-content] .markup",
            "[data-testid*=post-content]",
            ".post-content .body.markup",
            ".post-content .markup",
            ".post-content",
            "article .body.markup",
            "article .markup",
            ".body.markup"
        ).forEach { selector ->
            substackContainers += parsed.select(selector)
        }

        val domBlocks = substackContainers
            .map(::extractSubstackDomDebugBlocks)
            .filter { it.isNotEmpty() }
            .maxByOrNull { scoreWebBlockSet(it.map(WebExtractionDebugBlock::toReaderBlock)) }
            .orEmpty()

        if (isUsableSubstackBlockSet(domBlocks)) {
            return domBlocks
        }

        val embeddedBlocks = extractEmbeddedWebBodyDebugBlocks(html)
        if (isUsableSubstackBlockSet(embeddedBlocks)) {
            return embeddedBlocks
        }

        return listOf(domBlocks, embeddedBlocks)
            .filter { it.isNotEmpty() }
            .maxByOrNull { scoreWebBlockSet(it.map(WebExtractionDebugBlock::toReaderBlock)) }
            .orEmpty()
    }

    private fun isUsableSubstackBlockSet(blocks: List<WebExtractionDebugBlock>): Boolean {
        if (blocks.isEmpty()) {
            return false
        }

        val paragraphCount = blocks.count { it.type == ReaderBlockType.Paragraph }
        val totalChars = blocks.sumOf { it.text.length }
        val paragraphChars = blocks
            .asSequence()
            .filter { it.type == ReaderBlockType.Paragraph }
            .sumOf { it.text.length }

        return when {
            paragraphCount >= 3 && paragraphChars >= 240 -> true
            paragraphCount >= 2 && paragraphChars >= 120 -> true
            paragraphCount >= 1 && paragraphChars >= 80 -> true
            totalChars >= 160 -> true
            else -> false
        }
    }

    private fun extractSubstackDomBlocks(container: Element): List<ReaderBlock> {
        return extractSubstackDomDebugBlocks(container).map { it.toReaderBlock() }
    }

    private fun extractSubstackDomDebugBlocks(container: Element): List<WebExtractionDebugBlock> {
        val blocks = mutableListOf<WebExtractionDebugBlock>()
        val seen = linkedSetOf<String>()

        fun appendBlock(element: Element) {
            val tagName = element.tagName().lowercase(Locale.US)
            val text = normalizeWebElementText(element)
            if (!isUsableWebBlockText(text, tagName, element)) {
                return
            }

            val dedupeKey = normalizeHistoryToken(text.take(220))
            if (dedupeKey.isBlank() || !seen.add(dedupeKey)) {
                return
            }

            val blockType = if (tagName.startsWith("h")) ReaderBlockType.Heading else ReaderBlockType.Paragraph
            val normalizedText = if (blockType == ReaderBlockType.Heading) text.removeSuffix(":") else text
            blocks += WebExtractionDebugBlock(
                type = blockType,
                text = normalizedText,
                links = extractWebDebugLinks(element)
            )
        }

        fun appendChildren(parent: Element) {
            parent.children().forEach { child ->
                when (child.tagName().lowercase(Locale.US)) {
                    "p", "blockquote", "pre", "h1", "h2", "h3", "h4" -> appendBlock(child)
                    "ul", "ol" -> child.children()
                        .filter { it.tagName().equals("li", ignoreCase = true) }
                        .forEach(::appendBlock)
                    "div" -> {
                        if (isParagraphLikeWebDiv(child)) {
                            appendBlock(child)
                        } else {
                            appendChildren(child)
                        }
                    }
                }
            }
        }

        appendChildren(container)
        return if (blocks.isNotEmpty()) blocks else extractWebDebugBlocksFromContainer(container)
    }

    private fun extractWebBlocksFromContainer(container: Element): MutableList<ReaderBlock> {
        return extractWebDebugBlocksFromContainer(container)
            .mapTo(mutableListOf()) { it.toReaderBlock() }
    }

    private fun extractWebDebugBlocksFromContainer(container: Element): MutableList<WebExtractionDebugBlock> {
        val blocks = mutableListOf<WebExtractionDebugBlock>()
        val seen = linkedSetOf<String>()
        val candidateElements = selectWebContentElements(container)
        candidateElements.forEach { element ->
            if (element.parents().any { it !== container && looksLikeNoisyWebContainer(it) }) {
                return@forEach
            }

            val tagName = element.tagName().lowercase(Locale.US)
            val text = normalizeWebElementText(element)
            if (!isUsableWebBlockText(text, tagName, element)) {
                return@forEach
            }

            val dedupeKey = normalizeHistoryToken(text.take(220))
            if (dedupeKey.isBlank() || !seen.add(dedupeKey)) {
                return@forEach
            }

            val blockType = if (tagName.startsWith("h")) ReaderBlockType.Heading else ReaderBlockType.Paragraph
            val normalizedText = if (blockType == ReaderBlockType.Heading) {
                text.removeSuffix(":")
            } else {
                text
            }
            blocks += WebExtractionDebugBlock(
                type = blockType,
                text = normalizedText,
                links = extractWebDebugLinks(element)
            )
        }
        return blocks
    }

    private fun extractEmbeddedWebBodyBlocks(html: String): List<ReaderBlock> {
        return extractEmbeddedWebBodyDebugBlocks(html).map { it.toReaderBlock() }
    }

    private fun extractEmbeddedWebBodyDebugBlocks(html: String): List<WebExtractionDebugBlock> {
        val bodyHtml = decodeEmbeddedBodyHtml(html) ?: return emptyList()
        val parsedBody = Jsoup.parseBodyFragment(bodyHtml)
        val container = parsedBody.selectFirst("body") ?: return emptyList()
        val directBlocks = extractSubstackDomDebugBlocks(container)
        if (directBlocks.isNotEmpty()) {
            return directBlocks
        }

        val containerBlocks = extractWebDebugBlocksFromContainer(container)
        if (containerBlocks.isNotEmpty()) {
            return containerBlocks
        }

        val structuredBody = normalizeStructuredArticleBody(
            bodyHtml
                .replace(Regex("""(?i)</p>\s*<p[^>]*>"""), "\n\n")
                .replace(Regex("""(?i)</div>\s*<div[^>]*>"""), "\n\n")
                .replace(Regex("""(?i)<br\s*/?>"""), "\n")
                .replace(Regex("""<[^>]+>"""), " ")
        )
        return debugBlocksFromReaderBlocks(splitStructuredArticleBody(structuredBody))
    }

    private fun extractJsonLdArticleBlocks(parsed: org.jsoup.nodes.Document): List<ReaderBlock> {
        val articleBodies = mutableListOf<String>()
        parsed.select("script[type=application/ld+json]").forEach { script ->
            val raw = script.data().ifBlank { script.html() }.trim()
            if (raw.isBlank()) {
                return@forEach
            }

            runCatching {
                when {
                    raw.startsWith("[") -> collectArticleBodiesFromJson(JSONArray(raw), articleBodies)
                    raw.startsWith("{") -> collectArticleBodiesFromJson(JSONObject(raw), articleBodies)
                }
            }
        }

        val articleBody = articleBodies
            .map(::normalizeStructuredArticleBody)
            .filter { it.length >= 500 }
            .maxByOrNull { it.length }
            ?: return emptyList()

        return splitStructuredArticleBody(articleBody)
    }

    private fun extractJsonLdArticleDebugBlocks(parsed: org.jsoup.nodes.Document): List<WebExtractionDebugBlock> {
        return debugBlocksFromReaderBlocks(extractJsonLdArticleBlocks(parsed))
    }

    private fun collectArticleBodiesFromJson(value: Any?, articleBodies: MutableList<String>) {
        when (value) {
            is JSONObject -> {
                value.optString("articleBody")
                    .takeIf { it.isNotBlank() }
                    ?.let(articleBodies::add)

                value.keys().forEach { key ->
                    collectArticleBodiesFromJson(value.opt(key), articleBodies)
                }
            }

            is JSONArray -> {
                for (index in 0 until value.length()) {
                    collectArticleBodiesFromJson(value.opt(index), articleBodies)
                }
            }
        }
    }

    private fun normalizeStructuredArticleBody(text: String): String {
        return text
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("""[ \t]+\n"""), "\n")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    private fun splitStructuredArticleBody(articleBody: String): List<ReaderBlock> {
        return articleBody
            .split(Regex("""\n\s*\n+"""))
            .map { paragraph ->
                normalizeWebBlockText(paragraph.replace('\n', ' '))
            }
            .filter { paragraph ->
                isUsableWebBlockText(paragraph, "p") || looksLikeStructuredHeading(paragraph)
            }
            .map { paragraph ->
                if (looksLikeStructuredHeading(paragraph)) {
                    ReaderBlock(ReaderBlockType.Heading, paragraph.removeSuffix(":"))
                } else {
                    ReaderBlock(ReaderBlockType.Paragraph, paragraph)
                }
            }
    }

    private fun looksLikeStructuredHeading(text: String): Boolean {
        val wordCount = text.split(' ').count { it.isNotBlank() }
        if (wordCount !in 1..12 || text.length !in 4..110) {
            return false
        }

        if (text.endsWith(".") || text.endsWith("?") || text.endsWith("!")) {
            return false
        }

        val lowercase = text.lowercase(Locale.US)
        return text.any { it.isUpperCase() } && !lowercase.startsWith("http")
    }

    private fun extractTitleAnchoredWebBlocks(
        parsed: org.jsoup.nodes.Document,
        titleText: String,
        sourceLabel: String
    ): List<ReaderBlock> {
        return extractTitleAnchoredWebDebugBlocks(parsed, titleText, sourceLabel).map { it.toReaderBlock() }
    }

    private fun extractTitleAnchoredWebDebugBlocks(
        parsed: org.jsoup.nodes.Document,
        titleText: String,
        sourceLabel: String
    ): List<WebExtractionDebugBlock> {
        val body = parsed.body() ?: return emptyList()
        val normalizedTitle = normalizeHistoryToken(titleText)
        if (normalizedTitle.isBlank()) {
            return emptyList()
        }

        val host = runCatching { Uri.parse(sourceLabel).host.orEmpty().lowercase(Locale.US) }.getOrDefault("")
        val blocks = mutableListOf<WebExtractionDebugBlock>()
        val seen = linkedSetOf<String>()
        var started = false

        selectWebContentElements(body).forEach { element ->
            val tagName = element.tagName().lowercase(Locale.US)
            val text = normalizeWebElementText(element)
            if (text.isBlank()) {
                return@forEach
            }

            val normalizedText = normalizeHistoryToken(text)
            if (!started) {
                val isTitleMatch = tagName.startsWith("h") && (
                    normalizedText == normalizedTitle ||
                        normalizedText.contains(normalizedTitle) ||
                        normalizedTitle.contains(normalizedText)
                    )
                if (!isTitleMatch) {
                    return@forEach
                }

                started = true
            }

            if (shouldStopTitleAnchoredFlow(text, tagName, host)) {
                return@forEach
            }

            if (!isUsableWebBlockText(text, tagName) && !tagName.startsWith("h")) {
                return@forEach
            }

            val dedupeKey = normalizeHistoryToken(text.take(220))
            if (dedupeKey.isBlank() || !seen.add(dedupeKey)) {
                return@forEach
            }

            val blockType = if (tagName.startsWith("h")) ReaderBlockType.Heading else ReaderBlockType.Paragraph
            blocks += WebExtractionDebugBlock(
                type = blockType,
                text = if (blockType == ReaderBlockType.Heading) text.removeSuffix(":") else text,
                links = extractWebDebugLinks(element)
            )
        }

        return blocks
    }

    private fun shouldStopTitleAnchoredFlow(
        text: String,
        tagName: String,
        host: String
    ): Boolean {
        val normalized = normalizeWebBlockText(text).lowercase(Locale.US)
        if (normalized.isBlank()) {
            return false
        }

        val genericStopMarkers = listOf(
            "related reading",
            "related articles",
            "recommended reading",
            "read more",
            "more from",
            "share this",
            "share article",
            "email alerts",
            "stay up-to-date",
            "subscribe",
            "sign up",
            "about the author",
            "about the authors",
            "author bio",
            "contact us"
        )
        if (genericStopMarkers.any { normalized.startsWith(it) || normalized == it }) {
            return true
        }

        if (host.contains("siam.org")) {
            val siamStopMarkers = listOf(
                "publish with siam",
                "join siam",
                "siam news blog",
                "latest news",
                "featured articles"
            )
            if (siamStopMarkers.any { normalized.startsWith(it) || normalized == it }) {
                return true
            }
        }

        return tagName.startsWith("h") && normalized.length < 80 && genericStopMarkers.any { normalized.contains(it) }
    }

    private fun isStrongWebFallback(blocks: List<ReaderBlock>): Boolean {
        return blocks.count { it.type == ReaderBlockType.Paragraph } >= 3 &&
            blocks.sumOf { it.text.length } >= 500
    }

    private fun scoreWebBlockSet(blocks: List<ReaderBlock>): Double {
        if (blocks.isEmpty()) {
            return Double.NEGATIVE_INFINITY
        }

        val paragraphs = blocks.filter { it.type == ReaderBlockType.Paragraph }
        val headings = blocks.filter { it.type == ReaderBlockType.Heading }
        val paragraphChars = paragraphs.sumOf { it.text.length }
        val shortParagraphCount = paragraphs.count { it.text.length < 80 }
        val veryShortBlockCount = blocks.count { it.text.length < 24 }
        val boilerplateCount = blocks.count { looksLikeWebBoilerplateTextForHeuristics(it.text, it.type) }
        val headingHeavyTailCount = headings.count { heading ->
            val lower = heading.text.lowercase(Locale.US)
            listOf("related", "more from", "subscribe", "newsletter", "comments").any { lower.contains(it) }
        }

        return paragraphChars.toDouble() +
            paragraphs.size * 180.0 +
            headings.size * 24.0 -
            shortParagraphCount * 120.0 -
            veryShortBlockCount * 220.0 -
            boilerplateCount * 320.0 -
            headingHeavyTailCount * 260.0 -
            maxOf(0, headings.size - paragraphs.size) * 45.0
    }

    private fun selectCandidateWebContentContainers(parsed: org.jsoup.nodes.Document): List<Element> {
        val body = parsed.body() ?: return listOf(parsed)
        val scoredCandidates = buildList {
            add(body)
            addAll(parsed.select("article, main, section, div"))
        }
            .filter { element ->
                val textLength = normalizeWebBlockText(element.text()).length
                textLength >= 250 && !looksLikeNoisyWebContainer(element)
            }
            .distinctBy { element ->
                val normalizedText = normalizeWebDedupeKeyForHeuristics(element.text().take(400))
                buildString {
                    append(element.tagName())
                    append('|')
                    append(element.id())
                    append('|')
                    append(element.className())
                    append('|')
                    append(normalizedText)
                }
            }
            .sortedByDescending(::scoreWebContentContainer)

        val selected = mutableListOf<Element>()
        for (candidate in scoredCandidates) {
            if (selected.size >= 4) {
                break
            }
            val candidateTextLength = normalizeWebBlockText(candidate.text()).length
            val duplicatesExisting = selected.any { existing ->
                val existingTextLength = normalizeWebBlockText(existing.text()).length
                val sameLineage = candidate.parents().contains(existing) || existing.parents().contains(candidate)
                sameLineage && minOf(candidateTextLength, existingTextLength) >= (maxOf(candidateTextLength, existingTextLength) * 0.8)
            }
            if (!duplicatesExisting) {
                selected += candidate
            }
        }

        return selected.ifEmpty { listOf(selectBestWebContentContainer(parsed)) }
    }

    private fun decodeEmbeddedBodyHtml(html: String): String? {
        val encodedCandidates = buildList {
            addAll(
                extractJsonStringFieldValues(html, "\"body_html\"")
            )
            addAll(
                Regex("""\\\"body_html\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\"])*)\\\"""")
                    .findAll(html)
                    .map { it.groupValues[1] }
            )
        }

        return encodedCandidates
            .mapNotNull { encoded ->
                runCatching {
                    JSONObject("""{"body":"$encoded"}""").getString("body")
                }.getOrNull()
            }
            .filter { it.isNotBlank() }
            .maxByOrNull { it.length }
    }

    private fun extractJsonStringFieldValues(source: String, fieldToken: String): List<String> {
        val matches = mutableListOf<String>()
        var searchIndex = 0
        while (true) {
            val fieldIndex = source.indexOf(fieldToken, startIndex = searchIndex)
            if (fieldIndex < 0) {
                break
            }

            var cursor = fieldIndex + fieldToken.length
            while (cursor < source.length && source[cursor].isWhitespace()) {
                cursor++
            }
            if (cursor >= source.length || source[cursor] != ':') {
                searchIndex = fieldIndex + fieldToken.length
                continue
            }

            cursor++
            while (cursor < source.length && source[cursor].isWhitespace()) {
                cursor++
            }
            if (cursor >= source.length || source[cursor] != '"') {
                searchIndex = fieldIndex + fieldToken.length
                continue
            }

            cursor++
            val valueStart = cursor
            var escaping = false
            while (cursor < source.length) {
                val current = source[cursor]
                if (escaping) {
                    escaping = false
                } else if (current == '\\') {
                    escaping = true
                } else if (current == '"') {
                    matches += source.substring(valueStart, cursor)
                    cursor++
                    break
                }
                cursor++
            }
            searchIndex = cursor.coerceAtLeast(fieldIndex + fieldToken.length)
        }
        return matches
    }

    private fun selectWebContentElements(container: Element): List<Element> {
        val selected = linkedSetOf<Element>()
        container.select("h1, h2, h3, h4, p, li, blockquote, pre, div").forEach { element ->
            val tagName = element.tagName().lowercase(Locale.US)
            val parentTag = element.parent()?.tagName()?.lowercase(Locale.US)
            val keep = when (tagName) {
                "li" -> true
                "blockquote" -> true
                "pre" -> true
                "div" -> isParagraphLikeWebDiv(element)
                else -> parentTag !in setOf("blockquote", "li")
            }
            if (keep) {
                selected += element
            }
        }
        return selected.toList()
    }

    private fun isParagraphLikeWebDiv(element: Element): Boolean {
        if (looksLikeWebUiElementMarker(element) || looksLikeNoisyWebContainer(element)) {
            return false
        }

        val blockedDescendantTags = setOf(
            "article", "main", "section", "nav", "aside", "ul", "ol", "p", "blockquote",
            "pre", "table", "figure", "figcaption", "footer", "header"
        )
        if (element.getAllElements().drop(1).any { descendant ->
                descendant.tagName().lowercase(Locale.US) in blockedDescendantTags
            }
        ) {
            return false
        }

        if (element.children().count { child ->
                child.tagName().equals("div", ignoreCase = true) &&
                    normalizeWebBlockText(child.text()).length >= 40
            } >= 1
        ) {
            return false
        }

        val sanitizedElement = sanitizeWebExtractionElement(element)
        val text = normalizeWebElementText(sanitizedElement)
        val wordCount = text.split(Regex("""\s+""")).count { it.isNotBlank() }
        if (text.length < 45 || wordCount < 8) {
            return false
        }

        val linkTextLength = normalizeWebBlockText(sanitizedElement.select("a").text()).length
        val linkDensity = if (text.isBlank()) 1.0 else linkTextLength.toDouble() / text.length.toDouble()
        if (linkDensity > 0.72) {
            return false
        }

        return text.any { it.isLowerCase() } &&
            (text.contains('.') || text.contains('!') || text.contains('?') || text.contains(','))
    }

    private fun selectBestWebContentContainer(parsed: org.jsoup.nodes.Document): Element {
        val body = parsed.body() ?: return parsed
        parsed.selectFirst("article")
            ?.takeIf {
                it.select("p").size >= 3 &&
                    normalizeWebBlockText(it.text()).length >= 500
            }
            ?.let { return it }
        val candidates = buildList {
            add(body)
            addAll(parsed.select("article, main, section, div"))
        }

        return candidates
            .filter { it.text().isNotBlank() }
            .maxByOrNull(::scoreWebContentContainer)
            ?: body
    }

    private fun cleanWebContentContainer(container: Element): Element {
        val cleaned = container.clone()
        cleaned.select(
            """
            .related, .recommended, .newsletter, .signup, .subscribe, .comments, .comment,
            .social, .share, .advertisement, .ad, .promo, .cookie, .breadcrumb, .sidebar,
            .post-footer, .single-post-section, .footer-wrap, #discussion,
            [role=complementary], [role=navigation],
            [data-testid*=share], [data-testid*=sidebar], [data-testid*=newsletter], [data-testid*=comments]
            """.trimIndent().replace("\n", " ")
        ).remove()
        cleaned.select("*").forEach { element ->
            if (!isStructuralWebCleanupElement(element)) {
                return@forEach
            }
            val sanitizedElement = sanitizeWebExtractionElement(element)
            val textLength = normalizeWebBlockText(sanitizedElement.text()).length
            val linkTextLength = normalizeWebBlockText(sanitizedElement.select("a").text()).length
            val linkDensity = if (textLength == 0) 0.0 else linkTextLength.toDouble() / textLength.toDouble()
            if (
                element !== cleaned &&
                (
                    textLength == 0 ||
                        looksLikeNoisyWebContainer(element) ||
                        (linkDensity > 0.72 && textLength < 700)
                    )
            ) {
                element.remove()
            }
        }
        return cleaned
    }

    private fun isStructuralWebCleanupElement(element: Element): Boolean {
        val tagName = element.tagName().lowercase(Locale.US)
        return tagName in setOf(
            "article", "main", "section", "div", "aside", "nav", "ul", "ol", "figure",
            "header", "footer"
        )
    }

    private fun scoreWebContentContainer(element: Element): Double {
        val tagName = element.tagName().lowercase(Locale.US)
        val classId = buildString {
            append(element.className())
            append(' ')
            append(element.id())
        }.lowercase(Locale.US)

        val paragraphCount = element.select("p").size
        val headingCount = element.select("h1, h2, h3, h4").size
        val listCount = element.select("li").size
        val textLength = normalizeWebBlockText(element.text()).length
        val linkTextLength = normalizeWebBlockText(element.select("a").text()).length
        val linkDensity = if (textLength == 0) 1.0 else linkTextLength.toDouble() / textLength.toDouble()

        var score = paragraphCount * 14.0
        score += headingCount * 8.0
        score += minOf(listCount, 12) * 2.0
        score += minOf(textLength, 12000) / 18.0

        if (tagName == "article") score += 90.0
        if (tagName == "main") score += 75.0
        if ("article" in classId || "content" in classId || "post" in classId || "story" in classId) {
            score += 40.0
        }
        if ("body" in classId || "entry" in classId || "markdown" in classId) {
            score += 24.0
        }

        if (linkDensity > 0.55) score -= 90.0
        else if (linkDensity > 0.35) score -= 35.0

        if (looksLikeNoisyWebContainer(element)) {
            score -= 220.0
        }

        return score
    }

    private fun extractWebMetadataBlocks(
        parsed: org.jsoup.nodes.Document,
        titleText: String
    ): List<ReaderBlock> {
        val metadata = linkedSetOf<String>()

        parsed.selectFirst("meta[name=author]")?.attr("content")
            ?.let(::normalizeWebBlockText)
            ?.takeIf { isUsableWebMetadataText(it, titleText) }
            ?.let { metadata += context.getString(R.string.by_prefix, it) }

        parsed.selectFirst("meta[property=article:published_time], meta[name=article:published_time]")
            ?.attr("content")
            ?.substringBefore('T')
            ?.let(::normalizeWebBlockText)
            ?.takeIf { it.isNotBlank() }
            ?.let { metadata += context.getString(R.string.published_prefix, it) }

        parsed.selectFirst("meta[name=description], meta[property=og:description], meta[name=twitter:description]")
            ?.attr("content")
            ?.let(::normalizeWebBlockText)
            ?.takeIf { it.length in 40..220 && !it.equals(titleText, ignoreCase = true) }
            ?.let { metadata += it }

        parsed.select(
            """
            [rel=author], a[rel=author], [itemprop=author], [itemprop=datePublished], time,
            [class*=byline], [class*=author], [class*=dek], [class*=standfirst], [class*=subtitle],
            [data-testid*=byline], [data-testid*=author], [data-testid*=subtitle]
            """.trimIndent().replace("\n", " ")
        ).forEach { element ->
            val text = normalizeWebBlockText(element.text())
            if (isUsableWebMetadataText(text, titleText)) {
                metadata += text
            }
        }

        return metadata
            .take(5)
            .map { ReaderBlock(ReaderBlockType.Metadata, it) }
    }

    private fun looksLikeNoisyWebContainer(element: Element): Boolean {
        return looksLikeNoisyWebContainerForHeuristics(element)
    }

    private fun looksLikeBrowserChallengeHtml(html: String): Boolean {
        return looksLikeBrowserChallengeHtmlForHeuristics(html)
    }

    private fun normalizeWebBlockText(text: String): String {
        return normalizeWebBlockTextForHeuristics(text)
    }

    private fun normalizeWebElementText(element: Element): String {
        val source = sanitizeWebExtractionElement(element)
        val normalized = when (source.tagName().lowercase(Locale.US)) {
            "blockquote" -> {
                source.select("p")
                    .map { paragraph -> normalizeWebBlockText(paragraph.text()) }
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                    .ifBlank { normalizeWebBlockText(source.text()) }
            }

            "pre" -> {
                source.textNodes()
                    .joinToString("\n") { it.text() }
                    .ifBlank { source.wholeText() }
                    .let(::normalizeWebBlockText)
            }

            else -> normalizeWebBlockText(source.text())
        }
        return normalized
    }

    private fun isUsableWebMetadataText(text: String, titleText: String): Boolean {
        if (text.isBlank() || text.equals(titleText, ignoreCase = true)) {
            return false
        }

        if (text.length < 3 || text.length > 220) {
            return false
        }

        val lower = text.lowercase(Locale.US)
        if (
            lower.startsWith("share") ||
            lower.startsWith("subscribe") ||
            lower.startsWith("read more") ||
            lower.startsWith("advertisement")
        ) {
            return false
        }

        return true
    }

    private fun isUsableWebBlockText(text: String, tagName: String, element: Element? = null): Boolean {
        if (text.isBlank()) {
            return false
        }

        val normalizedTag = tagName.lowercase(Locale.US)
        if (element != null && looksLikeWebUiElementMarker(element)) {
            return false
        }
        val wordCount = text.split(' ').count { it.isNotBlank() }
        if (normalizedTag.startsWith("h")) {
            return wordCount in 1..18 && text.length in 4..140
        }

        if (text.length < 45 && wordCount < 8) {
            return false
        }

        val maxTextLength = when (normalizedTag) {
            "blockquote" -> Int.MAX_VALUE
            "div" -> 4000
            else -> 1200
        }
        if (text.length > maxTextLength) {
            return false
        }

        if (text.count { it.isLetterOrDigit() }.toDouble() / text.length.toDouble() < 0.55) {
            return false
        }

        val lower = text.lowercase(Locale.US)
        val noisyStarts = listOf(
            "share this", "sign up", "subscribe", "read more", "advertisement",
            "cookie policy", "all rights reserved", "follow us", "related articles"
        )
        if (noisyStarts.any { lower.startsWith(it) }) {
            return false
        }

        if (looksLikeActionOrUtilityLine(text, ReaderBlockType.Paragraph)) {
            return false
        }

        return true
    }

    private fun shouldUseSentenceFallbackForWebExtraction(
        blocks: List<ReaderBlock>,
        fallbackSourceText: String
    ): Boolean {
        val normalizedSourceText = normalizeWebBlockText(fallbackSourceText)
        if (normalizedSourceText.isBlank()) {
            return blocks.isEmpty()
        }

        if (blocks.isEmpty()) {
            return true
        }

        if (normalizedSourceText.length < 700) {
            return false
        }

        val extractedParagraphCount = blocks.count { it.type == ReaderBlockType.Paragraph }
        val extractedChars = blocks.sumOf { it.text.length }
        val extractionRatio = extractedChars.toDouble() / normalizedSourceText.length.toDouble()

        return extractedParagraphCount < 3 || extractionRatio < 0.45
    }

    private fun buildSentenceFallbackWebBlocks(
        source: Element,
        titleText: String,
        metadataTexts: List<String>
    ): List<ReaderBlock> {
        val fallbackParagraphs = source.text()
            .split(Regex("(?<=[.!?])\\s+"))
            .map(::normalizeWebBlockText)
            .filter { isUsableWebBlockText(it, "p") }
            .take(200)
            .map { ReaderBlock(ReaderBlockType.Paragraph, it) }

        return cleanupExtractedWebBlocksForHeuristics(
            blocks = fallbackParagraphs,
            titleText = titleText,
            metadataTexts = metadataTexts
        )
    }

    private fun looksLikeWebUiElementMarker(element: Element): Boolean {
        val marker = normalizeWebMarkerText(
            element.className(),
            element.id(),
            element.attr("role"),
            element.attr("aria-label"),
            element.attr("data-testid")
        )

        if (marker.isBlank()) {
            return false
        }

        val phrases = listOf(
            "share", "social", "comment", "comments", "discussion", "reply", "footer", "site index",
            "site info", "site information", "navigation", "nav", "toolbar", "menu", "actions",
            "button", "cta", "related", "recommended", "newsletter", "subscribe", "signin",
            "sign in", "signup", "sign up", "breadcrumb", "safeframe", "ad slot", "advert",
            "advertisement", "sponsored"
        )

        return phrases.any { phrase -> webMarkerContainsPhrase(marker, phrase) }
    }

    private fun normalizeHistoryToken(text: String): String {
        return text
            .lowercase(Locale.US)
            .replace(Regex("""\s+"""), " ")
            .replace(Regex("""[^a-z0-9 ]"""), "")
            .trim()
    }

    private fun readBytesWithLimit(input: java.io.InputStream, maxBytes: Int): ByteArray {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE * 4))
        var totalRead = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) {
                break
            }
            totalRead += read
            if (totalRead > maxBytes.toLong()) {
                error(pdfTooLargeMessage())
            }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun pdfTooLargeMessage(): String {
        return context.getString(
            R.string.error_pdf_too_large,
            android.text.format.Formatter.formatFileSize(context, MAX_PDF_BYTES.toLong())
        )
    }

    private fun requireSupportedRemoteUrl(urlString: String) {
        val scheme = runCatching { Uri.parse(urlString).scheme?.lowercase(Locale.US) }.getOrNull()
        if (scheme != "http" && scheme != "https") {
            error(context.getString(R.string.error_only_http_https_urls))
        }
    }
}

internal data class CapturedTextReaderContent(
    val title: String,
    val metadataBlocks: List<ReaderBlock>,
    val blocks: List<ReaderBlock>
)

internal fun parseCapturedTextReaderContent(
    rawText: String,
    providedTitle: String?,
    fallbackTitle: String
): CapturedTextReaderContent {
    val paragraphs = splitCapturedTextIntoParagraphs(rawText)
    val explicitTitle = providedTitle
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }

    val contentParagraphs = paragraphs.toMutableList()
    val title = when {
        explicitTitle != null -> {
            if (
                contentParagraphs.firstOrNull()?.let(::normalizeCapturedTextToken) ==
                normalizeCapturedTextToken(explicitTitle)
            ) {
                contentParagraphs.removeAt(0)
            }
            explicitTitle
        }

        contentParagraphs.firstOrNull()?.let(::looksLikeCapturedTextTitle) == true -> {
            contentParagraphs.removeAt(0)
        }

        else -> fallbackTitle
    }

    val metadataBlocks = mutableListOf<ReaderBlock>()
    while (contentParagraphs.isNotEmpty() && metadataBlocks.size < 3) {
        val candidate = contentParagraphs.first()
        if (!looksLikeCapturedTextMetadata(candidate)) {
            break
        }
        metadataBlocks += ReaderBlock(ReaderBlockType.Metadata, candidate)
        contentParagraphs.removeAt(0)
    }

    val blocks = contentParagraphs
        .filter { it.isNotBlank() }
        .map { paragraph ->
            if (looksLikeCapturedTextHeading(paragraph)) {
                ReaderBlock(ReaderBlockType.Heading, paragraph.removeSuffix(":"))
            } else {
                ReaderBlock(ReaderBlockType.Paragraph, paragraph)
            }
        }

    return CapturedTextReaderContent(
        title = title,
        metadataBlocks = metadataBlocks,
        blocks = if (blocks.isNotEmpty()) {
            blocks
        } else {
            listOf(
                ReaderBlock(
                    ReaderBlockType.Paragraph,
                    splitCapturedTextIntoParagraphs(rawText).joinToString("\n\n").ifBlank { fallbackTitle }
                )
            )
        }
    )
}

internal fun splitCapturedTextIntoParagraphs(rawText: String): List<String> {
    val normalized = rawText
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace('\u00A0', ' ')
        .replace(Regex("""\n{3,}"""), "\n\n")
        .trim()

    if (normalized.isBlank()) {
        return emptyList()
    }

    return normalized
        .split(Regex("""\n\s*\n+"""))
        .mapNotNull { chunk ->
            joinCapturedTextLines(
                chunk.lines().map { it.trim() }.filter { it.isNotBlank() }
            ).takeIf { it.isNotBlank() }
        }
}

private fun joinCapturedTextLines(lines: List<String>): String {
    if (lines.isEmpty()) {
        return ""
    }

    val builder = StringBuilder(lines.first())
    for (index in 1 until lines.size) {
        val nextLine = lines[index]
        val current = builder.toString()
        when {
            current.endsWith("-") && nextLine.firstOrNull()?.isLowerCase() == true -> {
                builder.deleteCharAt(builder.lastIndex)
                builder.append(nextLine)
            }

            current.endsWith("/") || current.endsWith("—") || current.endsWith("–") -> {
                builder.append(nextLine)
            }

            else -> {
                builder.append(' ')
                builder.append(nextLine)
            }
        }
    }

    return builder.toString()
        .replace(Regex("\\s+"), " ")
        .replace(Regex("""\s+([,.;:!?])"""), "$1")
        .trim()
}

private fun looksLikeCapturedTextTitle(text: String): Boolean {
    val normalized = text.trim()
    if (normalized.length !in 4..140) {
        return false
    }

    val wordCount = normalized.split(Regex("\\s+")).count()
    if (wordCount !in 1..18) {
        return false
    }

    if (normalized.endsWith(".") || normalized.endsWith("?") || normalized.endsWith("!")) {
        return false
    }

    return normalized.any { it.isUpperCase() }
}

private fun looksLikeCapturedTextMetadata(text: String): Boolean {
    val normalized = text.trim()
    if (normalized.length !in 3..220) {
        return false
    }

    val lower = normalized.lowercase(Locale.US)
    if (lower.startsWith("abstract") || lower.startsWith("introduction")) {
        return false
    }

    val metadataMarkers = listOf(
        "@", "university", "department", "institute", "college", "laboratory", "lab",
        "school", "correspondence", "affiliation", "author", "authors", "by "
    )

    if (metadataMarkers.any { lower.contains(it) }) {
        return true
    }

    val wordCount = normalized.split(Regex("\\s+")).count()
    return wordCount <= 20 &&
        normalized.count { it == ',' || it == ';' } >= 1 &&
        normalized.count { it.isUpperCase() } >= 2 &&
        !normalized.endsWith(".")
}

private fun looksLikeCapturedTextHeading(text: String): Boolean {
    val normalized = text.trim()
    if (normalized.length !in 3..110) {
        return false
    }

    val wordCount = normalized.split(Regex("\\s+")).count()
    if (wordCount !in 1..14) {
        return false
    }

    if (normalized.endsWith(".") || normalized.endsWith("?") || normalized.endsWith("!")) {
        return false
    }

    val lower = normalized.lowercase(Locale.US)
    if (lower.startsWith("http://") || lower.startsWith("https://")) {
        return false
    }

    return normalized.any { it.isUpperCase() || it.isDigit() }
}

private fun normalizeCapturedTextToken(text: String): String {
    return text
        .lowercase(Locale.US)
        .replace(Regex("""\s+"""), " ")
        .replace(Regex("""[^a-z0-9 ]"""), "")
        .trim()
}
