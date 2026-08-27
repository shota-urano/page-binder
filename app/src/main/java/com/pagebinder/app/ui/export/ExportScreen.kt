package com.pagebinder.app.ui.export

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pagebinder.app.R
import com.pagebinder.app.domain.ExportPdfQuality
import com.pagebinder.app.domain.ExportProgressPhase
import com.pagebinder.app.domain.ExportType
import com.pagebinder.app.ui.theme.ButtonCornerRadius
import com.pagebinder.app.ui.theme.CardCornerRadius
import com.pagebinder.app.ui.theme.ColorAccent
import com.pagebinder.app.ui.theme.ColorDivider
import com.pagebinder.app.ui.theme.ColorPrimary
import com.pagebinder.app.ui.theme.ColorPrimaryDark
import com.pagebinder.app.ui.theme.ColorSuccess
import com.pagebinder.app.ui.theme.ColorTextSecondary
import com.pagebinder.app.ui.theme.ColorWarning
import com.pagebinder.app.ui.theme.DISABLED_ALPHA
import com.pagebinder.app.ui.theme.MinTouchTarget
import com.pagebinder.app.ui.theme.ScreenHorizontalMargin
import com.pagebinder.app.ui.theme.SpaceUnit

/** 書き出し画面の操作。ViewModel を持ち込まずに画面をテストできるようにまとめてある */
@Stable
class ExportScreenActions(
    val onBack: () -> Unit,
    val onFormatChange: (ExportType) -> Unit,
    val onFileNameChange: (String) -> Unit,
    val onPageRangeSelectionChange: (ExportPageRangeSelection) -> Unit,
    val onPageRangeStartChange: (String) -> Unit,
    val onPageRangeEndChange: (String) -> Unit,
    val onPdfQualityChange: (ExportPdfQuality) -> Unit,
    val onPermissionConfirmedChange: (Boolean) -> Unit,
    val onOcrWarningReviewRequested: () -> Unit,
    val onOcrWarningContinue: () -> Unit,
    val onOcrWarningAbort: () -> Unit,
    val onStartExportRequested: () -> Unit,
    val onCancelExport: () -> Unit,
    val onResultDismissed: () -> Unit,
)

/**
 * 書き出し画面（docs/design/11-export.md）。
 *
 * 描くのはアプリバーから下のコンテンツ領域だけで、ステータスバー・ナビゲーションバーは OS が描く
 * （docs/design/system/03-principles.md「モック画像の読み方」）。表示値はすべて [uiState] から描き、
 * モックのサンプルデータは持たない。
 */
@Composable
fun ExportScreen(
    uiState: ExportUiState,
    actions: ExportScreenActions,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            ExportTopBar(onBack = actions.onBack)
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = ScreenHorizontalMargin),
                verticalArrangement = Arrangement.spacedBy(SpaceUnit * 2),
            ) {
                ExportFormatCard(
                    format = uiState.format,
                    enabled = !uiState.exportInProgress,
                    onFormatChange = actions.onFormatChange,
                )
                ExportSettingsCard(uiState = uiState, actions = actions)
                if (uiState.ocrWarningVisible) {
                    OcrIncompleteBanner(
                        incompletePageCount = uiState.ocrIncompletePageCount,
                        onReview = actions.onOcrWarningReviewRequested,
                    )
                }
                ExportConsentCard(
                    uiState = uiState.consent,
                    onPermissionConfirmedChange = actions.onPermissionConfirmedChange,
                )
                Spacer(modifier = Modifier.height(SpaceUnit))
            }
            ExportBottomBar(uiState = uiState, actions = actions)
        }
    }
    if (uiState.ocrWarningDialogVisible) {
        OcrIncompleteDialog(
            incompletePageCount = uiState.ocrIncompletePageCount,
            onContinue = actions.onOcrWarningContinue,
            onAbort = actions.onOcrWarningAbort,
        )
    }
}

@Composable
private fun ExportTopBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget + SpaceUnit * 2)
                .padding(horizontal = SpaceUnit),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(MinTouchTarget)) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.export_back),
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(modifier = Modifier.width(SpaceUnit))
        Text(
            text = stringResource(R.string.export_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}

@Composable
private fun ExportCard(
    @StringRes titleRes: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(ScreenHorizontalMargin)) {
            // カード見出しは「見出し 22sp」= headlineSmall（docs/design/system/01-tokens.md タイポグラフィ）。
            // titleLarge(18sp) はアプリバーの画面タイトルとダイアログタイトル用
            Text(
                text = stringResource(titleRes),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(SpaceUnit))
            content()
        }
    }
}

