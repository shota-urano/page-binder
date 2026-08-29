package com.pagebinder.app.ui.pageedit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader

/**
 * 回転・切り取り編集画面と ViewModel の接続。
 *
 * 画面を閉じる先は呼び出し側が決める。未保存の変更があるときだけ破棄確認を挟むという判断は
 * ここで行い（ViewModel に画面遷移を持ち込まないため）、確認の表示自体は UiState が持つ。
 */
@Composable
fun PageEditRoute(
    viewModel: PageEditViewModel,
    imageLoader: PageThumbnailLoader,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val unsavedChanges by rememberUpdatedState(uiState.unsavedChanges)

    val actions =
        remember(viewModel, onClose) {
            PageEditScreenActions(
                onCloseRequested = {
                    if (unsavedChanges) viewModel.onDiscardRequested() else onClose()
                },
                onReload = viewModel::load,
                onRotateClockwise = viewModel::onRotateClockwise,
                onUndoRequested = viewModel::onUndoRequested,
                onCropHandleDragged = viewModel::onCropHandleDragged,
                onCropDragFinished = viewModel::onCropDragFinished,
                onApplyToAllPagesChanged = viewModel::onApplyToAllPagesChanged,
                onSaveRequested = viewModel::onSaveRequested,
                onBulkApplyConfirmed = viewModel::onBulkApplyConfirmed,
                onBulkApplyDismissed = viewModel::onBulkApplyDismissed,
                onDiscardConfirmed = {
                    viewModel.onDiscardDismissed()
                    onClose()
                },
                onDiscardDismissed = viewModel::onDiscardDismissed,
                onMessageDismissed = viewModel::onMessageDismissed,
            )
        }

    PageEditScreen(
        uiState = uiState,
        imageLoader = imageLoader,
        actions = actions,
        modifier = modifier,
    )
}
