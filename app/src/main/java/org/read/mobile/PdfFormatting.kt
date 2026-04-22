package org.read.mobile

private const val SHORT_HEADING_MAX_WORDS = 12
private const val SHORT_PARAGRAPH_MERGE_THRESHOLD = 90
private const val RUNNING_HEADER_MAX_LENGTH = 60
private val INLINE_PDF_ARTIFACT_REGEX =
    Regex("""(?<=\p{L})[\u0000-\u001F\u007F\u00AD\u200B\u200C\u200D\u2060\uFEFF]+(?=\p{L})""")
private val INLINE_GENERIC_PDF_GARBAGE_REGEX =
    Regex("""(?<=\p{L})[\uFFFD\u2022\u2023\u2043\u2219\u25A0-\u25FF]+(?=\p{L})""")
private val TRAILING_PDF_HYPHEN_ARTIFACT_REGEX =
    Regex("""(?<=\p{L})[\u0002\u00AD\u2010\u2011\u2060\uFEFF]+\s*$""")
private val CONTROL_CHAR_REGEX = Regex("""[\u0000-\u0008\u000B\u000C\u000E-\u001F\u007F]""")
private val ABSTRACT_CUE_REGEX = Regex(
    """\b(here we|we show|we find|we suggest|these findings|this study|this work)\b""",
    RegexOption.IGNORE_CASE
)
private val ABSTRACT_CONTINUATION_START_REGEX = Regex(
    """^(these findings|we suggest|together|overall|in summary|collectively|our results|our findings|this work|here we)\b""",
    RegexOption.IGNORE_CASE
)
private val BODY_START_CUE_REGEX = Regex(
    """^(artificial intelligence|large language models|recent|reasoning models|while |therefore|nevertheless|in this paper|we propose)\b""",
    RegexOption.IGNORE_CASE
)
private val HYPHENATED_PREFIXES = setOf(
    "anti", "co", "cross", "de", "inter", "intra", "macro", "meta", "micro",
    "mid", "multi", "neo", "non", "over", "post", "pre", "pro", "pseudo",
    "re", "semi", "self", "sub", "super", "trans", "ultra", "under"
)

private data class SanitizedPdfLine(
    val text: String,
    val pageIndex: Int
)

fun formatReaderContent(pageTexts: List<String>, title: String): FormattedReaderContent {
    val inferredTitle = inferDisplayTitle(pageTexts, title)
    val extractedFootnotes = mutableListOf<String>()
    val cleanedLines = sanitizeLines(pageTexts, inferredTitle, extractedFootnotes)
    val metadataBlocks = mutableListOf<ReaderBlock>()
    val blocks = mutableListOf<ReaderBlock>()
    val footnoteBlocks = mutableListOf<ReaderBlock>()
    val paragraphLines = mutableListOf<String>()
    var inFrontMatter = true

    fun flushParagraph() {
        if (paragraphLines.isEmpty()) {
            return
        }
        val paragraph = normalizeParagraph(paragraphLines)
        if (paragraph.isNotBlank()) {
            val mixedFrontMatterSplit = if (inFrontMatter) {
                splitLeadingMetadataPrefix(paragraph, inferredTitle)
            } else {
                null
            }
            val treatAsBody = inFrontMatter && looksLikeProseParagraph(paragraph)

            if (mixedFrontMatterSplit != null) {
                metadataBlocks += ReaderBlock(ReaderBlockType.Metadata, mixedFrontMatterSplit.prefix)
                inFrontMatter = false
                blocks += ReaderBlock(ReaderBlockType.Paragraph, mixedFrontMatterSplit.body)
            } else if (treatAsBody) {
                inFrontMatter = false
                blocks += ReaderBlock(ReaderBlockType.Paragraph, paragraph)
            } else {
                val target = if (inFrontMatter) metadataBlocks else blocks
                target += ReaderBlock(ReaderBlockType.Paragraph, paragraph)
            }
        }
        paragraphLines.clear()
    }

    cleanedLines.forEach { line ->
        if (isMainContentBoundary(line)) {
            flushParagraph()
            inFrontMatter = false
            blocks += ReaderBlock(ReaderBlockType.Heading, line)
            return@forEach
        }

        if (line.isBlank()) {
            flushParagraph()
            return@forEach
        }

        if (looksLikeHeading(line)) {
            flushParagraph()
            if (inFrontMatter && looksLikeMetadata(line, inferredTitle)) {
                metadataBlocks += ReaderBlock(ReaderBlockType.Metadata, line)
            } else {
                blocks += ReaderBlock(ReaderBlockType.Heading, line)
            }
        } else {
            val mixedFrontMatterLine = if (inFrontMatter) {
                splitLeadingMetadataPrefix(line, inferredTitle)
            } else {
                null
            }
            if (mixedFrontMatterLine != null) {
                flushParagraph()
                metadataBlocks += ReaderBlock(ReaderBlockType.Metadata, mixedFrontMatterLine.prefix)
                inFrontMatter = false
                blocks += ReaderBlock(ReaderBlockType.Paragraph, mixedFrontMatterLine.body)
            } else if (
                inFrontMatter &&
                looksLikeMetadata(line, inferredTitle) &&
                !looksLikeProseParagraph(line)
            ) {
                flushParagraph()
                metadataBlocks += ReaderBlock(ReaderBlockType.Metadata, line)
            } else {
                if (inFrontMatter && looksLikeProseParagraph(line)) {
                    inFrontMatter = false
                }
                paragraphLines += line
            }
        }
    }

    flushParagraph()
    extractedFootnotes
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .forEach { footnoteBlocks += ReaderBlock(ReaderBlockType.Footnote, it) }

    val (normalizedMetadataBlocks, normalizedContentBlocks) = normalizeLeadingFrontMatter(
        displayTitle = inferredTitle,
        metadataBlocks = metadataBlocks,
        contentBlocks = blocks
    )
    val (abstractAwareMetadataBlocks, abstractAwareContentBlocks) = extractLeadingAbstractSection(
        metadataBlocks = normalizedMetadataBlocks,
        contentBlocks = normalizedContentBlocks
    )
    val (contentWithoutFigureCaptions, figureCaptionFootnotes) = extractEmbeddedFigureCaptionSections(
        contentBlocks = abstractAwareContentBlocks,
        footnoteBlocks = footnoteBlocks
    )
    val (contentWithoutTabularArtifacts, tableArtifactFootnotes) = extractTabularArtifactParagraphs(
        contentBlocks = contentWithoutFigureCaptions,
        footnoteBlocks = figureCaptionFootnotes
    )

    return FormattedReaderContent(
        displayTitle = inferredTitle,
        metadataBlocks = mergeShortParagraphs(abstractAwareMetadataBlocks),
        contentBlocks = mergeShortParagraphs(contentWithoutTabularArtifacts),
        footnoteBlocks = mergeShortParagraphs(tableArtifactFootnotes)
    )
}

