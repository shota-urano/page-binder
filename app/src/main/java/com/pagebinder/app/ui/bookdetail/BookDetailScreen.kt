package com.pagebinder.app.ui.bookdetail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pagebinder.app.R
import com.pagebinder.app.ui.formatStorageBytes
import com.pagebinder.app.ui.theme.ButtonCornerRadius
import com.pagebinder.app.ui.theme.CardCornerRadius
import com.pagebinder.app.ui.theme.ColorAccent
import com.pagebinder.app.ui.theme.ColorDivider
import com.pagebinder.app.ui.theme.ColorError
import com.pagebinder.app.ui.theme.ColorTextSecondary
import com.pagebinder.app.ui.theme.ColorWarning
import com.pagebinder.app.ui.theme.MinTouchTarget
import com.pagebinder.app.ui.theme.ScreenHorizontalMargin
import com.pagebinder.app.ui.theme.SpaceUnit

/**
 * 統計の値だけを一意に指す印。ページ数・OCR完了・エラーは同じ数字が並ぶため、
 * 「撮影でページが増えたときに統計が更新される」ことを UI テストから読むには印が要る。
 */
const val BOOK_DETAIL_PAGE_COUNT_TEST_TAG = "book-detail-page-count"
const val BOOK_DETAIL_OCR_COMPLETED_TEST_TAG = "book-detail-ocr-completed"
const val BOOK_DETAIL_OCR_PROGRESS_TEST_TAG = "book-detail-ocr-progress"
const val BOOK_DETAIL_OCR_ERROR_TEST_TAG = "book-detail-ocr-error"
const val BOOK_DETAIL_STORAGE_TEST_TAG = "book-detail-storage"

data class BookDetailActions(
    val onBack: () -> Unit,
    val onEdit: () -> Unit,
    val onManualCapture: () -> Unit,
    val onContinuousCapture: () -> Unit,
    val onPageList: () -> Unit,
    val onOcrBatch: () -> Unit,
    val onExport: () -> Unit,
    val onBookSettings: () -> Unit,
    val onMoveToTrashRequested: () -> Unit,
    val onMoveToTrashConfirmed: () -> Unit,
    val onMoveToTrashDismissed: () -> Unit,
    val onReload: () -> Unit,
    /** 未完了の書き出しの「再試行」（docs/specs/11-export.md §3.2） */
    val onRetryInterruptedExport: () -> Unit,
    val manualCaptureAvailable: Boolean = true,
    val continuousCaptureAvailable: Boolean = true,
    val exportAvailable: Boolean = true,
)

