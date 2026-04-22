package org.read.mobile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class PlaybackUiState(
    val currentDocumentId: String? = null,
    val currentTitle: String = "Read!",
    val isReady: Boolean = false,
    val isSpeaking: Boolean = false,
    val hasSegments: Boolean = false,
    val speed: Float = 1.0f,
    val currentSegmentIndex: Int = 0,
    val currentCharOffset: Int = 0,
    val currentCharEnd: Int = 0,
    val currentBlockIndex: Int = -1,
    val currentSegmentRange: IntRange? = null,
    val manualNavigationVersion: Long = 0L,
    val statusMessage: String? = null
)

data class SpeechSegment(
    val text: String,
    val blockIndex: Int,
    val startOffset: Int,
    val endOffset: Int
)

private const val ESTIMATED_TTS_WORDS_PER_MINUTE_AT_1X = 170f

object ReaderPlaybackStore {
    private val lock = Any()
    private val _uiState = MutableStateFlow(PlaybackUiState())
    val uiState: StateFlow<PlaybackUiState> = _uiState.asStateFlow()

    private var segments: List<SpeechSegment> = emptyList()
    private var cumulativeSegmentUnits: IntArray = intArrayOf(0)
    private var cumulativeSegmentWordUnits: IntArray = intArrayOf(0)
    private var totalSegmentUnits: Int = 0
    private var totalSegmentWordUnits: Int = 0

    private fun setUiStateLocked(transform: (PlaybackUiState) -> PlaybackUiState) {
        _uiState.value = withCurrentPlaybackVisualStateLocked(transform(_uiState.value))
    }

    private fun withCurrentPlaybackVisualStateLocked(state: PlaybackUiState): PlaybackUiState {
        val segment = segments.getOrNull(state.currentSegmentIndex)
        val blockIndex = segment?.blockIndex ?: -1
        val segmentRange = segment?.let {
            val safeStart = state.currentCharOffset.coerceIn(0, it.text.length)
            val safeEnd = state.currentCharEnd.coerceIn(safeStart, it.text.length)
            if (safeEnd > safeStart) {
                (it.startOffset + safeStart) until (it.startOffset + safeEnd)
            } else {
                (it.startOffset + safeStart).coerceAtMost(it.endOffset) until it.endOffset
            }
        }
        return if (
            state.currentBlockIndex == blockIndex &&
            state.currentSegmentRange == segmentRange
        ) {
            state
        } else {
            state.copy(
                currentBlockIndex = blockIndex,
                currentSegmentRange = segmentRange
            )
        }
    }

    fun loadDocument(documentId: String, title: String, blocks: List<ReaderBlock>) {
        synchronized(lock) {
            if (_uiState.value.currentDocumentId == documentId && segments.isNotEmpty()) {
                return
            }

            segments = buildSpeechSegments(blocks)
            rebuildSegmentCaches()
            setUiStateLocked {
                it.copy(
                    currentDocumentId = documentId,
                    currentTitle = title,
                    currentSegmentIndex = 0,
                    currentCharOffset = 0,
                    currentCharEnd = 0,
                    manualNavigationVersion = 0L,
                    hasSegments = segments.isNotEmpty(),
                    isSpeaking = false,
                    statusMessage = null
                )
            }
        }
    }

    fun ensureSegments(blocks: List<ReaderBlock>) {
        synchronized(lock) {
            if (segments.isNotEmpty()) {
                return
            }
            segments = buildSpeechSegments(blocks)
            rebuildSegmentCaches()
            setUiStateLocked { it.copy(hasSegments = segments.isNotEmpty()) }
        }
    }

    fun clearStatus() {
        _uiState.update { it.copy(statusMessage = null) }
    }

    fun markReady(ready: Boolean) {
        _uiState.update { it.copy(isReady = ready) }
    }

    fun setSpeaking(speaking: Boolean) {
        _uiState.update { it.copy(isSpeaking = speaking) }
    }

    fun setStatus(message: String?) {
        _uiState.update { it.copy(statusMessage = message) }
    }

    fun setSpeed(speed: Float) {
        _uiState.update { it.copy(speed = speed) }
    }

