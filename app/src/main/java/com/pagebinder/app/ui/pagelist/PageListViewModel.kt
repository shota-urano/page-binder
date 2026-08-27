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
 * 受け持つのは「見せ方」（グリッド/リスト・絞り込み）・「選択」・一覧内の編集
 * （ドラッグ並べ替え・件数を出す削除確認・直前1操作の取り消し。docs/specs/08-page-editing.md §3.2・§3.4・§6）。
 * 回転・切り取りは別画面（`PageEditViewModel`）の担当。
 */
class PageListViewModel(
    private val projectId: UUID,
    private val pageRepository: PageRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(PageListUiState())
    val uiState: StateFlow<PageListUiState> = mutableUiState.asStateFlow()

    /** ドラッグで並びを動かしたが、まだ永続化していない */
    private var reorderPending = false

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

    /**
     * ドラッグ中の入れ替え（docs/specs/08-page-editing.md §3.2 FR-EDT-002）。
     *
     * 指の動きに合わせて UiState の並びと連番だけを先に動かし、永続化は指を離したとき
     * （[onReorderFinished]）に1回だけ行う。1コマ動くたびに書き込むと、1回のドラッグが
     * 何十件もの取り消し履歴を作ってしまう（履歴は直前1操作。同 §3.4）。
     */
    fun onPageMoved(
        fromIndex: Int,
        toIndex: Int,
    ) {
        var moved = false
        mutableUiState.update { current ->
            val pages = current.pages
            if (!current.reorderEnabled ||
                fromIndex == toIndex ||
                fromIndex !in pages.indices ||
                toIndex !in pages.indices
            ) {
                return@update current
            }
            moved = true
            val reordered = pages.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
            current.copy(pages = reordered.mapIndexed { index, page -> page.copy(sequence = index + 1) })
        }
        if (moved) reorderPending = true
    }

    /** 指を離したときの確定。失敗したら永続化された順序を読み直して元へ戻す（同 §6） */
    fun onReorderFinished() {
        if (!reorderPending) return
        reorderPending = false
        val orderedPageIds = mutableUiState.value.pages.map(PageListItemUiState::pageId)
        viewModelScope.launch {
            runCatching { pageRepository.reorder(projectId, orderedPageIds) }
                .onSuccess {
                    mutableUiState.update {
                        it.copy(undoableEdit = PageListUndoableEdit.Reorder, operationError = null)
                    }
                }.onFailure {
                    mutableUiState.update { it.copy(operationError = PageListOperationError.REORDER) }
                }
            load()
        }
    }

    /** 選択モードバーのごみ箱。削除の前に必ず件数付きの確認を出す（同 §6） */
    fun onDeleteSelectedRequested() {
        val current = mutableUiState.value
        if (current.selectedPageIds.isEmpty() || current.deleting) return
        mutableUiState.update {
            it.copy(deleteConfirmation = PageDeleteConfirmationUiState(pageCount = it.selectedCount))
        }
    }

    /** 確認ダイアログのキャンセル。選択は残したままにする */
    fun onDeleteDismissed() {
        mutableUiState.update { it.copy(deleteConfirmation = null) }
    }

    fun onDeleteConfirmed() {
        val targets = mutableUiState.value.selectedPageIds
        if (targets.isEmpty()) {
            onDeleteDismissed()
            return
        }
        mutableUiState.update { it.copy(deleteConfirmation = null, deleting = true) }
        viewModelScope.launch {
            runCatching { pageRepository.delete(projectId, targets) }
                .onSuccess {
                    mutableUiState.update {
                        it.copy(
                            deleting = false,
                            selectedPageIds = emptySet(),
                            undoableEdit = PageListUndoableEdit.Delete(pageCount = targets.size),
                            operationError = null,
                        )
                    }
                }.onFailure {
                    mutableUiState.update {
                        it.copy(deleting = false, operationError = PageListOperationError.DELETE)
                    }
                }
            load()
        }
    }

    /**
     * 直前1操作の取り消し（docs/specs/08-page-editing.md §3.4）。
     * ページ削除の復元はごみ箱ではなくこの取り消しで担保する（同 §7）。
     */
    fun onUndoRequested() {
        if (mutableUiState.value.undoableEdit == null) return
        viewModelScope.launch {
            runCatching { pageRepository.undoLastEdit() }
                .onSuccess { restored ->
                    mutableUiState.update {
                        it.copy(
                            undoableEdit = null,
                            operationError = if (restored) null else PageListOperationError.UNDO,
                        )
                    }
                }.onFailure {
                    mutableUiState.update {
                        it.copy(undoableEdit = null, operationError = PageListOperationError.UNDO)
                    }
                }
            load()
        }
    }

    /** 取り消し案内・失敗表示を閉じる */
    fun onMessageDismissed() {
        mutableUiState.update { it.copy(undoableEdit = null, operationError = null) }
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