@Composable
private fun ExportFormatCard(
    format: ExportType,
    enabled: Boolean,
    onFormatChange: (ExportType) -> Unit,
    modifier: Modifier = Modifier,
) {
    ExportCard(titleRes = R.string.export_format_title, modifier = modifier) {
        Column(modifier = Modifier.selectableGroup()) {
            ExportType.entries.forEach { candidate ->
                ExportFormatRow(
                    format = candidate,
                    selected = candidate == format,
                    enabled = enabled,
                    onSelect = { onFormatChange(candidate) },
                )
            }
        }
    }
}

@Composable
private fun ExportFormatRow(
    format: ExportType,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onSelect,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            enabled = enabled,
            colors =
                RadioButtonDefaults.colors(
                    selectedColor = ColorPrimary,
                    unselectedColor = ColorTextSecondary,
                ),
        )
        Spacer(modifier = Modifier.width(SpaceUnit))
        Text(
            text = stringResource(format.labelRes()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ExportSettingsCard(
    uiState: ExportUiState,
    actions: ExportScreenActions,
    modifier: Modifier = Modifier,
) {
    ExportCard(titleRes = R.string.export_settings_title, modifier = modifier) {
        OutlinedTextField(
            value = uiState.fileName,
            onValueChange = actions.onFileNameChange,
            label = { Text(text = stringResource(R.string.export_file_name_label)) },
            singleLine = true,
            isError = uiState.fileNameErrorVisible,
            enabled = !uiState.exportInProgress,
            shape = RoundedCornerShape(ButtonCornerRadius),
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ColorPrimary,
                    unfocusedBorderColor = ColorDivider,
                    focusedLabelColor = ColorPrimary,
                    unfocusedLabelColor = ColorTextSecondary,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (uiState.fileNameErrorVisible) {
            SettingsHelperText(
                text = stringResource(R.string.export_file_name_required),
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(SpaceUnit))
        HorizontalDivider(color = ColorDivider)
        PageRangeRow(uiState = uiState, actions = actions)
        if (uiState.pdfQualityVisible) {
            HorizontalDivider(color = ColorDivider)
            PdfQualityRow(
                quality = uiState.pdfQuality,
                enabled = !uiState.exportInProgress,
                onQualityChange = actions.onPdfQualityChange,
            )
        }
    }
}

@Composable
private fun PageRangeRow(
    uiState: ExportUiState,
    actions: ExportScreenActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SettingsDropdownRow(
            label = stringResource(R.string.export_page_range_label),
            value = uiState.pageRangeValueLabel(),
            enabled = !uiState.exportInProgress,
        ) { dismiss ->
            ExportPageRangeSelection.entries.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(candidate.labelRes())) },
                    onClick = {
                        actions.onPageRangeSelectionChange(candidate)
                        dismiss()
                    },
                )
            }
        }
        if (uiState.pageRangeSelection == ExportPageRangeSelection.CUSTOM) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = SpaceUnit),
                horizontalArrangement = Arrangement.spacedBy(SpaceUnit),
            ) {
                PageNumberField(
                    value = uiState.pageRangeStartInput,
                    labelRes = R.string.export_page_range_start,
                    isError = uiState.pageRangeErrorVisible,
                    enabled = !uiState.exportInProgress,
                    onValueChange = actions.onPageRangeStartChange,
                    modifier = Modifier.weight(1f),
                )
                PageNumberField(
                    value = uiState.pageRangeEndInput,
                    labelRes = R.string.export_page_range_end,
                    isError = uiState.pageRangeErrorVisible,
                    enabled = !uiState.exportInProgress,
                    onValueChange = actions.onPageRangeEndChange,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (uiState.pageRangeErrorVisible) {
            SettingsHelperText(
                text = stringResource(R.string.export_page_range_invalid, uiState.pageCount),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun PageNumberField(
    value: String,
    @StringRes labelRes: Int,
    isError: Boolean,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = stringResource(labelRes)) },
        singleLine = true,
        isError = isError,
        enabled = enabled,
        shape = RoundedCornerShape(ButtonCornerRadius),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors =
            OutlinedTextFieldDefaults.colors(
                focusedBorderColor = ColorPrimary,
                unfocusedBorderColor = ColorDivider,
                focusedLabelColor = ColorPrimary,
                unfocusedLabelColor = ColorTextSecondary,
            ),
        modifier = modifier,
    )
}

@Composable
private fun PdfQualityRow(
    quality: ExportPdfQuality,
    enabled: Boolean,
    onQualityChange: (ExportPdfQuality) -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsDropdownRow(
        label = stringResource(R.string.export_pdf_quality_label),
        value = stringResource(quality.labelRes()),
        enabled = enabled,
        modifier = modifier,
    ) { dismiss ->
        ExportPdfQuality.entries.forEach { candidate ->
            DropdownMenuItem(
                text = { Text(text = stringResource(candidate.labelRes())) },
                onClick = {
                    onQualityChange(candidate)
                    dismiss()
                },
            )
        }
    }
}

@Composable
private fun SettingsDropdownRow(
    label: String,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    menuItems: @Composable (dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget)
                    .clickable(enabled = enabled) { expanded = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = ColorTextSecondary,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = value,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.width(SpaceUnit))
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = ColorTextSecondary,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            menuItems { expanded = false }
        }
    }
}

