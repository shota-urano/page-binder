package com.pagebinder.app.ui.ocredit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader

/**
 * OCR編集画面と ViewModel の接続。
 *
 * 画面遷移（戻る先）は呼び出し側が決める。この画面が持つのは1ページ分のOCR結果の表示と修正だけで、
 * OCRの実行そのものはキュー側の担当（docs/specs/09-ocr.md §3.2）。
 */
@Composable
fun OcrEditRoute(
    viewModel: OcrEditViewModel,
    imageLoader: PageThumbnailLoader,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val actions =
        remember(viewModel, onBack) {
            OcrEditScreenActions(
                onBack = onBack,
                onReload = viewModel::load,
                onSearchToggled = viewModel::onSearchToggled,
                onSearchQueryChange = viewModel::onSearchQueryChange,
                onSearchNext = viewModel::onSearchNext,
                onSearchPrevious = viewModel::onSearchPrevious,
                onRerunRequested = viewModel::onRerunRequested,
                onZoomIn = viewModel::onZoomIn,
                onZoomOut = viewModel::onZoomOut,
                onSplitRatioChange = viewModel::onSplitRatioChange,
                onTextChange = viewModel::onTextChange,
                onSaveRequested = viewModel::onSaveRequested,
                onRevertRequested = viewModel::onRevertRequested,
                onRevertConfirmed = viewModel::onRevertConfirmed,
                onRevertDismissed = viewModel::onRevertDismissed,
            )
        }

    OcrEditScreen(
        uiState = uiState,
        imageLoader = imageLoader,
        actions = actions,
        modifier = modifier,
    )
}
