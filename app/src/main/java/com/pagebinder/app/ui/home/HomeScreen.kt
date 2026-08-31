package com.pagebinder.app.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pagebinder.app.R
import com.pagebinder.app.domain.BookProjectSort
import com.pagebinder.app.ui.pagelist.PageThumbnail
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader
import com.pagebinder.app.ui.theme.ButtonCornerRadius
import com.pagebinder.app.ui.theme.CardCornerRadius
import com.pagebinder.app.ui.theme.ColorDivider
import com.pagebinder.app.ui.theme.ColorPrimary
import com.pagebinder.app.ui.theme.ColorText
import com.pagebinder.app.ui.theme.ColorTextSecondary
import com.pagebinder.app.ui.theme.MinTouchTarget
import com.pagebinder.app.ui.theme.ScreenHorizontalMargin
import com.pagebinder.app.ui.theme.SpaceUnit
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onQueryChange: (String) -> Unit,
    onSortChange: (BookProjectSort) -> Unit,
    onBookClick: (HomeBookUiState) -> Unit,
    onNewBook: () -> Unit,
    onTrash: () -> Unit,
    onReload: () -> Unit,
    thumbnailLoader: PageThumbnailLoader,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = ScreenHorizontalMargin, end = SpaceUnit),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onTrash, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.home_trash))
                    }
                }
                OutlinedTextField(
                    value = uiState.query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = ScreenHorizontalMargin),
                    placeholder = { Text(stringResource(R.string.home_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(ButtonCornerRadius),
                )
                SortMenu(uiState.sort, onSortChange)
                when {
                    uiState.loadFailed -> HomeNotice(R.string.home_load_failed, onReload)
                    uiState.empty -> HomeNotice(R.string.home_empty)
                    uiState.noSearchResults -> HomeNotice(R.string.home_search_empty)
                    else ->
                        LazyColumn(
                            contentPadding =
                                PaddingValues(
                                    start = ScreenHorizontalMargin,
                                    end = ScreenHorizontalMargin,
                                    bottom = 88.dp,
                                ),
                            verticalArrangement = Arrangement.spacedBy(SpaceUnit),
                        ) {
                            items(uiState.books, key = HomeBookUiState::id) { book ->
                                BookRow(book, thumbnailLoader, onBookClick)
                            }
                        }
                }
            }
            ExtendedFloatingActionButton(
                onClick = onNewBook,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.home_new_book)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(ScreenHorizontalMargin),
            )
        }
    }
}

@Composable
private fun SortMenu(
    sort: BookProjectSort,
    onSortChange: (BookProjectSort) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(horizontal = ScreenHorizontalMargin, vertical = SpaceUnit)) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.heightIn(min = MinTouchTarget),
            shape = RoundedCornerShape(percent = 50),
            border = BorderStroke(1.dp, ColorPrimary),
        ) {
            Text(stringResource(sort.labelRes()))
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            BookProjectSort.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(stringResource(candidate.labelRes())) },
                    onClick = {
                        expanded = false
                        onSortChange(candidate)
                    },
                )
            }
        }
    }
}

@Composable
private fun BookRow(
    book: HomeBookUiState,
    thumbnailLoader: PageThumbnailLoader,
    onClick: (HomeBookUiState) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onClick(book) },
        shape = RoundedCornerShape(CardCornerRadius),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, ColorDivider),
        shadowElevation = 1.dp,
    ) {
        Row(modifier = Modifier.padding(SpaceUnit), verticalAlignment = Alignment.CenterVertically) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(8.dp),
                color = ColorDivider,
            ) {
                val firstPageId = book.firstPageId
                if (firstPageId == null) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = ColorTextSecondary)
                    }
                } else {
                    PageThumbnail(
                        pageId = firstPageId,
                        rotation = book.firstPageRotation,
                        crop = book.firstPageCrop,
                        loader = thumbnailLoader,
                        targetWidth = 56.dp,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(SpaceUnit * 1.5f))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    book.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = ColorText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                book.author?.takeIf(String::isNotBlank)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = ColorTextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    stringResource(
                        R.string.home_book_meta,
                        book.pageCount,
                        book.updatedAt.atZone(ZoneId.systemDefault()).format(UPDATED_FORMAT),
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = ColorTextSecondary,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

@Composable
private fun HomeNotice(
    messageRes: Int,
    onReload: (() -> Unit)? = null,
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(messageRes), color = ColorTextSecondary)
            onReload?.let { TextButton(onClick = it) { Text(stringResource(R.string.home_reload)) } }
        }
    }
}

private fun BookProjectSort.labelRes(): Int =
    when (this) {
        BookProjectSort.UPDATED_AT -> R.string.home_sort_updated
        BookProjectSort.CREATED_AT -> R.string.home_sort_created
        BookProjectSort.TITLE -> R.string.home_sort_title
    }

private val UPDATED_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/M/d HH:mm")
