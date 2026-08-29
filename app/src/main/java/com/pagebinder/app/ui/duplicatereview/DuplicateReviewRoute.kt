package com.pagebinder.app.ui.duplicatereview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader

/**
 * 重複候補比較・黒画面候補一覧と ViewModel の接続。
 *
 * 確認と削除はこの画面で完結する（docs/specs/08-page-editing.md §3.2 FR-EDT-006・FR-EDT-007）。
 * 呼び出し側が決めるのは戻り先だけ。
 */
@Composable
fun DuplicateReviewRoute(
    viewModel: DuplicateReviewViewModel,
    thumbnailLoader: PageThumbnailLoader,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val actions =
        remember(viewModel, onBack) {
            DuplicateReviewScreenActions(
                onBack = onBack,
                onKeepPageSelected = viewModel::onKeepPageSelected,
                onBlackPageDeleteRequested = viewModel::onBlackPageDeleteRequested,
                onBlackPageKept = viewModel::onBlackPageKept,
                onDuplicateDeleteRequested = viewModel::onDuplicateDeleteRequested,
                onDeleteConfirmed = viewModel::onDeleteConfirmed,
                onDeleteDismissed = viewModel::onDeleteDismissed,
                onUndoRequested = viewModel::onUndoRequested,
                onMessageDismissed = viewModel::onMessageDismissed,
                onReload = viewModel::load,
            )
        }

    DuplicateReviewScreen(
        uiState = uiState,
        thumbnailLoader = thumbnailLoader,
        actions = actions,
        modifier = modifier,
    )
}