private fun normalizeLeadingFrontMatter(
    displayTitle: String,
    metadataBlocks: List<ReaderBlock>,
    contentBlocks: List<ReaderBlock>
): Pair<List<ReaderBlock>, List<ReaderBlock>> {
    val normalizedMetadata = metadataBlocks.toMutableList()
    val normalizedContent = contentBlocks.toMutableList()

    while (normalizedContent.isNotEmpty()) {
        val first = normalizedContent.first()
        val clean = first.text.trim()
        if (clean.isBlank()) {
            normalizedContent.removeAt(0)
            continue
        }

        if (normalizeSignature(clean) == normalizeSignature(displayTitle)) {
            normalizedContent.removeAt(0)
            continue
        }

        if (isMainContentBoundary(clean)) {
            break
        }

        if (first.type == ReaderBlockType.Paragraph) {
            val split = splitLeadingMetadataPrefix(clean, displayTitle)
            if (split != null) {
                normalizedMetadata += ReaderBlock(ReaderBlockType.Metadata, split.prefix)
                normalizedContent[0] = first.copy(text = split.body)
                break
            }
        }

        val bodyLikeParagraph = first.type == ReaderBlockType.Paragraph && looksLikeProseParagraph(clean)
        val shouldMoveToMetadata =
            (looksLikeMetadata(clean, displayTitle) && !bodyLikeParagraph) ||
                (first.type == ReaderBlockType.Paragraph && !bodyLikeParagraph) ||
                (first.type == ReaderBlockType.Heading && looksLikeLeadingFrontMatterHeading(clean))

        if (!shouldMoveToMetadata) {
            break
        }

        normalizedMetadata += ReaderBlock(ReaderBlockType.Metadata, clean)
        normalizedContent.removeAt(0)
    }

    trimLeadingMetadataPrefixFromFirstParagraph(
        displayTitle = displayTitle,
        metadataBlocks = normalizedMetadata,
        contentBlocks = normalizedContent
    )

    return normalizedMetadata to normalizedContent
}

private fun trimLeadingMetadataPrefixFromFirstParagraph(
    displayTitle: String,
    metadataBlocks: MutableList<ReaderBlock>,
    contentBlocks: MutableList<ReaderBlock>
) {
    val firstBlock = contentBlocks.firstOrNull() ?: return
    if (firstBlock.type != ReaderBlockType.Paragraph) {
        return
    }

    val split = splitLeadingMetadataPrefix(firstBlock.text, displayTitle) ?: return
    metadataBlocks += ReaderBlock(ReaderBlockType.Metadata, split.prefix)
    contentBlocks[0] = firstBlock.copy(text = split.body)
}

private fun extractLeadingAbstractSection(
    metadataBlocks: List<ReaderBlock>,
    contentBlocks: List<ReaderBlock>
): Pair<List<ReaderBlock>, List<ReaderBlock>> {
    val normalizedMetadata = metadataBlocks.toMutableList()
    val normalizedContent = contentBlocks.toMutableList()
    if (normalizedContent.isEmpty()) {
        return normalizedMetadata to normalizedContent
    }

    fun appendMetadata(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) {
            return
        }
        val signature = normalizeSignature(clean)
        if (normalizedMetadata.any { normalizeSignature(it.text) == signature }) {
            return
        }
        normalizedMetadata += ReaderBlock(ReaderBlockType.Metadata, clean)
    }

    val firstBlock = normalizedContent.first()
    val leadingAbstractBody = if (firstBlock.type == ReaderBlockType.Paragraph) {
        splitLeadingAbstractParagraph(firstBlock.text)
    } else {
        null
    }

    when {
        firstBlock.type == ReaderBlockType.Heading && isAbstractHeading(firstBlock.text) -> {
            appendMetadata(firstBlock.text)
            normalizedContent.removeAt(0)
            val explicitAbstractBlocks = leadingExplicitAbstractBlocks(normalizedContent)
            explicitAbstractBlocks.forEach { block ->
                appendMetadata(block.text)
            }
            repeat(explicitAbstractBlocks.size) {
                if (normalizedContent.isNotEmpty()) {
                    normalizedContent.removeAt(0)
                }
            }
        }

        leadingAbstractBody != null -> {
            appendMetadata("Abstract")
            appendMetadata(leadingAbstractBody)
            normalizedContent.removeAt(0)
        }

        shouldPromoteImplicitAbstract(normalizedMetadata, normalizedContent) -> {
            val implicitAbstractBlocks = leadingImplicitAbstractBlocks(normalizedContent)
            appendMetadata("Abstract")
            implicitAbstractBlocks.forEach { block ->
                appendMetadata(block.text)
            }
            repeat(implicitAbstractBlocks.size) {
                if (normalizedContent.isNotEmpty()) {
                    normalizedContent.removeAt(0)
                }
            }
        }

        shouldSplitMergedLeadingAbstract(normalizedMetadata, normalizedContent) -> {
            val firstParagraph = normalizedContent.first()
            val split = splitMergedLeadingAbstract(firstParagraph.text) ?: return normalizedMetadata to normalizedContent
            appendMetadata("Abstract")
            appendMetadata(split.first)
            normalizedContent[0] = firstParagraph.copy(text = split.second)
        }
    }

    return normalizedMetadata to normalizedContent
}

