package org.read.mobile

internal enum class SpeechPronunciationHintKind {
    SubstituteText,
    SpellOut
}

internal data class SpeechPronunciationHint(
    val start: Int,
    val end: Int,
    val spokenText: String,
    val kind: SpeechPronunciationHintKind
)

internal data class SpeechOffsetMapping(
    val spokenStart: Int,
    val spokenEnd: Int,
    val originalStart: Int,
    val originalEnd: Int,
    val replaced: Boolean
)

internal data class SpeechPlaybackPlan(
    val spokenText: String,
    val mappings: List<SpeechOffsetMapping>
) {
    fun mapSpokenOffsetToOriginal(offset: Int, preferEnd: Boolean = false): Int {
        if (mappings.isEmpty()) {
            return offset.coerceAtLeast(0)
        }

        val clampedOffset = offset.coerceAtLeast(0)
        val mapping = mappings.firstOrNull { clampedOffset in it.spokenStart..it.spokenEnd }
            ?: mappings.last()

        if (!mapping.replaced) {
            val delta = (clampedOffset - mapping.spokenStart).coerceAtLeast(0)
            return (mapping.originalStart + delta).coerceIn(mapping.originalStart, mapping.originalEnd)
        }

        val originalLength = (mapping.originalEnd - mapping.originalStart).coerceAtLeast(1)
        val spokenLength = (mapping.spokenEnd - mapping.spokenStart).coerceAtLeast(1)
        val relative = (clampedOffset - mapping.spokenStart).toFloat() / spokenLength.toFloat()
        val projected = if (preferEnd) {
            mapping.originalStart + kotlin.math.ceil(relative * originalLength.toDouble()).toInt()
        } else {
            mapping.originalStart + kotlin.math.floor(relative * originalLength.toDouble()).toInt()
        }
        return projected.coerceIn(mapping.originalStart, mapping.originalEnd)
    }
}

internal fun pronunciationHintsForPlayback(text: String): List<SpeechPronunciationHint> {
    if (text.isBlank()) {
        return emptyList()
    }

    val hints = mutableListOf<SpeechPronunciationHint>()

    fun overlaps(start: Int, end: Int): Boolean =
        hints.any { start < it.end && end > it.start }

    COMMON_ABBREVIATION_PRONUNCIATIONS.forEach { rule ->
        rule.regex.findAll(text).forEach { match ->
            val start = match.range.first
            val end = match.range.last + 1
            if (!overlaps(start, end)) {
                hints += SpeechPronunciationHint(
                    start = start,
                    end = end,
                    spokenText = rule.spokenText,
                    kind = SpeechPronunciationHintKind.SubstituteText
                )
            }
        }
    }

    DOTTED_ACRONYM_REGEX.findAll(text).forEach { match ->
        val start = match.range.first
        val end = match.range.last + 1
        if (overlaps(start, end)) {
            return@forEach
        }

        val lettersOnly = match.value.filter(Char::isLetterOrDigit)
        if (lettersOnly.length >= 2) {
            hints += SpeechPronunciationHint(
                start = start,
                end = end,
                spokenText = lettersOnly.toCharArray().joinToString(" ") { it.toString() },
                kind = SpeechPronunciationHintKind.SpellOut
            )
        }
    }

    return hints.sortedBy { it.start }
}

internal fun buildSpeechPlaybackPlan(text: String): SpeechPlaybackPlan {
    val hints = pronunciationHintsForPlayback(text)
    if (hints.isEmpty()) {
        return SpeechPlaybackPlan(
            spokenText = text,
            mappings = listOf(
                SpeechOffsetMapping(
                    spokenStart = 0,
                    spokenEnd = text.length,
                    originalStart = 0,
                    originalEnd = text.length,
                    replaced = false
                )
            )
        )
    }

    val builder = StringBuilder()
    val mappings = mutableListOf<SpeechOffsetMapping>()
    var originalIndex = 0

    fun appendPlain(until: Int) {
        if (until <= originalIndex) {
            return
        }
        val slice = text.substring(originalIndex, until)
        val spokenStart = builder.length
        builder.append(slice)
        mappings += SpeechOffsetMapping(
            spokenStart = spokenStart,
            spokenEnd = builder.length,
            originalStart = originalIndex,
            originalEnd = until,
            replaced = false
        )
        originalIndex = until
    }

    hints.forEach { hint ->
        appendPlain(hint.start)
        val spokenStart = builder.length
        builder.append(hint.spokenText)
        mappings += SpeechOffsetMapping(
            spokenStart = spokenStart,
            spokenEnd = builder.length,
            originalStart = hint.start,
            originalEnd = hint.end,
            replaced = true
        )
        originalIndex = hint.end
    }

    appendPlain(text.length)

    return SpeechPlaybackPlan(
        spokenText = builder.toString(),
        mappings = mappings
    )
}