@Composable
private fun SettingsHelperText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = modifier.padding(top = SpaceUnit / 2),
    )
}

/**
 * OCR未完了の警告バナー（FR-EXP-009）。
 * 地色トークンは未定義なので `--color-warning` の薄地として不透明度で作る（design 11-export の記述どおり）。
 */
@Composable
private fun OcrIncompleteBanner(
    incompletePageCount: Int,
    onReview: () -> Unit,
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
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = ColorWarning,
            )
            Spacer(modifier = Modifier.width(SpaceUnit * 1.5f))
            Text(
                text = stringResource(R.string.export_ocr_warning, incompletePageCount),
                style = MaterialTheme.typography.bodyMedium,
                color = ColorWarning,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReview, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(
                    text = stringResource(R.string.export_ocr_warning_action),
                    style = MaterialTheme.typography.labelLarge,
                    color = ColorWarning,
                )
            }
        }
    }
}

/** 続行/中止の選択（FR-EXP-009）。中止を選ぶ限り書き出しは始まらない */
@Composable
private fun OcrIncompleteDialog(
    incompletePageCount: Int,
    onContinue: () -> Unit,
    onAbort: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAbort,
        shape = RoundedCornerShape(CardCornerRadius),
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text(
                text = stringResource(R.string.export_ocr_warning_dialog_title),
                style = MaterialTheme.typography.titleLarge,
            )
        },
        text = {
            Text(
                text =
                    stringResource(
                        R.string.export_ocr_warning_dialog_message,
                        incompletePageCount,
                    ),
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = {
            TextButton(onClick = onContinue) {
                Text(
                    text = stringResource(R.string.export_ocr_warning_continue),
                    style = MaterialTheme.typography.labelLarge,
                    color = ColorPrimary,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onAbort) {
                Text(
                    text = stringResource(R.string.export_ocr_warning_abort),
                    style = MaterialTheme.typography.labelLarge,
                    color = ColorTextSecondary,
                )
            }
        },
    )
}

@Composable
private fun ExportBottomBar(
    uiState: ExportUiState,
    actions: ExportScreenActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalMargin)
                .padding(bottom = SpaceUnit * 2),
        verticalArrangement = Arrangement.spacedBy(SpaceUnit),
    ) {
        uiState.result?.let { result ->
            ExportResultCard(result = result, onDismiss = actions.onResultDismissed)
        }
        val progress = uiState.progress
        if (progress != null) {
            ExportProgressCard(progress = progress, onCancel = actions.onCancelExport)
        } else {
            ExportStartButton(onClick = actions.onStartExportRequested)
        }
    }
}