private fun shouldPromoteImplicitAbstract(
    metadataBlocks: List<ReaderBlock>,
    contentBlocks: List<ReaderBlock>
): Boolean {
    val firstParagraph = contentBlocks.firstOrNull()?.takeIf { it.type == ReaderBlockType.Paragraph } ?: return false
    val secondParagraph = contentBlocks.drop(1).firstOrNull { it.type == ReaderBlockType.Paragraph } ?: return false
    val firstHeadingIndex = contentBlocks.indexOfFirst { it.type == ReaderBlockType.Heading }
    if (firstHeadingIndex in 0..1) {
        return false
    }

    val metadataText = metadataBlocks.joinToString(" ") { it.text }.lowercase()
    val hasAuthorStyleMetadata = listOf(
        "university",
        "institute",
        "department",
        "laboratory",
        "school",
        "google",
        "openai",
        "deepmind",
        "@"
    ).any(metadataText::contains)
    if (!hasAuthorStyleMetadata) {
        return false
    }

    val firstText = firstParagraph.text.trim()
    val secondText = secondParagraph.text.trim()
    val secondLooksLikeContinuation = secondText.firstOrNull()?.isLowerCase() == true
    if (firstText.length !in 280..2200 || secondText.length < 60) {
        return false
    }

    if (!looksLikeProseParagraph(firstText)) {
        return false
    }

    val secondLooksAbstractLike = looksLikeProseParagraph(secondText) || secondLooksLikeContinuation
    if (!secondLooksAbstractLike) {
        return false
    }

    if (looksLikeMetadata(firstText) || looksLikeHeading(firstText)) {
        return false
    }

    return true
}

private fun leadingImplicitAbstractBlocks(contentBlocks: List<ReaderBlock>): List<ReaderBlock> {
    val headingIndex = contentBlocks.indexOfFirst { it.type == ReaderBlockType.Heading }
    val boundary = when {
        headingIndex > 0 -> headingIndex
        else -> contentBlocks.size
    }
    val leadingParagraphs = contentBlocks
        .take(boundary)
        .takeWhile { it.type == ReaderBlockType.Paragraph }

    if (leadingParagraphs.size <= 2) {
        return leadingParagraphs.take(2)
    }

    val selected = mutableListOf<ReaderBlock>()
    leadingParagraphs.forEachIndexed { index, block ->
        val text = block.text.trim()
        when {
            index < 2 -> selected += block
            text.isBlank() -> return@forEachIndexed
            startsLikeDefiniteBodyParagraph(text) -> return selected
            text.firstOrNull()?.isLowerCase() == true -> selected += block
            ABSTRACT_CONTINUATION_START_REGEX.containsMatchIn(text) -> selected += block
            else -> return selected
        }

        if (selected.size >= 4) {
            return selected
        }
    }

    return selected
}

private fun leadingExplicitAbstractBlocks(contentBlocks: List<ReaderBlock>): List<ReaderBlock> {
    val leadingParagraphs = contentBlocks.takeWhile { it.type == ReaderBlockType.Paragraph }
    if (leadingParagraphs.isEmpty()) {
        return emptyList()
    }

    val selected = mutableListOf<ReaderBlock>()
    leadingParagraphs.forEachIndexed { index, block ->
        val text = block.text.trim()
        when {
            text.isBlank() -> return@forEachIndexed
            index == 0 -> selected += block
            startsLikeDefiniteBodyParagraph(text) -> return selected
            text.firstOrNull()?.isLowerCase() == true -> selected += block
            ABSTRACT_CONTINUATION_START_REGEX.containsMatchIn(text) -> selected += block
            ABSTRACT_CUE_REGEX.containsMatchIn(text.take(220)) && !looksLikeMetadata(text) -> selected += block
            else -> return selected
        }

        if (selected.size >= 4) {
            return selected
        }
    }

    return selected
}

private fun shouldSplitMergedLeadingAbstract(
    metadataBlocks: List<ReaderBlock>,
    contentBlocks: List<ReaderBlock>
): Boolean {
    val firstParagraph = contentBlocks.firstOrNull()?.takeIf { it.type == ReaderBlockType.Paragraph } ?: return false
    val metadataText = metadataBlocks.joinToString(" ") { it.text }.lowercase()
    val hasAuthorStyleMetadata = listOf(
        "university",
        "institute",
        "department",
        "laboratory",
        "school",
        "google",
        "openai",
        "deepmind",
        "@"
    ).any(metadataText::contains)
    if (!hasAuthorStyleMetadata) {
        return false
    }

    return splitMergedLeadingAbstract(firstParagraph.text) != null
}

private fun splitMergedLeadingAbstract(text: String): Pair<String, String>? {
    val clean = text.trim()
    if (clean.length < 800) {
        return null
    }

    val sentences = Regex("""(?<=[.!?])\s+(?=[A-Z])""")
        .split(clean)
        .map(String::trim)
        .filter(String::isNotBlank)
    if (sentences.size < 6) {
        return null
    }

    var fallbackCandidate: Pair<String, String>? = null

    for (splitIndex in 4 until sentences.lastIndex) {
        val prefix = sentences.take(splitIndex).joinToString(" ").trim()
        val suffix = sentences.drop(splitIndex).joinToString(" ").trim()
        if (prefix.length !in 500..2200 || suffix.length < 350) {
            continue
        }
        if (!ABSTRACT_CUE_REGEX.containsMatchIn(prefix)) {
            continue
        }
        val suffixLead = suffix.take(180)
        val hasExplicitBodyCue = BODY_START_CUE_REGEX.containsMatchIn(suffixLead)
        if (!hasExplicitBodyCue && !looksLikeProseParagraph(suffix)) {
            continue
        }
        if (hasExplicitBodyCue) {
            return prefix to suffix
        }
        fallbackCandidate = prefix to suffix
    }

    return fallbackCandidate
}

private fun startsLikeDefiniteBodyParagraph(text: String): Boolean {
    val lead = text.trim().take(180)
    return BODY_START_CUE_REGEX.containsMatchIn(lead)
}

private fun isAbstractHeading(text: String): Boolean {
    val clean = normalizePdfLineArtifacts(text).trim()
    return clean.equals("abstract", ignoreCase = true) ||
        clean.equals("summary", ignoreCase = true)
}

private fun splitLeadingAbstractParagraph(text: String): String? {
    val match = Regex(
        """^(abstract|summary)\s*[:.\-–—]?\s+(.+)$""",
        RegexOption.IGNORE_CASE
    ).matchEntire(text.trim()) ?: return null

    val body = match.groupValues[2].trim()
    return body.takeIf { it.length >= 40 && looksLikeProseParagraph(it) }
}