    fun setCurrentSegmentIndex(index: Int) {
        synchronized(lock) {
            val clamped = when {
                segments.isEmpty() -> 0
                else -> index.coerceIn(0, segments.lastIndex)
            }
            setUiStateLocked {
                it.copy(
                    currentSegmentIndex = clamped,
                    currentCharOffset = 0,
                    currentCharEnd = 0,
                    manualNavigationVersion = it.manualNavigationVersion + 1
                )
            }
        }
    }

    fun setCurrentBlockAndOffset(blockIndex: Int, charOffset: Int) {
        synchronized(lock) {
            if (segments.isEmpty()) {
                setUiStateLocked {
                    it.copy(
                        currentSegmentIndex = 0,
                        currentCharOffset = 0,
                        currentCharEnd = 0,
                        manualNavigationVersion = it.manualNavigationVersion + 1
                    )
                }
                return
            }

            val clampedOffset = charOffset.coerceAtLeast(0)
            val exactIndex = segments.indexOfFirst { segment ->
                segment.blockIndex == blockIndex &&
                    clampedOffset >= segment.startOffset &&
                    clampedOffset < segment.endOffset
            }

            val selectedIndex = when {
                exactIndex >= 0 -> exactIndex
                else -> {
                    val blockSegments = segments.withIndex()
                        .filter { it.value.blockIndex == blockIndex }
                    when {
                        blockSegments.isEmpty() -> indexForBlock(blockIndex)
                        else -> blockSegments
                            .lastOrNull { clampedOffset >= it.value.startOffset }
                            ?.index
                            ?: blockSegments.first().index
                    }
                }
            }.coerceIn(0, segments.lastIndex)

            val selectedSegment = segments[selectedIndex]
            val maxOffset = (selectedSegment.text.length - 1).coerceAtLeast(0)
            val offsetWithinSegment = (clampedOffset - selectedSegment.startOffset)
                .coerceIn(0, maxOffset)

            setUiStateLocked {
                it.copy(
                    currentSegmentIndex = selectedIndex,
                    currentCharOffset = offsetWithinSegment,
                    currentCharEnd = offsetWithinSegment,
                    manualNavigationVersion = it.manualNavigationVersion + 1
                )
            }
        }
    }

    fun markRange(start: Int, end: Int) {
        synchronized(lock) {
            val currentState = _uiState.value
            val segment = segments.getOrNull(currentState.currentSegmentIndex) ?: return
            val clampedStart = start.coerceIn(0, segment.text.length)
            val clampedEnd = end.coerceIn(clampedStart, segment.text.length)
            if (
                currentState.currentCharOffset == clampedStart &&
                currentState.currentCharEnd == clampedEnd
            ) {
                return
            }
            setUiStateLocked {
                it.copy(
                    currentCharOffset = clampedStart,
                    currentCharEnd = clampedEnd
                )
            }
        }
    }

    fun currentPlaybackText(): String? {
        synchronized(lock) {
            val state = _uiState.value
            val segment = segments.getOrNull(state.currentSegmentIndex) ?: return null
            val offset = state.currentCharOffset.coerceIn(0, segment.text.length)
            return segment.text.substring(offset).takeIf { it.isNotBlank() }
        }
    }

    fun indexForBlock(blockIndex: Int): Int {
        synchronized(lock) {
            return segments.indexOfFirst { it.blockIndex >= blockIndex }
                .takeIf { it >= 0 }
                ?: segments.lastIndex.coerceAtLeast(0)
        }
    }

    fun indexForBlockAndOffset(blockIndex: Int, charOffset: Int): Int {
        synchronized(lock) {
            val clampedOffset = charOffset.coerceAtLeast(0)
            val exactMatch = segments.indexOfFirst { segment ->
                segment.blockIndex == blockIndex &&
                    clampedOffset >= segment.startOffset &&
                    clampedOffset < segment.endOffset
            }
            if (exactMatch >= 0) {
                return exactMatch
            }

            val firstInBlock = segments.indexOfFirst { it.blockIndex == blockIndex }
            if (firstInBlock >= 0) {
                return firstInBlock
            }

            return segments.indexOfFirst { it.blockIndex >= blockIndex }
                .takeIf { it >= 0 }
                ?: segments.lastIndex.coerceAtLeast(0)
        }
    }