@Composable
fun BookDetailScreen(
    uiState: BookDetailUiState,
    actions: BookDetailActions,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            DetailTopBar(uiState.title, actions.onBack, actions.onEdit)
            if (uiState.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.book_detail_loading), color = ColorTextSecondary)
                }
            } else if (uiState.operationError == BookDetailOperationError.LOAD) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = actions.onReload) { Text(stringResource(R.string.book_detail_load_failed)) }
                }
            } else {
                Column(
                    modifier =
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = ScreenHorizontalMargin),
                    verticalArrangement = Arrangement.spacedBy(SpaceUnit * 2),
                ) {
                    uiState.interruptedExport?.let { interrupted ->
                        InterruptedExportBanner(
                            count = interrupted.count,
                            onRetry = actions.onRetryInterruptedExport,
                        )
                    }
                    InfoCard(uiState)
                    StatisticsCard(uiState)
                    Row(horizontalArrangement = Arrangement.spacedBy(SpaceUnit)) {
                        Button(
                            onClick = actions.onManualCapture,
                            enabled = actions.manualCaptureAvailable,
                            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget),
                            shape = RoundedCornerShape(ButtonCornerRadius),
                        ) { Text(stringResource(R.string.book_detail_manual_capture)) }
                        OutlinedButton(
                            onClick = actions.onContinuousCapture,
                            enabled = actions.continuousCaptureAvailable,
                            modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget),
                            shape = RoundedCornerShape(ButtonCornerRadius),
                        ) { Text(stringResource(R.string.book_detail_continuous_capture)) }
                    }
                    Surface(
                        shape = RoundedCornerShape(CardCornerRadius),
                        border = BorderStroke(1.dp, ColorDivider),
                        color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column {
                            DetailAction(R.string.book_detail_pages, actions.onPageList)
                            DetailAction(R.string.book_detail_ocr_batch, actions.onOcrBatch)
                            DetailAction(
                                R.string.book_detail_export,
                                actions.onExport,
                                enabled = actions.exportAvailable,
                            )
                            DetailAction(R.string.book_detail_settings, actions.onBookSettings, divider = false)
                        }
                    }
                    // 無効な導線は理由まで出す（押せるのに何も起きない状態を残さない）。
                    // ページ0件時の表示はデザイン素材に無いため（docs/design/03-book-detail.md 未定事項）
                    // 補助テキストで最小限に説明する
                    if (!actions.exportAvailable && uiState.pageCount == 0) {
                        Text(
                            stringResource(R.string.book_detail_export_unavailable),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ColorTextSecondary,
                        )
                    }
                    TextButton(
                        onClick = actions.onMoveToTrashRequested,
                        modifier = Modifier.align(Alignment.End).heightIn(min = MinTouchTarget),
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = ColorError)
                        Text(stringResource(R.string.book_detail_move_to_trash), color = ColorError)
                    }
                    uiState.operationError?.takeIf { it != BookDetailOperationError.LOAD }?.let {
                        Text(stringResource(it.messageRes()), color = ColorError)
                    }
                    uiState.queuedOcrCount?.let { count ->
                        // 0件は「予約できなかった」ではなく「OCR待ちが無い」＝全ページ処理済みを意味する
                        val message =
                            if (count == 0) {
                                stringResource(R.string.book_detail_ocr_nothing_queued)
                            } else {
                                stringResource(R.string.book_detail_ocr_queued, count)
                            }
                        Text(message, color = ColorTextSecondary)
                    }
                    Spacer(Modifier.padding(bottom = SpaceUnit))
                }
            }
        }
    }
    uiState.moveToTrashConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = actions.onMoveToTrashDismissed,
            shape = RoundedCornerShape(CardCornerRadius),
            title = { Text(stringResource(R.string.book_detail_trash_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.book_detail_trash_dialog_message,
                        confirmation.title,
                        confirmation.pageCount,
                        formatStorageBytes(confirmation.storageBytes),
                    ),
                )
            },
            confirmButton = {
                Button(
                    onClick = actions.onMoveToTrashConfirmed,
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                    shape = RoundedCornerShape(ButtonCornerRadius),
                    colors = ButtonDefaults.buttonColors(containerColor = ColorError, contentColor = Color.White),
                ) { Text(stringResource(R.string.book_detail_trash_dialog_confirm)) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = actions.onMoveToTrashDismissed,
                    modifier = Modifier.heightIn(min = MinTouchTarget),
                    shape = RoundedCornerShape(ButtonCornerRadius),
                ) {
                    Text(stringResource(R.string.book_detail_trash_dialog_cancel))
                }
            },
        )
    }
}

@Composable
private fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SpaceUnit),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.book_detail_back))
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.book_detail_edit))
        }
    }
}

@Composable
private fun InfoCard(uiState: BookDetailUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        border = BorderStroke(1.dp, ColorDivider),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(SpaceUnit * 2), verticalArrangement = Arrangement.spacedBy(SpaceUnit)) {
            Text(uiState.title, style = MaterialTheme.typography.headlineSmall)
            uiState.author?.takeIf(String::isNotBlank)?.let { Text(it, color = ColorTextSecondary) }
            uiState.note?.takeIf(String::isNotBlank)?.let { Text(it) }
        }
    }
}

@Composable
private fun StatisticsCard(uiState: BookDetailUiState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        border = BorderStroke(1.dp, ColorDivider),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(SpaceUnit * 2)) {
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Statistic(
                    R.string.book_detail_page_count,
                    uiState.pageCount.toString(),
                    Modifier.weight(1f),
                    valueTestTag = BOOK_DETAIL_PAGE_COUNT_TEST_TAG,
                )
                VerticalDivider(modifier = Modifier.fillMaxHeight(), color = ColorDivider)
                Statistic(
                    R.string.book_detail_ocr_completed,
                    uiState.ocrCompletedCount.toString(),
                    Modifier.weight(1f),
                    ColorAccent,
                    showCheck = true,
                    valueTestTag = BOOK_DETAIL_OCR_COMPLETED_TEST_TAG,
                )
            }
            HorizontalDivider(Modifier.padding(vertical = SpaceUnit), color = ColorDivider)
            Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                Statistic(
                    R.string.book_detail_error_count,
                    uiState.ocrErrorCount.toString(),
                    Modifier.weight(1f),
                    if (uiState.ocrErrorCount > 0) ColorError else MaterialTheme.colorScheme.onSurface,
                    valueTestTag = BOOK_DETAIL_OCR_ERROR_TEST_TAG,
                )
                VerticalDivider(modifier = Modifier.fillMaxHeight(), color = ColorDivider)
                Statistic(
                    R.string.book_detail_storage,
                    formatStorageBytes(uiState.storageBytes),
                    Modifier.weight(1f),
                    valueTestTag = BOOK_DETAIL_STORAGE_TEST_TAG,
                )
            }
            // 予約から完了までの間を埋める（pagebinder-1sd）。待ちが無くなれば消え、数字だけが残る
            if (uiState.ocrInProgress) {
                HorizontalDivider(Modifier.padding(vertical = SpaceUnit), color = ColorDivider)
                OcrProgress(uiState)
            }
        }
    }
}