private fun splitLeadingMetadataPrefix(text: String, displayTitle: String): MetadataPrefixSplit? {
    val trimmedText = text.trim()
    if (trimmedText.length < 80) {
        return null
    }

    val wordMatches = Regex("""\S+""").findAll(trimmedText).toList()
    if (wordMatches.size < 8) {
        return null
    }

    for (index in 2 until minOf(wordMatches.size, 36)) {
        val start = wordMatches[index].range.first
        val prefix = trimmedText.substring(0, start).trim().trim(',', ';', ':', '-', '–', '—')
        val body = trimmedText.substring(start).trimStart(' ', ',', ';', ':', '-', '–', '—')
        if (prefix.length !in 12..320 || body.length < 45) {
            continue
        }
        if (looksLikeProseParagraph(prefix)) {
            continue
        }
        if (!startsLikeBodySentence(body)) {
            continue
        }
        if (!looksLikeProseParagraph(body)) {
            continue
        }
        if (!looksLikeMetadataPrefix(prefix, displayTitle)) {
            continue
        }
        return MetadataPrefixSplit(prefix = prefix, body = body)
    }

    return null
}

private fun looksLikeMetadataPrefix(text: String, displayTitle: String): Boolean {
    val clean = text.trim()
    if (clean.isBlank()) {
        return false
    }

    if (normalizeSignature(clean) == normalizeSignature(displayTitle)) {
        return true
    }

    if (looksLikeMetadata(clean, displayTitle)) {
        return true
    }

    val lower = clean.lowercase()
    val affiliationKeywordPresent = listOf(
        "university",
        "institute",
        "department",
        "school of",
        "faculty",
        "laboratory",
        "center for",
        "centre for",
        "google",
        "deepmind",
        "microsoft",
        "openai",
        "correspond",
        "equal contribution",
        "college",
        "hospital"
    ).any(lower::contains)
    if (affiliationKeywordPresent) {
        return true
    }

    val words = clean.split(Regex("""\s+""")).filter { it.isNotBlank() }
    val capitalizedWords = words.count { word ->
        val normalized = word.trim(',', ';', ':', '.', '(', ')')
        normalized.firstOrNull()?.isUpperCase() == true
    }
    val lowercaseWords = words.count { word ->
        val normalized = word.trim(',', ';', ':', '.', '(', ')')
        normalized.firstOrNull()?.isLowerCase() == true
    }
    val conjunctionCount = Regex("""\b(and|&|y)\b""", RegexOption.IGNORE_CASE).findAll(clean).count()

    return clean.count { it == ',' } >= 1 &&
        words.size <= 30 &&
        capitalizedWords >= 3 &&
        lowercaseWords <= 12 &&
        conjunctionCount <= 4 &&
        !clean.contains('.') &&
        !clean.contains('!') &&
        !clean.contains('?')
}

private fun startsLikeBodySentence(text: String): Boolean {
    val words = Regex("""[A-Za-z][^\s,.;:!?)]*""")
        .findAll(text)
        .map { it.value.trim('"', '\'', '“', '”', '(', '[') }
        .filter { it.isNotBlank() }
        .take(4)
        .toList()
    if (words.size < 2) {
        return false
    }

    if (words.first().firstOrNull()?.isUpperCase() != true) {
        return false
    }

    if (words.getOrNull(1)?.firstOrNull()?.isLowerCase() != true) {
        return false
    }

    val lowercaseLeadCount = words.drop(1).count { word ->
        word.firstOrNull()?.isLowerCase() == true
    }
    return lowercaseLeadCount >= 2
}

private data class MetadataPrefixSplit(
    val prefix: String,
    val body: String
)

private fun looksLikeLeadingFrontMatterHeading(text: String): Boolean {
    val clean = text.trim()
    if (clean.isBlank() || isMainContentBoundary(clean)) {
        return false
    }

    if (Regex("""^[0-9IVX.]+\s+[A-Z].*""").matches(clean)) {
        return false
    }

    if (looksLikeMetadata(clean)) {
        return true
    }

    return clean.length < 120 &&
        !looksLikeProseParagraph(clean) &&
        clean.split(Regex("""\s+""")).size <= 10
}