    fun segmentForBlockAndOffset(blockIndex: Int, charOffset: Int): SpeechSegment? {
        synchronized(lock) {
            val clampedOffset = charOffset.coerceAtLeast(0)
            return segments.firstOrNull { segment ->
                segment.blockIndex == blockIndex &&
                    clampedOffset >= segment.startOffset &&
                    clampedOffset < segment.endOffset
            } ?: segments.firstOrNull { it.blockIndex == blockIndex }
        }
    }

    fun currentSegment(): SpeechSegment? {
        synchronized(lock) {
            val state = _uiState.value
            return segments.getOrNull(state.currentSegmentIndex)
        }
    }

    fun currentSegmentText(): String? = currentSegment()?.text

    fun currentSegmentRange(): IntRange? = _uiState.value.currentSegmentRange

    fun currentPlaybackOffsetInBlock(): Int? {
        val segment = currentSegment() ?: return null
        val state = _uiState.value
        return (segment.startOffset + state.currentCharOffset).coerceIn(segment.startOffset, segment.endOffset)
    }

    fun currentBlockIndex(): Int = _uiState.value.currentBlockIndex

    fun advanceToNextSegment(): Boolean {
        synchronized(lock) {
            val nextIndex = _uiState.value.currentSegmentIndex + 1
            return if (nextIndex < segments.size) {
                setUiStateLocked { it.copy(currentSegmentIndex = nextIndex, currentCharOffset = 0, currentCharEnd = 0) }
                true
            } else {
                setUiStateLocked {
                    it.copy(
                        currentSegmentIndex = 0,
                        currentCharOffset = 0,
                        currentCharEnd = 0,
                        isSpeaking = false
                    )
                }
                false
            }
        }
    }

    fun hasSegments(): Boolean = synchronized(lock) { segments.isNotEmpty() }

    fun segmentCount(): Int = synchronized(lock) { segments.size }

    fun progressFraction(): Float {
        synchronized(lock) {
            if (segments.isEmpty() || totalSegmentUnits <= 0) {
                return 0f
            }

            val state = _uiState.value
            val currentIndex = state.currentSegmentIndex.coerceIn(0, segments.lastIndex)
            val currentSegment = segments[currentIndex]
            val consumedUnits = cumulativeSegmentUnits[currentIndex] +
                state.currentCharOffset.coerceIn(0, currentSegment.text.length)

            return (consumedUnits.toFloat() / totalSegmentUnits.toFloat()).coerceIn(0f, 1f)
        }
    }

    fun progressLabel(): String {
        synchronized(lock) {
            if (segments.isEmpty()) {
                return "0%"
            }
            val percent = (progressFraction() * 100f).toInt().coerceIn(0, 100)
            val remainingLabel = estimatedRemainingTimeLabel(_uiState.value)
            return if (remainingLabel == null) {
                "$percent%"
            } else {
                "$percent% \u00b7 $remainingLabel"
            }
        }
    }

    fun seekToProgressFraction(fraction: Float) {
        synchronized(lock) {
            if (segments.isEmpty()) {
                return
            }

            if (totalSegmentUnits <= 0) {
                setUiStateLocked { it.copy(currentSegmentIndex = 0, currentCharOffset = 0, currentCharEnd = 0) }
                return
            }

            val targetUnits = ((fraction.coerceIn(0f, 1f) * totalSegmentUnits).toInt())
                .coerceIn(0, (totalSegmentUnits - 1).coerceAtLeast(0))
            val targetIndex = findSegmentIndexForConsumedUnits(targetUnits)
            val targetSegment = segments[targetIndex]
            val offset = (targetUnits - cumulativeSegmentUnits[targetIndex])
                .coerceIn(0, targetSegment.text.length.coerceAtLeast(0))
            setUiStateLocked {
                it.copy(
                    currentSegmentIndex = targetIndex,
                    currentCharOffset = offset,
                    currentCharEnd = offset,
                    manualNavigationVersion = it.manualNavigationVersion + 1
                )
            }
        }
    }

    private fun segmentUnits(segment: SpeechSegment): Int = segment.text.length.coerceAtLeast(1)

