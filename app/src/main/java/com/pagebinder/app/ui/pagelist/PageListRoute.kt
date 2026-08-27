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
 * 画面遷移（編集画面へ・削除確認へ）は呼び出し側が決める。この画面は選択件数を確定させるところまでで、
 * 削除そのものは確認ダイアログを持つ実装単位の担当（docs/specs/08-page-editing.md §6・§9）。
 */
@Composable
fun PageListRoute(
    viewModel: PageListViewModel,
    thumbnailLoader: PageThumbnailLoader,
    onBack: () -> Unit,
    onPageOpened: (UUID) -> Unit,
    onDeleteSelectedRequested: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val actions =
        remember(viewModel, onBack, onPageOpened, onDeleteSelectedRequested) {
            PageListScreenActions(
                onBack = onBack,
                onViewModeChange = viewModel::onViewModeChange,
                onFilterChange = viewModel::onFilterChange,
                onPageOpened = onPageOpened,
                onPageLongPressed = viewModel::onPageLongPressed,
                onSelectionToggled = viewModel::onSelectionToggled,
                onSelectionCleared = viewModel::onSelectionCleared,
                onDeleteSelectedRequested = onDeleteSelectedRequested,
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