private fun normalizeParagraph(lines: List<String>): String {
    val builder = StringBuilder()
    lines.forEach { line ->
        val clean = normalizePdfLineArtifacts(line).trim()
        if (clean.isBlank()) {
            return@forEach
        }

        if (builder.isNotEmpty()) {
            val previousEndsWithHyphen = builder.last() == '-'
            val nextWordStart = clean.firstOrNull { it.isLetterOrDigit() }
            if (previousEndsWithHyphen && nextWordStart != null) {
                val previousFragment = trailingWordFragment(builder)
                when {
                    shouldPreserveLineBreakHyphen(previousFragment, nextWordStart) -> Unit
                    shouldDropLineBreakHyphen(previousFragment, nextWordStart) -> builder.deleteCharAt(builder.lastIndex)
                    else -> builder.append(' ')
                }
            } else {
                builder.append(' ')
            }
        }

        builder.append(clean)
    }

    return builder.toString()
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun trailingWordFragment(builder: StringBuilder): String {
    var index = builder.lastIndex - 1
    while (index >= 0) {
        val character = builder[index]
        if (!character.isLetterOrDigit()) {
            break
        }
        index -= 1
    }
    return builder.substring(index + 1, builder.lastIndex)
}

private fun shouldPreserveLineBreakHyphen(previousFragment: String, nextWordStart: Char): Boolean {
    if (previousFragment.isBlank()) {
        return false
    }

    if (nextWordStart.isUpperCase() || nextWordStart.isDigit()) {
        return true
    }

    val lower = previousFragment.lowercase()
    if (lower in HYPHENATED_PREFIXES) {
        return true
    }

    return previousFragment.any(Char::isLetter) &&
        previousFragment.all { !it.isLetter() || it.isUpperCase() }
}

private fun shouldDropLineBreakHyphen(previousFragment: String, nextWordStart: Char): Boolean {
    if (previousFragment.isBlank()) {
        return false
    }

    return nextWordStart.isLowerCase() &&
        previousFragment.any(Char::isLowerCase) &&
        previousFragment.lowercase() !in HYPHENATED_PREFIXES
}

private fun mergeShortParagraphs(blocks: List<ReaderBlock>): List<ReaderBlock> {
    val merged = mutableListOf<ReaderBlock>()

    for (block in blocks) {
        val last = merged.lastOrNull()
        if (
            block.type == ReaderBlockType.Paragraph &&
            last?.type == ReaderBlockType.Paragraph &&
            shouldMergeParagraphs(last.text, block.text)
        ) {
            merged[merged.lastIndex] = last.copy(text = "${last.text} ${block.text}".trim())
        } else {
            merged += block
        }
    }

    return merged
}

private fun shouldMergeParagraphs(previous: String, current: String): Boolean {
    val prev = previous.trim()
    val next = current.trim()
    if (prev.isBlank() || next.isBlank()) {
        return false
    }

    if (looksLikeHeading(next) || looksLikeMetadata(next)) {
        return false
    }

    val previousEndsMidSentence = prev.lastOrNull() !in listOf('.', '!', '?', ':')
    val nextStartsLowercase = next.firstOrNull()?.isLowerCase() == true
    val nextLooksWrappedContinuation = next.startsWith("(") || next.startsWith("[")
    val nextLooksContinuation = nextStartsLowercase || nextLooksWrappedContinuation
    val previousLooksVeryShort = prev.length < SHORT_PARAGRAPH_MERGE_THRESHOLD

    return previousEndsMidSentence ||
        nextStartsLowercase ||
        (previousLooksVeryShort && nextLooksContinuation)
}

private fun sanitizeLines(
    pageTexts: List<String>,
    title: String,
    extractedFootnotes: MutableList<String>
): List<String> {
    val repeatedLineThreshold = if (pageTexts.size <= 2) 2 else 3
    val allLines = pageTexts.flatMapIndexed { pageIndex, pageText ->
        val pageLines = pageText.lines()
            .map(::normalizePdfLineArtifacts)
            .map { it.trim() }
            .toMutableList()
        val removedFootnotes = extractTrailingFootnotes(pageLines)
        extractedFootnotes += removedFootnotes
        pageLines
            .map { SanitizedPdfLine(text = it, pageIndex = pageIndex) }
    }

    val firstPageProtectedSignatures = protectedFirstPageFrontMatterSignatures(allLines)

    val repeatedShortLines = allLines
        .map { it.text }
        .filter { looksLikeRunningHeaderFooterCandidate(it) }
        .groupingBy(::normalizeSignature)
        .eachCount()
        .filterValues { it >= repeatedLineThreshold }
        .keys

    return allLines
        .filterNot { shouldDropLine(it, title, repeatedShortLines, firstPageProtectedSignatures) }
        .map { it.text }
}

private fun protectedFirstPageFrontMatterSignatures(lines: List<SanitizedPdfLine>): Set<String> {
    return lines
        .filter { it.pageIndex == 0 }
        .map { it.text.trim() }
        .takeWhile { line ->
            line.isNotBlank() &&
                !isMainContentBoundary(line) &&
                splitLeadingAbstractParagraph(line) == null &&
                !looksLikeProseParagraph(line)
        }
        .map(::normalizeSignature)
        .toSet()
}

private fun shouldDropLine(
    line: SanitizedPdfLine,
    title: String,
    repeatedShortLines: Set<String>,
    firstPageProtectedSignatures: Set<String>
): Boolean {
    val clean = normalizePdfLineArtifacts(line.text).trim()
    if (clean.isBlank()) {
        return false
    }

    val signature = normalizeSignature(clean)
    if (line.pageIndex == 0 && (signature == normalizeSignature(title) || signature in firstPageProtectedSignatures)) {
        return false
    }

    if (looksLikeArxivHeaderFooterLine(clean)) {
        return true
    }

    if (looksLikeNumberedRunningHeaderFooterLine(clean, line.pageIndex)) {
        return true
    }

    if (clean.matches(Regex("""^\d+$"""))) {
        return true
    }

    if (signature in repeatedShortLines && looksLikeRunningHeaderFooterCandidate(clean)) {
        return true
    }

    if (Regex("""^page\s+\d+(\s+of\s+\d+)?$""", RegexOption.IGNORE_CASE).matches(clean)) {
        return true
    }

    return false
}

private fun looksLikeArxivHeaderFooterLine(text: String): Boolean {
    val clean = text.trim()
    val lower = clean.lowercase()
    if (!lower.contains("arxiv:")) {
        return false
    }

    return Regex(
        """arxiv:\d{4}\.\d{4,5}(v\d+)?\s+\[[^\]]+\]\s+\d{1,2}\s+[a-z]{3}\s+\d{4}""",
        RegexOption.IGNORE_CASE
    ).containsMatchIn(clean)
}

private fun looksLikeNumberedRunningHeaderFooterLine(text: String, pageIndex: Int): Boolean {
    if (pageIndex == 0) {
        return false
    }

    val match = Regex("""^\d+\s+(.+)$""").matchEntire(text.trim()) ?: return false
    val remainder = match.groupValues[1].trim()
    if (remainder.isBlank()) {
        return false
    }

    return looksLikeAuthorNameLine(remainder) ||
        (remainder == remainder.uppercase() && !remainder.endsWith(".") && remainder.count(Char::isLetter) >= 8)
}

private fun looksLikeRunningHeaderFooterCandidate(text: String): Boolean {
    val clean = text.trim()
    if (clean.length !in 2..RUNNING_HEADER_MAX_LENGTH) {
        return false
    }

    if (clean.matches(Regex("""^\d+$"""))) {
        return false
    }

    if (clean.endsWith(".") || clean.endsWith("!") || clean.endsWith("?")) {
        return false
    }

    if (clean.contains("@")) {
        return false
    }

    val words = clean.split(Regex("""\s+"""))
    if (words.size > 10) {
        return false
    }

    val punctuationCount = clean.count { !it.isLetterOrDigit() && !it.isWhitespace() }
    if (punctuationCount > 4) {
        return false
    }

    val letterCount = clean.count(Char::isLetter)
    return letterCount >= 2
}

private fun normalizeSignature(text: String): String {
    return text
        .lowercase()
        .replace(Regex("""\d+"""), "#")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun inferDisplayTitle(pageTexts: List<String>, fallbackTitle: String): String {
    val fallbackLooksGeneric = fallbackTitle.lowercase().endsWith(".pdf") ||
        fallbackTitle.equals("Local PDF", ignoreCase = true) ||
        fallbackTitle.equals("Remote PDF", ignoreCase = true) ||
        !fallbackTitle.contains(' ')

    val firstPageLines = pageTexts
        .firstOrNull()
        ?.lines()
        ?.map(::normalizePdfLineArtifacts)
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()

    if (firstPageLines.isEmpty()) {
        return fallbackTitle
    }

    inferLeadingFrontMatterTitle(firstPageLines)?.let { inferred ->
        return when {
            fallbackLooksGeneric -> inferred
            inferred.length > fallbackTitle.length + 8 -> inferred
            else -> fallbackTitle
        }
    }

    val candidates = firstPageLines
        .take(8)
        .filterNot { looksLikeMetadata(it, fallbackTitle) || isMainContentBoundary(it) }
        .filter { it.length in 12..180 }

    val inferred = buildList {
        for (line in candidates) {
            if (isLikelyTitleLine(line)) {
                add(line)
            } else if (isNotEmpty()) {
                break
            }
        }
    }
        .joinToString(" ")
        .replace(Regex("""\s+"""), " ")
        .trim()
        .takeIf { it.isNotBlank() }

    return when {
        inferred.isNullOrBlank() -> fallbackTitle
        fallbackLooksGeneric -> inferred
        inferred.length > fallbackTitle.length + 8 -> inferred
        else -> fallbackTitle
    }
}

private fun inferLeadingFrontMatterTitle(firstPageLines: List<String>): String? {
    val leadingTitleLines = mutableListOf<String>()

    for (line in firstPageLines.take(8)) {
        val clean = line.trim()
        if (clean.isBlank()) {
            if (leadingTitleLines.isNotEmpty()) {
                break
            }
            continue
        }

        if (isMainContentBoundary(clean) || splitLeadingAbstractParagraph(clean) != null) {
            break
        }

        if (looksLikeAuthorNameLine(clean) || looksLikeMetadata(clean)) {
            if (leadingTitleLines.isNotEmpty()) {
                break
            }
            continue
        }

        if (!looksLikeFrontMatterTitleLine(clean)) {
            if (leadingTitleLines.isNotEmpty()) {
                break
            }
            continue
        }

        leadingTitleLines += clean
        if (leadingTitleLines.size >= 3) {
            break
        }
    }

    return leadingTitleLines
        .takeIf { it.isNotEmpty() }
        ?.joinToString(" ")
        ?.replace(Regex("""\s+"""), " ")
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

private fun looksLikeFrontMatterTitleLine(text: String): Boolean {
    val clean = text.trim()
    if (clean.length !in 12..180) {
        return false
    }

    if (clean.contains("@") || clean.any { it.isDigit() }) {
        return false
    }

    val lower = clean.lowercase()
    if (
        listOf(
            "abstract",
            "summary",
            "introduction",
            "keywords",
            "department",
            "university",
            "institute",
            "school",
            "college",
            "center",
            "centre",
            "laboratory",
            "conference",
            "proceedings",
            "journal",
            "arxiv",
            "doi"
        ).any(lower::contains)
    ) {
        return false
    }

    if (looksLikeProseParagraph(clean)) {
        return false
    }

    return clean.count(Char::isLetter) >= 10
}

private fun isLikelyTitleLine(text: String): Boolean {
    val clean = text.trim()
    if (clean.length !in 12..180) {
        return false
    }

    if (clean.contains("@") || clean.matches(Regex("""^\d+(\s*,\s*\d+)*$"""))) {
        return false
    }

    val lower = clean.lowercase()
    if (
        listOf(
            "abstract",
            "introduction",
            "keywords",
            "department",
            "university",
            "institute",
            "conference",
            "proceedings",
            "arxiv",
            "doi"
        ).any { lower.contains(it) }
    ) {
        return false
    }

    return clean.count(Char::isLetter) >= 10
}

private fun extractTrailingFootnotes(pageLines: MutableList<String>): List<String> {
    val nonBlankIndices = pageLines.mapIndexedNotNull { index, line ->
        index.takeIf { line.isNotBlank() }
    }
    if (nonBlankIndices.isEmpty()) {
        return emptyList()
    }

    val lastIndex = nonBlankIndices.last()
    val previousIndex = nonBlankIndices.dropLast(1).lastOrNull()
    if (
        previousIndex != null &&
        looksLikeStandaloneTrailingFootnote(pageLines[lastIndex]) &&
        looksLikeProseParagraph(pageLines[previousIndex])
    ) {
        val footnote = normalizeParagraph(listOf(pageLines[lastIndex]))
        pageLines.removeAt(lastIndex)
        return listOf(footnote)
    }

    val tailIndices = nonBlankIndices.takeLast(5)
    val startIndex = tailIndices.indexOfFirst { looksLikeFootnoteStart(pageLines[it]) }
    if (startIndex == -1) {
        return emptyList()
    }

    val footnoteIndices = tailIndices.drop(startIndex)
    val footnoteLines = footnoteIndices.map { pageLines[it] }
    if (!shouldExtractTrailingFootnotes(footnoteLines)) {
        return emptyList()
    }

    footnoteIndices.sortedDescending().forEach { pageLines.removeAt(it) }
    return listOf(normalizeParagraph(footnoteLines))
}

private fun shouldExtractTrailingFootnotes(lines: List<String>): Boolean {
    val cleanLines = lines.map { it.trim() }.filter { it.isNotBlank() }
    if (cleanLines.isEmpty()) {
        return false
    }

    val explicitMarkerCount = cleanLines.count { looksLikeFootnoteStart(it) }
    if (explicitMarkerCount == 0) {
        return false
    }

    val bodyLikeCount = cleanLines.count { line ->
        looksLikeProseParagraph(line) || line.length > 180
    }
    if (bodyLikeCount > 0 && explicitMarkerCount < 2) {
        return false
    }

    val combinedLength = normalizeParagraph(cleanLines).length
    if (combinedLength < 20 || (cleanLines.size == 1 && combinedLength < 35)) {
        return false
    }

    return true
}

private fun looksLikeFootnoteStart(text: String): Boolean {
    val clean = text.trim()
    if (clean.isBlank()) {
        return false
    }

    val lower = clean.lowercase()
    if (lower.startsWith("footnote")) {
        return true
    }

    if (clean.contains("@")) {
        return true
    }

    if (Regex("""^(\*|\u2020|\u2021|\u00A7)(\s+.+)$""").matches(clean)) {
        return true
    }

    if (Regex("""^\d{1,2}(?=[A-Z]).+""").matches(clean)) {
        return true
    }

    if (looksLikeHeading(clean) || isMainContentBoundary(clean)) {
        return false
    }

    return clean.length <= 160 &&
        Regex("""^(\d{1,2}[.)]?)(\s+.+)$""").matches(clean)
}

private fun looksLikeStandaloneTrailingFootnote(text: String): Boolean {
    val clean = text.trim()
    if (!looksLikeFootnoteStart(clean)) {
        return false
    }

    val lower = clean.lowercase()
    return listOf(
        "note",
        "correspond",
        "@",
        "equal contribution",
        "supplementary",
        "acknowledg",
        "fund",
        "available at"
    ).any(lower::contains)
}

private fun isMainContentBoundary(text: String): Boolean {
    val clean = text.trim()
    return clean.equals("abstract", ignoreCase = true) ||
        clean.equals("summary", ignoreCase = true) ||
        clean.equals("introduction", ignoreCase = true) ||
        Regex("""^([1I]\.?|I\.)\s+introduction$""", RegexOption.IGNORE_CASE).matches(clean)
}

private fun looksLikeMetadata(text: String, title: String? = null): Boolean {
    val clean = text.trim()
    if (clean.isBlank()) {
        return false
    }

    if (title != null && normalizeSignature(clean) == normalizeSignature(title)) {
        return false
    }

    val lower = clean.lowercase()

    if (lower.contains("@")) {
        return true
    }

    if (
        listOf(
            "author",
            "university",
            "institute",
            "department",
            "school of",
            "faculty of",
            "laboratory",
            "center for",
            "centre for",
            "research group",
            "google",
            "deepmind",
            "microsoft",
            "openai",
            "correspond",
            "equal contribution",
            "supplementary",
            "keywords",
            "doi",
            "arxiv",
            "preprint",
            "submitted",
            "under review",
            "licensed under",
            "copyright",
            "accepted",
            "conference",
            "journal",
            "proceedings"
        ).any(lower::contains)
    ) {
        return true
    }

    if (Regex("""^(received|published|revised|accepted)\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
        return true
    }

    if (Regex("""^\d+(\s*,\s*\d+)*$""").matches(clean)) {
        return true
    }

    if (Regex("""^[A-Za-z ,.&'-]+(?:university|institute|department|laboratory|school|college|center|centre)\b""", RegexOption.IGNORE_CASE).containsMatchIn(clean)) {
        return true
    }

    if (looksLikeAuthorNameLine(clean)) {
        return true
    }

    if (
        clean != clean.uppercase() &&
        Regex("""^[A-Z][A-Za-z.'-]+(?:\s+[A-Z][A-Za-z.'-]+){1,5}(?:\s*,\s*[A-Z][A-Za-z.'-]+(?:\s+[A-Z][A-Za-z.'-]+){1,5})*$""").matches(clean)
    ) {
        return true
    }

    return false
}

private fun looksLikeAuthorNameLine(text: String): Boolean {
    val clean = text.trim().replace(Regex("""\s+"""), " ")
    val normalized = normalizeAuthorMarkerArtifacts(clean)
    if (normalized.length !in 8..160) {
        return false
    }
    if (normalized.contains('@') || normalized.endsWith(".") || normalized.any { it.isDigit() }) {
        return false
    }

    val lower = normalized.lowercase()
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
            "arxiv"
        ).any(lower::contains)
    ) {
        return false
    }

    val words = normalized.split(Regex("""\s+""")).filter { it.isNotBlank() }
    if (words.size !in 2..12) {
        return false
    }

    val segments = normalized
        .split(Regex("""\s*,\s*|\s+(?:and|&)\s+""", RegexOption.IGNORE_CASE))
        .map { it.trim() }
        .filter { it.isNotBlank() }

    val isNameWord: (String) -> Boolean = { word ->
        val normalized = word.trim(',', ';', ':', '.', '(', ')')
        val letters = normalized.filter(Char::isLetter)
        letters.length in 2..20 &&
            (
                letters == letters.uppercase() ||
                    (letters.firstOrNull()?.isUpperCase() == true && letters.drop(1).all { it.isLowerCase() })
                )
    }
    if (
        segments.size >= 2 &&
        segments.all { segment ->
            val segmentWords = segment.split(Regex("""\s+""")).filter { it.isNotBlank() }
            segmentWords.size in 1..4 && segmentWords.all(isNameWord)
        } &&
        segments.count { segment ->
            segment.split(Regex("""\s+""")).count { it.isNotBlank() } >= 2
        } >= 2
    ) {
        return true
    }

    return false
}

private fun normalizeAuthorMarkerArtifacts(text: String): String {
    return text
        .replace(Regex("""(?<=\p{L})[\d*†‡§∗]+"""), "")
        .replace(Regex("""(?<=\p{L})\((?:\d+|[*†‡§∗]+)\)"""), "")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private fun extractEmbeddedFigureCaptionSections(
    contentBlocks: List<ReaderBlock>,
    footnoteBlocks: List<ReaderBlock>
): Pair<List<ReaderBlock>, List<ReaderBlock>> {
    val remainingContent = mutableListOf<ReaderBlock>()
    val extractedFootnotes = footnoteBlocks.toMutableList()
    var index = 0

    while (index < contentBlocks.size) {
        val block = contentBlocks[index]
        val clean = block.text.trim()
        if (clean.isBlank()) {
            index += 1
            continue
        }

        if (!looksLikeEmbeddedFigureSectionStart(clean)) {
            remainingContent += block
            index += 1
            continue
        }

        val sectionBlocks = mutableListOf<String>()
        var cursor = index
        while (cursor < contentBlocks.size) {
            val candidate = contentBlocks[cursor].text.trim()
            if (candidate.isBlank()) {
                cursor += 1
                continue
            }
            if (cursor != index && !looksLikeEmbeddedFigureSectionContinuation(candidate)) {
                break
            }
            sectionBlocks += candidate
            cursor += 1
        }

        val combined = normalizeParagraph(sectionBlocks)
        if (combined.isNotBlank()) {
            extractedFootnotes += ReaderBlock(ReaderBlockType.Footnote, combined)
        }
        index = cursor
    }

    return remainingContent to extractedFootnotes
}

private fun extractTabularArtifactParagraphs(
    contentBlocks: List<ReaderBlock>,
    footnoteBlocks: List<ReaderBlock>
): Pair<List<ReaderBlock>, List<ReaderBlock>> {
    val remainingContent = mutableListOf<ReaderBlock>()
    val extractedFootnotes = footnoteBlocks.toMutableList()

    contentBlocks.forEach { block ->
        val clean = block.text.trim()
        if (block.type == ReaderBlockType.Paragraph && looksLikeTabularArtifactParagraph(clean)) {
            extractedFootnotes += ReaderBlock(ReaderBlockType.Footnote, clean)
        } else {
            remainingContent += block
        }
    }

    return remainingContent to extractedFootnotes
}

private fun looksLikeEmbeddedFigureSectionStart(text: String): Boolean {
    val clean = text.trim()
    val lower = clean.lowercase()
    return Regex("""^\([a-z]\)\s+""", RegexOption.IGNORE_CASE).containsMatchIn(clean) ||
        Regex("""^(figure|fig\.|table)\s+\d+[:.]""", RegexOption.IGNORE_CASE).containsMatchIn(clean) ||
        looksLikeEmbeddedTranscriptBlob(clean) ||
        lower.startsWith("user:") ||
        lower.startsWith("assistant:") ||
        lower.startsWith("tool call:") ||
        lower.startsWith("tool output:") ||
        lower.contains("figure 1:")
}

private fun looksLikeEmbeddedFigureSectionContinuation(text: String): Boolean {
    val clean = text.trim()
    val lower = clean.lowercase()
    if (looksLikeEmbeddedFigureSectionStart(clean)) {
        return true
    }

    return looksLikeEmbeddedTranscriptBlob(clean) ||
        lower.startsWith("user:") ||
        lower.startsWith("assistant:") ||
        lower.startsWith("tool call:") ||
        lower.startsWith("tool output:") ||
        lower.startsWith("(a)") ||
        lower.startsWith("(b)") ||
        lower.contains("follow-up q&a") ||
        lower.contains("model exfiltration") ||
        lower.contains("figure 1:")
}

private fun looksLikeEmbeddedTranscriptBlob(text: String): Boolean {
    val clean = text.trim()
    val lower = clean.lowercase()
    val markerCount = listOf(
        "action required:",
        "filename:",
        "last_modified:",
        "source_ip=",
        "dest_ip=",
        "list_server_files(",
        "copy_server_file(",
        "transfer_server_files(",
        "run_shell_command(",
        "add_server_delete_queue(",
        ".safetensors",
        "employee_directory.csv",
        "server 10.0."
    ).count(lower::contains)
    val hasIpAddress = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""").containsMatchIn(clean)
    val hasCommandStyleAssignment = Regex("""\b[a-z_]+\s*=\s*["(]""", RegexOption.IGNORE_CASE)
        .containsMatchIn(clean)

    return markerCount >= 2 ||
        (markerCount >= 1 && hasIpAddress) ||
        (clean.startsWith("[...]") && (markerCount >= 1 || hasCommandStyleAssignment))
}

private fun looksLikeTabularArtifactParagraph(text: String): Boolean {
    val clean = text.trim()
    if (clean.length < 24 || looksLikeProseParagraph(clean)) {
        return false
    }

    val tokens = clean.split(Regex("""\s+""")).filter { it.isNotBlank() }
    if (tokens.size < 10) {
        return false
    }

    val numericTokenRegex = Regex("""^[-–—]?\d+(?:\.\d+)?%?$""")
    val numericLikeTokens = tokens.count { token ->
        numericTokenRegex.matches(token.trim(',', ';', ':', '(', ')')) ||
            token in setOf("-", "–", "—")
    }
    val digitCount = clean.count(Char::isDigit)
    val letterCount = clean.count(Char::isLetter)

    return (numericLikeTokens >= 8 && digitCount >= letterCount) ||
        (numericLikeTokens >= 6 && clean.contains("0.0") && digitCount >= 12)
}

private fun looksLikeProseParagraph(text: String): Boolean {
    val clean = text.trim()
    if (clean.length < 45) {
        return false
    }

    val lowercaseWords = clean.split(Regex("""\s+""")).count { word ->
        word.firstOrNull()?.isLowerCase() == true
    }
    return lowercaseWords >= 6 || (lowercaseWords >= 4 && clean.any { it in ".?!" })
}

private fun looksLikeBodyParagraph(text: String): Boolean {
    val clean = text.trim()
    if (!looksLikeProseParagraph(clean)) {
        return false
    }

    return !looksLikeMetadata(clean)
}

private fun looksLikeHeading(text: String): Boolean {
    val clean = normalizePdfLineArtifacts(text).trim()
    if (clean.isBlank() || clean.length > 90) {
        return false
    }

    val words = clean.split(Regex("""\s+"""))
    if (words.size > SHORT_HEADING_MAX_WORDS) {
        return false
    }

    if (clean.lastOrNull() in listOf('.', '?', '!', ':', ';')) {
        return false
    }

    if (clean == clean.uppercase() && clean.count(Char::isLetter) > 1) {
        return true
    }

    if (Regex("""^[0-9IVX.]+\s+[A-Z].*""").matches(clean)) {
        return true
    }

    val capitalizedWords = words.count { word ->
        word.firstOrNull()?.isUpperCase() == true
    }
    return capitalizedWords >= (words.size * 0.7)
}

private fun normalizePdfLineArtifacts(text: String): String {
    return text
        .replace('\u00A0', ' ')
        .replace(TRAILING_PDF_HYPHEN_ARTIFACT_REGEX, "-")
        .replace(INLINE_PDF_ARTIFACT_REGEX, "")
        .replace(INLINE_GENERIC_PDF_GARBAGE_REGEX, "")
        .replace(CONTROL_CHAR_REGEX, "")
        .replace(Regex("""[\u200B\u200C\u200D\u2060\uFEFF]"""), "")
}

data class FormattedReaderContent(
    val displayTitle: String,
    val metadataBlocks: List<ReaderBlock>,
    val contentBlocks: List<ReaderBlock>,
    val footnoteBlocks: List<ReaderBlock>
)