    private fun estimatedRemainingTimeLabel(state: PlaybackUiState): String? {
        if (segments.isEmpty()) {
            return null
        }

        if (totalSegmentWordUnits <= 0) {
            return null
        }

        val currentIndex = state.currentSegmentIndex.coerceIn(0, segments.lastIndex)
        var consumedWordUnits = cumulativeSegmentWordUnits[currentIndex].toFloat()

        val currentSegment = segments[currentIndex]
        val currentSegmentUnits =
            cumulativeSegmentWordUnits[currentIndex + 1] - cumulativeSegmentWordUnits[currentIndex]
        if (currentSegmentUnits > 0) {
            val segmentProgressFraction = if (currentSegment.text.isEmpty()) {
                0f
            } else {
                state.currentCharOffset
                    .coerceIn(0, currentSegment.text.length)
                    .toFloat() / currentSegment.text.length.toFloat()
            }
            consumedWordUnits += currentSegmentUnits * segmentProgressFraction
        }

        val remainingWordUnits = (totalSegmentWordUnits.toFloat() - consumedWordUnits).coerceAtLeast(0f)
        val effectiveSpeed = state.speed.coerceAtLeast(0.1f)
        val remainingMinutes = remainingWordUnits / (ESTIMATED_TTS_WORDS_PER_MINUTE_AT_1X * effectiveSpeed)
        return formatRemainingReadingTime(remainingMinutes)
    }

    private fun segmentWordUnits(segment: SpeechSegment): Int {
        return Regex("""\b[\p{L}\p{N}][\p{L}\p{N}'’\-]*\b""")
            .findAll(segment.text)
            .count()
            .coerceAtLeast(1)
    }

    private fun rebuildSegmentCaches() {
        val count = segments.size
        cumulativeSegmentUnits = IntArray(count + 1)
        cumulativeSegmentWordUnits = IntArray(count + 1)
        var textUnits = 0
        var wordUnits = 0
        segments.forEachIndexed { index, segment ->
            textUnits += segmentUnits(segment)
            wordUnits += segmentWordUnits(segment)
            cumulativeSegmentUnits[index + 1] = textUnits
            cumulativeSegmentWordUnits[index + 1] = wordUnits
        }
        totalSegmentUnits = textUnits
        totalSegmentWordUnits = wordUnits
    }

    private fun findSegmentIndexForConsumedUnits(targetUnits: Int): Int {
        var low = 0
        var high = segments.lastIndex
        while (low <= high) {
            val mid = (low + high).ushr(1)
            val start = cumulativeSegmentUnits[mid]
            val end = cumulativeSegmentUnits[mid + 1]
            when {
                targetUnits < start -> high = mid - 1
                targetUnits >= end -> low = mid + 1
                else -> return mid
            }
        }
        return segments.lastIndex.coerceAtLeast(0)
    }

    private fun formatRemainingReadingTime(minutes: Float): String {
        val totalSeconds = (minutes * 60f).toInt().coerceAtLeast(0)
        return when {
            totalSeconds < 45 -> "<1m left"
            totalSeconds < 3600 -> "${((totalSeconds + 30) / 60).coerceAtLeast(1)}m left"
            else -> {
                val hours = totalSeconds / 3600
                val minutesPart = ((totalSeconds % 3600) + 30) / 60
                if (minutesPart <= 0) {
                    "${hours}h left"
                } else {
                    "${hours}h ${minutesPart}m left"
                }
            }
        }
    }
}

fun buildSpeechSegments(blocks: List<ReaderBlock>): List<SpeechSegment> {
    var skipBackMatter = false
    return buildList {
        blocks.forEachIndexed { index, block ->
            when (block.type) {
                ReaderBlockType.Heading -> {
                    val heading = block.text.trim()
                    if (heading.isBlank()) {
                        return@forEachIndexed
                    }
                    if (isReferenceSectionHeading(heading)) {
                        skipBackMatter = true
                        return@forEachIndexed
                    }
                    if (skipBackMatter) {
                        return@forEachIndexed
                    }
                    add(
                        SpeechSegment(
                            text = "$heading.",
                            blockIndex = index,
                            startOffset = 0,
                            endOffset = heading.length
                        )
                    )
                }

                ReaderBlockType.Paragraph -> {
                    if (skipBackMatter) {
                        return@forEachIndexed
                    }
                    val text = block.text.trim()
                    if (text.isBlank()) {
                        return@forEachIndexed
                    }
                    splitParagraphForSpeech(text).forEach { slice ->
                        val cleaned = cleanSpeechTextForPlayback(slice.text)
                        if (cleaned.isNotBlank()) {
                            add(
                                SpeechSegment(
                                    text = cleaned,
                                    blockIndex = index,
                                    startOffset = slice.startOffset,
                                    endOffset = slice.endOffset
                                )
                            )
                        }
                    }
                }

                ReaderBlockType.Metadata, ReaderBlockType.Footnote -> Unit
            }
        }
    }
}

