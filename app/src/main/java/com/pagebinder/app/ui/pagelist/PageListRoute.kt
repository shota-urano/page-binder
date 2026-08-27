package com.pagebinder.app.ui.pagelist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.util.UUID

/**
 * ページ一覧画面と ViewModel の接続。
 *
 * 一覧内で完結する編集（並べ替え・削除・取り消し）はこの画面が持つ（docs/specs/08-page-editing.md §3.2・§3.4）。
 * 別画面への遷移（回転・切り取り編集）だけを呼び出し側が決める。
 */
@Composable
fun PageListRoute(
    viewModel: PageListViewModel,
    thumbnailLoader: PageThumbnailLoader,
    onBack: () -> Unit,
    onPageOpened: (UUID) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val actions =
        remember(viewModel, onBack, onPageOpened) {
            PageListScreenActions(
                onBack = onBack,
                onViewModeChange = viewModel::onViewModeChange,
                onFilterChange = viewModel::onFilterChange,
                onPageOpened = onPageOpened,
                onPageLongPressed = viewModel::onPageLongPressed,
                onSelectionToggled = viewModel::onSelectionToggled,
                onSelectionCleared = viewModel::onSelectionCleared,
                onDeleteSelectedRequested = viewModel::onDeleteSelectedRequested,
                onDeleteConfirmed = viewModel::onDeleteConfirmed,
                onDeleteDismissed = viewModel::onDeleteDismissed,
                onPageMoved = viewModel::onPageMoved,
                onReorderFinished = viewModel::onReorderFinished,
                onUndoRequested = viewModel::onUndoRequested,
                onMessageDismissed = viewModel::onMessageDismissed,
                onReload = viewModel::load,
            )
        }

    PageListScreen(
        uiState = uiState,
        thumbnailLoader = thumbnailLoader,
        actions = actions,
        modifier = modifier,
    )
}