@Composable
private fun OcrProgress(uiState: BookDetailUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(SpaceUnit)) {
        Text(
            stringResource(
                R.string.book_detail_ocr_progress,
                uiState.ocrCompletedCount,
                uiState.ocrTargetCount,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = ColorTextSecondary,
            modifier = Modifier.testTag(BOOK_DETAIL_OCR_PROGRESS_TEST_TAG),
        )
        LinearProgressIndicator(
            progress = { uiState.ocrProgress },
            modifier = Modifier.fillMaxWidth(),
            color = ColorAccent,
        )
    }
}

@Composable
private fun Statistic(
    labelRes: Int,
    value: String,
    modifier: Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
    showCheck: Boolean = false,
    valueTestTag: String? = null,
) {
    Column(modifier.padding(horizontal = SpaceUnit)) {
        Text(stringResource(labelRes), style = MaterialTheme.typography.bodyMedium, color = ColorTextSecondary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showCheck) Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = ColorAccent)
            Text(
                value,
                modifier = if (valueTestTag == null) Modifier else Modifier.testTag(valueTestTag),
                style = MaterialTheme.typography.headlineSmall,
                color = valueColor,
            )
        }
    }
}

@Composable
private fun DetailAction(
    labelRes: Int,
    onClick: () -> Unit,
    divider: Boolean = true,
    enabled: Boolean = true,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = SpaceUnit * 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val contentColor = if (enabled) MaterialTheme.colorScheme.onSurface else ColorTextSecondary
        Text(stringResource(labelRes), modifier = Modifier.weight(1f), color = contentColor)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = contentColor)
    }
    if (divider) HorizontalDivider(color = ColorDivider)
}

/**
 * 未完了の書き出しの提示（docs/specs/11-export.md §3.2 末尾）。
 *
 * この提示 UI はデザイン素材（docs/design/03-book-detail.md）に定義が無いため、
 * 書き出し画面の警告バナー（docs/design/11-export.md「警告バナー」）と同じ
 * 「⚠ + 文言 + 右端アクション」を warning の薄地で置く。新しい見た目は作らない。
 */
@Composable
private fun InterruptedExportBanner(
    count: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        color = ColorWarning.copy(alpha = WARNING_SURFACE_ALPHA),
    ) {
        Row(
            modifier =
                Modifier
                    .heightIn(min = MinTouchTarget)
                    .padding(horizontal = ScreenHorizontalMargin, vertical = SpaceUnit),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(imageVector = Icons.Filled.Warning, contentDescription = null, tint = ColorWarning)
            Spacer(Modifier.width(SpaceUnit * 1.5f))
            Text(
                text = stringResource(R.string.book_detail_interrupted_export, count),
                style = MaterialTheme.typography.bodyMedium,
                color = ColorWarning,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(
                    text = stringResource(R.string.book_detail_interrupted_export_action),
                    style = MaterialTheme.typography.labelLarge,
                    color = ColorWarning,
                )
            }
        }
    }
}

private fun BookDetailOperationError.messageRes(): Int =
    when (this) {
        BookDetailOperationError.LOAD -> R.string.book_detail_load_failed
        BookDetailOperationError.MOVE_TO_TRASH -> R.string.book_detail_trash_failed
        BookDetailOperationError.OCR_BATCH -> R.string.book_detail_ocr_failed
    }

/** 警告バナーの薄地。トークンに warning-container が無いため不透明度で作る（書き出し画面と同値） */
private const val WARNING_SURFACE_ALPHA = 0.12f