private data class SpeechSlice(
    val text: String,
    val startOffset: Int,
    val endOffset: Int
)

private fun splitParagraphForSpeech(
    text: String,
    maxChunkLength: Int = 260
): List<SpeechSlice> {
    val normalized = text.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) {
        return emptyList()
    }

    val protectedNormalized = protectSentenceBoundariesInAbbreviations(normalized)
    val sentencePattern = Regex("[^.!?]+[.!?]?")
    val sentences = sentencePattern.findAll(protectedNormalized)
        .mapNotNull { match ->
            val rawSlice = normalized.substring(match.range.first, match.range.last + 1)
            val sentenceText = rawSlice.trim()
            if (sentenceText.isBlank()) {
                null
            } else {
                val leadingTrim = rawSlice.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                val trailingTrimmedLength = rawSlice.trimEnd().length
                SpeechSlice(
                    text = sentenceText,
                    startOffset = match.range.first + leadingTrim,
                    endOffset = match.range.first + trailingTrimmedLength
                )
            }
        }
        .toList()

    if (sentences.isEmpty()) {
        return listOf(
            SpeechSlice(
                text = normalized,
                startOffset = 0,
                endOffset = normalized.length
            )
        )
    }

    return sentences.flatMap { sentence ->
        if (sentence.text.length > maxChunkLength) {
            splitOversizedSentence(sentence, maxChunkLength)
        } else {
            listOf(sentence)
        }
    }.ifEmpty {
        listOf(
            SpeechSlice(
                text = normalized,
                startOffset = 0,
                endOffset = normalized.length
            )
        )
    }
}

private fun splitOversizedSentence(
    sentence: SpeechSlice,
    maxChunkLength: Int
): List<SpeechSlice> {
    val clausePattern = Regex("[^,;:]+[,;:]?")
    val clauses = clausePattern.findAll(sentence.text)
        .mapNotNull { match ->
            val clauseText = match.value.trim()
            if (clauseText.isBlank()) {
                null
            } else {
                val leadingTrim = match.value.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
                val trailingTrimmedLength = match.value.trimEnd().length
                SpeechSlice(
                    text = clauseText,
                    startOffset = sentence.startOffset + match.range.first + leadingTrim,
                    endOffset = sentence.startOffset + match.range.first + trailingTrimmedLength
                )
            }
        }
        .toList()

    if (clauses.size <= 1) {
        return sentence.text.chunked(maxChunkLength)
            .mapIndexedNotNull { index, part ->
                val trimmed = part.trim()
                if (trimmed.isBlank()) {
                    null
                } else {
                    val start = sentence.startOffset + index * maxChunkLength
                    SpeechSlice(
                        text = trimmed,
                        startOffset = start,
                        endOffset = (start + trimmed.length).coerceAtMost(sentence.endOffset)
                    )
                }
        }
    }

    return clauses.flatMap { clause ->
        if (clause.text.length > maxChunkLength) {
            clause.text.chunked(maxChunkLength).mapIndexedNotNull { index, part ->
                val trimmed = part.trim()
                if (trimmed.isBlank()) {
                    null
                } else {
                    val start = clause.startOffset + index * maxChunkLength
                    SpeechSlice(
                        text = trimmed,
                        startOffset = start,
                        endOffset = (start + trimmed.length).coerceAtMost(clause.endOffset)
                    )
                }
            }
        } else {
            listOf(clause)
        }
    }.ifEmpty { listOf(sentence) }
}

