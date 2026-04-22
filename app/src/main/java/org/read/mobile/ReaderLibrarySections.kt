package org.read.mobile

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun ReaderHistoryTypeSelector(
    selectedKind: DocumentKind,
    onSelectKind: (DocumentKind) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (selectedKind == DocumentKind.PDF) {
            Button(
                onClick = { onSelectKind(DocumentKind.PDF) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.history_pdfs))
            }
        } else {
            OutlinedButton(
                onClick = { onSelectKind(DocumentKind.PDF) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.history_pdfs))
            }
        }

        if (selectedKind == DocumentKind.WEB) {
            Button(
                onClick = { onSelectKind(DocumentKind.WEB) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.history_web_pages))
            }
        } else {
            OutlinedButton(
                onClick = { onSelectKind(DocumentKind.WEB) },
                modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(R.string.history_web_pages))
            }
        }
    }
}

@Composable
fun ReaderHistorySection(
    title: String,
    items: List<HistoryEntry>,
    onOpen: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    bookmarksBySource: Map<String, List<BookmarkEntry>>,
    readingListBySource: Map<String, ReadingListEntry>,
    onOpenBookmark: (BookmarkEntry) -> Unit,
    onDeleteBookmark: (BookmarkEntry) -> Unit,
    onToggleReadingList: (HistoryEntry) -> Unit
) {
    if (items.isEmpty()) {
        return
    }

    Text(
        title,
        style = MaterialTheme.typography.titleMedium
    )

    items.forEachIndexed { index, item ->
        val bookmarks = bookmarksBySource[item.sourceLabel].orEmpty()
        val inReadingList = readingListBySource[item.sourceLabel] != null
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen(item) }
                .padding(vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 4.dp, top = 8.dp, bottom = 8.dp)
            ) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { onOpen(item) },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                TextButton(
                    onClick = { onToggleReadingList(item) },
                    modifier = Modifier.padding(start = 0.dp)
                ) {
                    Text(
                        stringResource(
                            if (inReadingList) R.string.action_remove else R.string.reading_list_title
                        )
                    )
                }
                if (bookmarks.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.label_bookmark_count,
                            bookmarks.size,
                            if (bookmarks.size == 1) "" else "s"
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    bookmarks.take(3).forEach { bookmark ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenBookmark(bookmark) }
                                .padding(top = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Bookmark,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                buildString {
                                    append(
                                        bookmark.sectionHeading ?: stringResource(
                                            R.string.label_position_fallback,
                                            bookmark.blockIndex + 1
                                        )
                                    )
                                    append(" ")
                                    append(bookmark.label ?: bookmark.snippet)
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = { onDeleteBookmark(bookmark) },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = stringResource(R.string.action_delete_bookmark),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                    if (bookmarks.size > 3) {
                        Text(
                            stringResource(R.string.label_bookmarks_more, bookmarks.size - 3),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Box(modifier = Modifier.padding(top = 4.dp)) {
                IconButton(onClick = { onDelete(item) }) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.action_delete_history_item)
                    )
                }
            }
        }
        if (index < items.lastIndex) {
            HorizontalDivider()
        }
    }
}

@Composable
fun ReaderReadingListSection(
    items: List<ReadingListEntry>,
    currentDocumentSourceLabel: String?,
    onOpen: (ReadingListEntry) -> Unit,
    onToggleDone: (ReadingListEntry) -> Unit,
    onRemove: (ReadingListEntry) -> Unit
) {
    val toRead = items.filterNot { it.isDone }
    val done = items.filter { it.isDone }

    Text(
        stringResource(R.string.reading_list_title),
        style = MaterialTheme.typography.titleMedium
    )
    Text(
        stringResource(R.string.reading_list_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    if (toRead.isNotEmpty()) {
        ReaderReadingListGroup(
            title = stringResource(R.string.reading_list_group_to_read),
            items = toRead,
            currentDocumentSourceLabel = currentDocumentSourceLabel,
            onOpen = onOpen,
            onToggleDone = onToggleDone,
            onRemove = onRemove
        )
    }

    if (done.isNotEmpty()) {
        ReaderReadingListGroup(
            title = stringResource(R.string.reading_list_group_done),
            items = done,
            currentDocumentSourceLabel = currentDocumentSourceLabel,
            onOpen = onOpen,
            onToggleDone = onToggleDone,
            onRemove = onRemove
        )
    }
}

@Composable
fun ReaderReadingListGroup(
    title: String,
    items: List<ReadingListEntry>,
    currentDocumentSourceLabel: String?,
    onOpen: (ReadingListEntry) -> Unit,
    onToggleDone: (ReadingListEntry) -> Unit,
    onRemove: (ReadingListEntry) -> Unit
) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary
    )

    items.forEachIndexed { index, item ->
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            item.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { onOpen(item) },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (item.sourceLabel == currentDocumentSourceLabel) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    stringResource(R.string.label_current_paper),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Text(
                            item.summary,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TextButton(onClick = { onOpen(item) }) {
                        Text(stringResource(R.string.action_open))
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { onToggleDone(item) }) {
                        Text(stringResource(if (item.isDone) R.string.action_undo else R.string.action_done))
                    }
                    TextButton(onClick = { onRemove(item) }) {
                        Text(stringResource(R.string.action_remove))
                    }
                }
            }
        }
        if (index < items.lastIndex) {
            HorizontalDivider()
        }
    }
}