@Composable
private fun ExportStartButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Button(
        onClick = onClick,
        shape = RoundedCornerShape(ButtonCornerRadius),
        interactionSource = interactionSource,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = if (pressed) ColorPrimaryDark else ColorPrimary,
                contentColor = Color.White,
                disabledContainerColor = ColorPrimary.copy(alpha = DISABLED_ALPHA),
                disabledContentColor = Color.White.copy(alpha = DISABLED_ALPHA),
            ),
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget),
    ) {
        Text(
            text = stringResource(R.string.export_start),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** 進捗（docs/design/system/02-components.md「進捗」）。キャンセルを常に併置する */
@Composable
private fun ExportProgressCard(
    progress: ExportProgressUiState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(ScreenHorizontalMargin)) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                color = ColorAccent,
                trackColor = ColorDivider,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(SpaceUnit))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(progress.phase.labelRes()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ColorTextSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.export_progress_percent, progress.percent),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ColorTextSecondary,
                )
            }
            TextButton(
                onClick = onCancel,
                modifier = Modifier.align(Alignment.End).heightIn(min = MinTouchTarget),
            ) {
                Text(
                    text = stringResource(R.string.export_cancel),
                    style = MaterialTheme.typography.labelLarge,
                    color = ColorPrimary,
                )
            }
        }
    }
}

@Composable
private fun ExportResultCard(
    result: ExportResultUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val succeeded = result is ExportResultUiState.Succeeded
    val accent = if (succeeded) ColorSuccess else MaterialTheme.colorScheme.error
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(ScreenHorizontalMargin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (succeeded) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = accent,
            )
            Spacer(modifier = Modifier.width(SpaceUnit * 1.5f))
            Text(
                text = stringResource(result.messageRes()),
                style = MaterialTheme.typography.bodyMedium,
                color = accent,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                Text(
                    text = stringResource(R.string.export_result_dismiss),
                    style = MaterialTheme.typography.labelLarge,
                    color = ColorTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun ExportUiState.pageRangeValueLabel(): String =
    when (pageRangeSelection) {
        ExportPageRangeSelection.ALL -> stringResource(R.string.export_page_range_all, pageCount)
        ExportPageRangeSelection.CUSTOM ->
            stringResource(
                R.string.export_page_range_selected,
                pageRangeStartInput,
                pageRangeEndInput,
            )
    }

@StringRes
private fun ExportType.labelRes(): Int =
    when (this) {
        ExportType.SEARCHABLE_PDF -> R.string.export_format_searchable_pdf
        ExportType.IMAGE_PDF -> R.string.export_format_image_pdf
        ExportType.MARKDOWN -> R.string.export_format_markdown
        ExportType.TEXT_ZIP -> R.string.export_format_text_zip
        ExportType.IMAGE_ZIP -> R.string.export_format_image_zip
    }

@StringRes
private fun ExportPageRangeSelection.labelRes(): Int =
    when (this) {
        ExportPageRangeSelection.ALL -> R.string.export_page_range_all_label
        ExportPageRangeSelection.CUSTOM -> R.string.export_page_range_custom
    }

@StringRes
private fun ExportPdfQuality.labelRes(): Int =
    when (this) {
        ExportPdfQuality.HIGH -> R.string.export_pdf_quality_high
        ExportPdfQuality.STANDARD -> R.string.export_pdf_quality_standard
        ExportPdfQuality.COMPACT -> R.string.export_pdf_quality_compact
    }

@StringRes
private fun ExportProgressPhase.labelRes(): Int =
    when (this) {
        ExportProgressPhase.QUEUED -> R.string.export_progress_queued
        ExportProgressPhase.GENERATING -> R.string.export_progress_generating
        ExportProgressPhase.WRITING -> R.string.export_progress_writing
    }

@StringRes
private fun ExportResultUiState.messageRes(): Int =
    when (this) {
        ExportResultUiState.Succeeded -> R.string.export_result_succeeded
        is ExportResultUiState.Failed ->
            when (guidance) {
                ExportFailureGuidance.CANCELLED -> R.string.export_result_cancelled
                ExportFailureGuidance.PDF_FALLBACK -> R.string.export_result_pdf_fallback
                ExportFailureGuidance.GENERATION_FAILED -> R.string.export_result_generation_failed
                ExportFailureGuidance.DESTINATION_UNAVAILABLE ->
                    R.string.export_result_destination_unavailable
                ExportFailureGuidance.WRITE_FAILED -> R.string.export_result_write_failed
            }
    }

/** 警告バナーの薄地。トークンに warning-container が無いため不透明度で作る */
private const val WARNING_SURFACE_ALPHA = 0.12f
