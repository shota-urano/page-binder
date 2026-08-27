package com.pagebinder.app.ui.pagelist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pagebinder.app.domain.PageRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ページ一覧画面（docs/design/07-page-list.md / docs/specs/08-page-editing.md §3.1）の ViewModel。
 *
 * 画面単位で不変 [PageListUiState] を [StateFlow] で公開する（AGENTS.md ルール8）。
 * ページの取得は [PageRepository] 経由だけで、Room の型はここへ来ない。
 *
 * この画面が持つのは「見せ方」（グリッド/リスト・絞り込み）と「選択」だけ。
 * 削除の確認ダイアログ・ドラッグ並べ替えは次の実装単位（docs/specs/08-page-editing.md §9）の担当で、
 * ここでは選択件数を確定させて呼び出し側へ渡すところまでを受け持つ。
 */
class PageListViewModel(
    private val projectId: UUID,
    private val pageRepository: PageRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(PageListUiState())
    val uiState: StateFlow<PageListUiState> = mutableUiState.asStateFlow()

    init {
        load()
    }

    /** ページを読み直す。失敗時は一覧を消さずにエラー表示だけを出す（docs/specs/08-page-editing.md §6） */
    fun load() {
        mutableUiState.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            runCatching { pageRepository.findByProject(projectId) }
                .onSuccess { pages ->
                    val items = pages.sortedBy { it.sequence }.map { it.toPageListItemUiState() }
                    val remainingIds = items.map(PageListItemUiState::pageId).toSet()
                    mutableUiState.update { current ->
                        current.copy(
                            loading = false,
                            loadFailed = false,
                            pages = items,
                            // 消えたページの選択は持ち越さない（件数表示が実在しないページを数えないため）
                            selectedPageIds = current.selectedPageIds.intersect(remainingIds),
                        )
                    }
                }.onFailure {
                    // 例外の内容はログへ出さない（画像パス・書籍情報が混ざりうる。AGENTS.md ルール6）
                    mutableUiState.update { it.copy(loading = false, loadFailed = true) }
                }
        }
    }

    fun onViewModeChange(viewMode: PageListViewMode) {
        mutableUiState.update { it.copy(viewMode = viewMode) }
    }

    /**
     * 絞り込みの変更。選択は引き継がない。
     * 絞り込みで隠れたページが選択に残ると、選択モードバーの件数と見えている選択が食い違うため。
     */
    fun onFilterChange(filter: PageListFilter) {
        mutableUiState.update { it.copy(filter = filter, selectedPageIds = emptySet()) }
    }

    /** 長押しで選択モードを開始する（docs/design/07-page-list.md「インタラクション」） */
    fun onPageLongPressed(pageId: UUID) {
        if (mutableUiState.value.pages.none { it.pageId == pageId }) return
        mutableUiState.update { it.copy(selectedPageIds = it.selectedPageIds + pageId) }
    }

    /** 選択モード中のセルタップ。最後の1件を外すと選択モードが終わる */
    fun onSelectionToggled(pageId: UUID) {
        if (mutableUiState.value.pages.none { it.pageId == pageId }) return
        mutableUiState.update { current ->
            val selected = current.selectedPageIds
            current.copy(
                selectedPageIds = if (pageId in selected) selected - pageId else selected + pageId,
            )
        }
    }

    /** 選択モードバーの × */
    fun onSelectionCleared() {
        mutableUiState.update { it.copy(selectedPageIds = emptySet()) }
    }

    companion object {
        fun factory(
            projectId: UUID,
            pageRepository: PageRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { PageListViewModel(projectId, pageRepository) }
            }
    }
}
