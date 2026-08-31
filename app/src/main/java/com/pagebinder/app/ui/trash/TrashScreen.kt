package com.pagebinder.app.ui.trash

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.pagebinder.app.R
import com.pagebinder.app.ui.formatStorageBytes
import com.pagebinder.app.ui.theme.ButtonCornerRadius
import com.pagebinder.app.ui.theme.CardCornerRadius
import com.pagebinder.app.ui.theme.ColorDivider
import com.pagebinder.app.ui.theme.ColorError
import com.pagebinder.app.ui.theme.ColorTextSecondary
import com.pagebinder.app.ui.theme.MinTouchTarget
import com.pagebinder.app.ui.theme.ScreenHorizontalMargin
import com.pagebinder.app.ui.theme.SpaceUnit
import java.util.UUID

@Composable
fun TrashScreen(
    uiState: TrashUiState,
    onBack: () -> Unit,
    onRestore: (UUID) -> Unit,
    onDeleteRequested: (UUID) -> Unit,
    onDeleteConfirmed: () -> Unit,
    onDeleteDismissed: () -> Unit,
    onReload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = SpaceUnit),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.trash_back))
                }
                Text(stringResource(R.string.trash_title), style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                stringResource(R.string.trash_retention),
                modifier = Modifier.padding(horizontal = ScreenHorizontalMargin),
                color = ColorTextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            uiState.operationError?.takeIf { it != TrashOperationError.LOAD }?.let {
                val messageRes =
                    if (it == TrashOperationError.RESTORE) {
                        R.string.trash_restore_failed
                    } else {
                        R.string.trash_delete_failed
                    }
                Text(
                    stringResource(messageRes),
                    color = ColorError,
                    modifier = Modifier.padding(horizontal = ScreenHorizontalMargin, vertical = SpaceUnit),
                )
            }
            when {
                uiState.operationError == TrashOperationError.LOAD ->
                    TrashNotice(R.string.trash_load_failed, onReload)
                uiState.empty -> TrashNotice(R.string.trash_empty)
                else ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(ScreenHorizontalMargin),
                        verticalArrangement = Arrangement.spacedBy(SpaceUnit),
                    ) {
                        items(uiState.books, key = TrashBookUiState::id) { book ->
                            TrashRow(
                                book = book,
                                enabled = !uiState.operationInProgress,
                                onRestore = { onRestore(book.id) },
                                onDelete = { onDeleteRequested(book.id) },
                            )
                        }
                    }
            }
        }
    }
    uiState.deleteConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = onDeleteDismissed,
            shape = RoundedCornerShape(CardCornerRadius),
            title = { Text(stringResource(R.string.trash_delete_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.trash_delete_dialog_message,
                        confirmation.title,
                        confirmation.pageCount,
                        formatStorageBytes(confirmation.storageBytes),
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = onDeleteConfirmed,
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                    shape = RoundedCornerShape(ButtonCornerRadius),
                    colors = ButtonDefaults.buttonColors(containerColor = ColorError, contentColor = Color.White),
                ) { Text(stringResource(R.string.trash_delete)) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = onDeleteDismissed,
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                    shape = RoundedCornerShape(ButtonCornerRadius),
                ) { Text(stringResource(R.string.trash_cancel)) }
            },
        )
    }
}

@Composable
private fun TrashRow(
    book: TrashBookUiState,
    enabled: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        border = BorderStroke(1.dp, ColorDivider),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(SpaceUnit), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.size(56.dp), shape = RoundedCornerShape(8.dp), color = ColorDivider) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = ColorTextSecondary)
                }
            }
            Spacer(Modifier.width(SpaceUnit * 1.5f))
            Column(Modifier.weight(1f)) {
                Text(book.title, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.trash_book_meta, book.pageCount, book.remainingDays),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ColorTextSecondary,
                )
                Row(Modifier.align(Alignment.End)) {
                    TextButton(
                        onClick = onRestore,
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = MinTouchTarget),
                    ) {
                        Text(stringResource(R.string.trash_restore))
                    }
                    TextButton(
                        onClick = onDelete,
                        enabled = enabled,
                        modifier = Modifier.heightIn(min = MinTouchTarget),
                    ) {
                        Text(stringResource(R.string.trash_delete), color = ColorError)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrashNotice(
    messageRes: Int,
    onReload: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(messageRes), color = ColorTextSecondary)
            onReload?.let { TextButton(onClick = it) { Text(stringResource(R.string.trash_reload)) } }
        }
    }
}