private fun cleanSpeechTextForPlayback(text: String): String {
    if (text.isBlank()) {
        return ""
    }

    var cleaned = text

    cleaned = FIXED_SUPERSCRIPT_CITATION_REGEX.replace(cleaned, "")
    cleaned = BRACKETED_CONTENT_REGEX.replace(cleaned) { match ->
        val content = match.groupValues.getOrNull(1).orEmpty()
        if (looksLikeBracketCitation(content)) " " else match.value
    }
    cleaned = PARENTHETICAL_NUMERIC_CITATION_REGEX.replace(cleaned, " ")
    cleaned = PARENTHETICAL_CONTENT_REGEX.replace(cleaned) { match ->
        val content = match.groupValues.getOrNull(1).orEmpty()
        if (looksLikeCitationParenthetical(content)) " " else match.value
    }

    cleaned = cleaned
        .replace(Regex("""\s+([,.;:!?])"""), "$1")
        .replace(Regex("""([,.;:!?])(?:\s*[,.;:!?])+"""), "$1")
        .replace(Regex("""\(\s*\)"""), "")
        .replace(Regex("""\[\s*]"""), "")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()

    return cleaned.takeIf { it.any(Char::isLetterOrDigit) }.orEmpty()
}

private fun isReferenceSectionHeading(text: String): Boolean {
    val normalized = text
        .lowercase()
        .replace(Regex("""^[0-9ivxlcdm.\-\s]+"""), "")
        .replace(Regex("""[^a-z\s]"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()

    return normalized in setOf(
        "references",
        "reference list",
        "bibliography",
        "works cited",
        "citations",
        "references and notes",
        "notes and references",
        "literature cited"
    )
}

private fun looksLikeCitationParenthetical(content: String): Boolean {
    val normalized = content.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) {
        return false
    }
    if (PARENTHETICAL_NUMERIC_ONLY_REGEX.matches(normalized)) {
        return true
    }
    if (!YEAR_REGEX.containsMatchIn(normalized)) {
        return false
    }

    val lower = normalized.lowercase()
    return lower.contains("et al") ||
        lower.startsWith("see ") ||
        lower.startsWith("cf ") ||
        lower.startsWith("cf. ") ||
        lower.startsWith("e.g. ") ||
        lower.startsWith("i.e. ") ||
        lower.startsWith("compare ") ||
        lower.startsWith("contra ") ||
        normalized.contains(';') ||
        AUTHOR_YEAR_CITATION_REGEX.containsMatchIn(normalized) ||
        YEAR_REGEX.findAll(normalized).count() >= 2
}

private val NUMERIC_CITATION_REGEX =
    Regex("""\[\s*\d+(?:\s*[-,;]\s*\d+|\s*(?:–|—)\s*\d+)*\s*]""")

private fun looksLikeBracketCitation(content: String): Boolean {
    val normalized = content.replace(Regex("\\s+"), " ").trim()
    if (normalized.isBlank()) {
        return false
    }
    return BRACKETED_REFERENCE_LIST_REGEX.matches(normalized)
}

private val BRACKETED_CONTENT_REGEX = Regex("""\[([^\[\]]*)]""")

private val PARENTHETICAL_CONTENT_REGEX = Regex("""\(([^()]*)\)""")

private val YEAR_REGEX = Regex("""\b(?:19|20)\d{2}[a-z]?\b""")

private val AUTHOR_YEAR_CITATION_REGEX =
    Regex("""\b[A-Z][A-Za-z'`\-]+(?:\s+(?:and|&)\s+[A-Z][A-Za-z'`\-]+)?(?:\s+et al\.?)?(?:,\s*|\s+)(?:19|20)\d{2}[a-z]?\b""")

private val PARENTHETICAL_NUMERIC_CITATION_REGEX =
    Regex("""\(\s*\d{1,3}(?:\s*[-,;\u2013\u2014]\s*\d{1,3})*\s*\)""")

private val PARENTHETICAL_NUMERIC_ONLY_REGEX =
    Regex("""\d{1,3}(?:\s*[-,;\u2013\u2014]\s*\d{1,3})*""")

private val BRACKETED_REFERENCE_LIST_REGEX =
    Regex("""(?:[\p{L}\d+]*\d[\p{L}\d+]*)(?:\s*[-,;\u2013\u2014]\s*(?:[\p{L}\d+]*\d[\p{L}\d+]*))*""")

private val FIXED_SUPERSCRIPT_CITATION_REGEX =
    Regex("""(?:(?<=\p{L})|(?<=[\]\)\.,;:!?]))[\u00B9\u00B2\u00B3\u2070\u2074-\u2079]+""")

private val SUPERSCRIPT_CITATION_REGEX =
    Regex("""(?:(?<=\p{L})|(?<=[\]\)\.,;:!?]))[¹²³⁴⁵⁶⁷⁸⁹⁰]+""")
