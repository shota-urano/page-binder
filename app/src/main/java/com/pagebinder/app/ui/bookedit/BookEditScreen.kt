package com.pagebinder.app.ui.bookedit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import com.pagebinder.app.R
import com.pagebinder.app.ui.theme.ButtonCornerRadius
import com.pagebinder.app.ui.theme.MinTouchTarget
import com.pagebinder.app.ui.theme.ScreenHorizontalMargin
import com.pagebinder.app.ui.theme.SpaceUnit

@Composable
fun BookEditScreen(
    uiState: BookEditUiState,
    onTitleChange: (String) -> Unit,
    onAuthorChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = SpaceUnit),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.book_edit_back),
                    )
                }
                Text(
                    stringResource(
                        if (uiState.editing) R.string.book_edit_title_edit else R.string.book_edit_title_new,
                    ),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
        },
        bottomBar = {
            Button(
                onClick = onSave,
                enabled = uiState.canSave,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(ScreenHorizontalMargin)
                        .heightIn(min = MinTouchTarget),
                shape = RoundedCornerShape(ButtonCornerRadius),
            ) {
                Text(stringResource(if (uiState.saving) R.string.book_edit_saving else R.string.book_edit_save))
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .padding(horizontal = ScreenHorizontalMargin)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(SpaceUnit * 2),
        ) {
            BookField(
                value = uiState.title,
                onValueChange = onTitleChange,
                label = stringResource(R.string.book_edit_field_title),
                error = uiState.titleError,
                limit = TITLE_LIMIT,
                singleLine = true,
                imeAction = ImeAction.Next,
            )
            BookField(
                value = uiState.author,
                onValueChange = onAuthorChange,
                label = stringResource(R.string.book_edit_field_author),
                error = uiState.authorError,
                limit = AUTHOR_LIMIT,
                singleLine = true,
                imeAction = ImeAction.Next,
            )
            BookField(
                value = uiState.note,
                onValueChange = onNoteChange,
                label = stringResource(R.string.book_edit_field_note),
                error = uiState.noteError,
                limit = NOTE_LIMIT,
                singleLine = false,
                imeAction = ImeAction.Default,
                minLines = 4,
            )
            uiState.operationError?.let {
                Text(
                    text = stringResource(it.messageRes()),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun BookField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    error: BookEditFieldError?,
    limit: Int,
    singleLine: Boolean,
    imeAction: ImeAction,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        shape = RoundedCornerShape(ButtonCornerRadius),
        supportingText = {
            Row(modifier = Modifier.fillMaxWidth()) {
                error?.let { Text(stringResource(it.messageRes())) }
                Text(
                    text = "${value.length}/$limit",
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun BookEditFieldError.messageRes(): Int =
    when (this) {
        BookEditFieldError.REQUIRED -> R.string.book_edit_error_required
        BookEditFieldError.TOO_LONG -> R.string.book_edit_error_too_long
    }

private fun BookEditOperationError.messageRes(): Int =
    when (this) {
        BookEditOperationError.LOAD -> R.string.book_edit_error_load
        BookEditOperationError.CREATE -> R.string.book_edit_error_create
        BookEditOperationError.UPDATE -> R.string.book_edit_error_update
    }