@Composable
fun ReaderBookmarksSection(
    bookmarks: List<BookmarkEntry>,
    currentDocumentSourceLabel: String?,
    onOpenBookmark: (BookmarkEntry) -> Unit,
    onDeleteBookmark: (BookmarkEntry) -> Unit,
    onRenameBookmark: (BookmarkEntry, String) -> Unit
) {
    if (bookmarks.isEmpty()) {
        return
    }

    var bookmarkSearchQuery by remember { mutableStateOf("") }
    val filteredBookmarks = remember(bookmarks, bookmarkSearchQuery) {
        val query = bookmarkSearchQuery.trim()
        if (query.isBlank()) {
            bookmarks
        } else {
            val normalizedQuery = query.lowercase()
            bookmarks.filter { bookmark ->
                bookmark.documentTitle.lowercase().contains(normalizedQuery) ||
                    (bookmark.label?.lowercase()?.contains(normalizedQuery) == true) ||
                    bookmark.snippet.lowercase().contains(normalizedQuery)
            }
        }
    }
    val groupedBookmarks = remember(filteredBookmarks, currentDocumentSourceLabel) {
        filteredBookmarks
            .sortedByDescending { it.createdAt }
            .groupBy { it.sourceLabel }
            .values
            .map { group ->
                group.sortedWith(
                    compareBy<BookmarkEntry> { it.blockIndex }
                        .thenBy { it.charOffset }
                        .thenByDescending { it.createdAt }
                )
            }
            .sortedWith(
                compareByDescending<List<BookmarkEntry>> { it.firstOrNull()?.sourceLabel == currentDocumentSourceLabel }
                    .thenByDescending { group -> group.maxOfOrNull { it.createdAt } ?: 0L }
            )
    }
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }
    var editingBookmark by remember { mutableStateOf<BookmarkEntry?>(null) }
    var bookmarkLabelDraft by remember { mutableStateOf("") }

    Text(
        stringResource(R.string.bookmarks_title),
        style = MaterialTheme.typography.titleMedium
    )
    Text(
        stringResource(R.string.bookmarks_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    OutlinedTextField(
        value = bookmarkSearchQuery,
        onValueChange = { bookmarkSearchQuery = it.take(80) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text(stringResource(R.string.bookmark_filter_label)) },
        placeholder = { Text(stringResource(R.string.bookmark_filter_placeholder)) }
    )

    if (filteredBookmarks.isEmpty()) {
        Text(
            stringResource(R.string.bookmarks_no_matches),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        return
    }

    groupedBookmarks.forEach { documentBookmarks ->
        val documentTitle = documentBookmarks.first().documentTitle
        val sourceLabel = documentBookmarks.first().sourceLabel
        val latestBookmark = documentBookmarks.maxByOrNull { it.createdAt } ?: documentBookmarks.first()
        val isCurrentDocument = sourceLabel == currentDocumentSourceLabel
        val isExpanded = expandedGroups[sourceLabel] ?: isCurrentDocument
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            documentTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier.clickable { onOpenBookmark(latestBookmark) },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (isCurrentDocument) {
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                shape = MaterialTheme.shapes.small
                            ) {
                                Text(
                                    stringResource(R.string.label_current_paper),
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        Text(
                            stringResource(
                                R.string.label_bookmark_count,
                                documentBookmarks.size,
                                if (documentBookmarks.size == 1) "" else "s"
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    TextButton(onClick = { onOpenBookmark(latestBookmark) }) {
                        Text(stringResource(R.string.action_open))
                    }
                    IconButton(onClick = { expandedGroups[sourceLabel] = !isExpanded }) {
                        Icon(
                            if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = stringResource(
                                if (isExpanded) R.string.action_collapse_bookmarks else R.string.action_expand_bookmarks
                            )
                        )
                    }
                }

                if (isExpanded) {
                    documentBookmarks.forEachIndexed { index, bookmark ->
                        if (index > 0) {
                            HorizontalDivider()
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenBookmark(bookmark) }
                                .padding(vertical = 0.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Rounded.Bookmark,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(1.dp)
                            ) {
                                Text(
                                    bookmark.label ?: bookmark.snippet,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    buildString {
                                        append(
                                            bookmark.sectionHeading ?: stringResource(
                                                R.string.label_position_fallback,
                                                bookmark.blockIndex + 1
                                            )
                                        )
                                        if (!bookmark.label.isNullOrBlank()) {
                                            append(" - ")
                                            append(bookmark.snippet)
                                        }
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(0.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = {
                                        editingBookmark = bookmark
                                        bookmarkLabelDraft = bookmark.label ?: ""
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Edit,
                                        contentDescription = stringResource(R.string.action_rename_bookmark),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteBookmark(bookmark) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Delete,
                                        contentDescription = stringResource(R.string.action_delete_bookmark),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editingBookmark?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { editingBookmark = null },
            title = { Text(stringResource(R.string.dialog_rename_bookmark)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        bookmark.snippet,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    OutlinedTextField(
                        value = bookmarkLabelDraft,
                        onValueChange = { bookmarkLabelDraft = it.take(80) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.dialog_bookmark_label)) },
                        placeholder = { Text(stringResource(R.string.dialog_bookmark_label_placeholder)) }
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRenameBookmark(bookmark, bookmarkLabelDraft)
                        editingBookmark = null
                    }
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingBookmark = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
