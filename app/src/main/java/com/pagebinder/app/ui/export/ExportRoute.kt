package com.pagebinder.app.ui.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * 書き出し画面と SAF（ACTION_CREATE_DOCUMENT）の接続。
 *
 * 保存先は Document Provider 選択画面で利用者が選ぶ（Drive専用APIは使わない — docs/specs/11-export.md §3.2 手順4）。
 * アプリ内に共有ボタンは設けない（同 §3.3）。選ばれた URI はログへ出さない（AGENTS.md ルール6）。
 */
@Composable
fun ExportRoute(
    viewModel: ExportViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val safRequest = uiState.safRequest

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.CreateDocument(safRequest?.mimeType ?: FALLBACK_MIME_TYPE),
        ) { uri -> viewModel.onDestinationSelected(uri?.toString()) }

    LaunchedEffect(safRequest) {
        if (safRequest != null) {
            launcher.launch(safRequest.suggestedFileName)
            viewModel.onSafRequestHandled()
        }
    }

    val actions =
        remember(viewModel, onBack) {
            ExportScreenActions(
                onBack = onBack,
                onFormatChange = viewModel::onFormatChange,
                onFileNameChange = viewModel::onFileNameChange,
                onPageRangeSelectionChange = viewModel::onPageRangeSelectionChange,
                onPageRangeStartChange = viewModel::onPageRangeStartChange,
                onPageRangeEndChange = viewModel::onPageRangeEndChange,
                onPdfQualityChange = viewModel::onPdfQualityChange,
                onPermissionConfirmedChange = viewModel::onPermissionConfirmedChange,
                onOcrWarningReviewRequested = viewModel::onOcrWarningReviewRequested,
                onOcrWarningContinue = viewModel::onOcrWarningContinue,
                onOcrWarningAbort = viewModel::onOcrWarningAbort,
                onStartExportRequested = viewModel::onStartExportRequested,
                onCancelExport = viewModel::onCancelExport,
                onResultDismissed = viewModel::onResultDismissed,
            )
        }

    ExportScreen(uiState = uiState, actions = actions, modifier = modifier)
}

/** 起動要求が無い間だけ使う既定値。実際に開くときは要求側の MIME タイプに差し替わる */
private const val FALLBACK_MIME_TYPE = "application/pdf"