internal fun protectSentenceBoundariesInAbbreviations(text: String): String {
    if (text.isBlank()) {
        return text
    }

    var protected = text
    SENTENCE_BOUNDARY_PROTECTED_REGEXES.forEach { regex ->
        protected = regex.replace(protected) { match ->
            match.value.replace('.', SENTENCE_BOUNDARY_PLACEHOLDER)
        }
    }

    return protected
}

private data class AbbreviationPronunciationRule(
    val regex: Regex,
    val spokenText: String
)

private val COMMON_ABBREVIATION_PRONUNCIATIONS = listOf(
    AbbreviationPronunciationRule(
        regex = Regex("""(?i)(?<![\p{L}\d])mr\.(?![\p{L}\d])"""),
        spokenText = "Mister"
    ),
    AbbreviationPronunciationRule(
        regex = Regex("""(?i)(?<![\p{L}\d])mrs\.(?![\p{L}\d])"""),
        spokenText = "Misses"
    ),
    AbbreviationPronunciationRule(
        regex = Regex("""(?i)(?<![\p{L}\d])ms\.(?![\p{L}\d])"""),
        spokenText = "Miss"
    ),
    AbbreviationPronunciationRule(
        regex = Regex("""(?i)(?<![\p{L}\d])dr\.(?![\p{L}\d])"""),
        spokenText = "Doctor"
    ),
    AbbreviationPronunciationRule(
        regex = Regex("""(?i)(?<![\p{L}\d])prof\.(?![\p{L}\d])"""),
        spokenText = "Professor"
    ),
    AbbreviationPronunciationRule(
        regex = Regex("""(?i)(?<![\p{L}\d])u\.s\.(?:a\.)?(?![\p{L}\d])"""),
        spokenText = "United States"
    ),
    AbbreviationPronunciationRule(
        regex = Regex("""(?i)(?<![\p{L}\d])u\.k\.(?![\p{L}\d])"""),
        spokenText = "United Kingdom"
    ),
    AbbreviationPronunciationRule(
        regex = Regex("""(?i)(?<![\p{L}\d])e\.u\.(?![\p{L}\d])"""),
        spokenText = "European Union"
    ),
    AbbreviationPronunciationRule(
        regex = Regex("""(?i)(?<![\p{L}\d])u\.n\.(?![\p{L}\d])"""),
        spokenText = "United Nations"
    ),
    AbbreviationPronunciationRule(
        regex = Regex("""(?i)(?<![\p{L}\d])e\.g\.(?![\p{L}\d])"""),
        spokenText = "for example"
    ),
    AbbreviationPronunciationRule(
        regex = Regex("""(?i)(?<![\p{L}\d])i\.e\.(?![\p{L}\d])"""),
        spokenText = "that is"
    ),
    AbbreviationPronunciationRule(
        regex = Regex("""(?i)(?<![\p{L}\d])etc\.(?![\p{L}\d])"""),
        spokenText = "et cetera"
    ),
    AbbreviationPronunciationRule(
        regex = Regex("""(?i)(?<![\p{L}\d])ph\.d\.(?![\p{L}\d])"""),
        spokenText = "P H D"
    ),
    AbbreviationPronunciationRule(
        regex = Regex("""(?i)(?<![\p{L}\d])m\.d\.(?![\p{L}\d])"""),
        spokenText = "M D"
    )
)

private val DOTTED_ACRONYM_REGEX =
    Regex("""(?<![\p{L}\d])(?:[A-Z]\.){2,}(?![\p{L}\d])""")

private val SENTENCE_BOUNDARY_PROTECTED_REGEXES = listOf(
    DOTTED_ACRONYM_REGEX,
    Regex("""(?i)(?<![\p{L}\d])(?:e\.g|i\.e|etc|mr|mrs|ms|dr|prof|sr|jr|vs|no)\.(?![\p{L}\d])""")
)

private const val SENTENCE_BOUNDARY_PLACEHOLDER = '\u2024'
