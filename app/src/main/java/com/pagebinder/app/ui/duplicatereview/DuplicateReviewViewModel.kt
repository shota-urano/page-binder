package com.pagebinder.app.ui.duplicatereview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.ui.pagelist.toPageListItemUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 重複候補比較・黒画面候補一覧（docs/design/09-duplicate-review.md /
 * docs/specs/08-page-editing.md §3.2 FR-EDT-006・FR-EDT-007）の ViewModel。
 *
 * 画面単位で不変 [DuplicateReviewUiState] を [StateFlow] で公開する（AGENTS.md ルール8）。
 * 扱うのは**判定済みの結果**だけで、重複・黒画面の判定そのものは行わない
 * （docs/specs/07-image-quality.md §3.2・§3.3 の担当。この画面はその結果を確認・操作する）。
 */
class DuplicateReviewViewModel(
    private val projectId: UUID,
    private val pageRepository: PageRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(DuplicateReviewUiState())
    val uiState: StateFlow<DuplicateReviewUiState> = mutableUiState.asStateFlow()

    /** 組ごとに選んだ「残すページ」。読み直しても選択が消えないように画面が開いている間だけ持つ */
    private val keepSelections = mutableMapOf<UUID, UUID>()

    /**
     * 「残す」を選んだ黒画面候補。今回の確認から外すだけで、ページには手を触れない。
     * 判定結果（qualityState）の書き戻しは検出側の責務なので、この画面からは行わない。
     */
    private val keptBlackPageIds = mutableSetOf<UUID>()

    init {
        load()
    }

    /** 候補を読み直す。失敗時は一覧を消さずにエラー表示だけを出す（docs/specs/08-page-editing.md §6） */
    fun load() {
        mutableUiState.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            runCatching { pageRepository.findByProject(projectId) }
                .onSuccess { pages -> mutableUiState.update { it.withCandidates(pages) } }
                .onFailure {
                    // 例外の内容はログへ出さない（画像パス・書籍情報が混ざりうる。AGENTS.md ルール6）
                    mutableUiState.update { it.copy(loading = false, loadFailed = true) }
                }
        }
    }

    /**
     * 「このページを残す」（docs/design/09-duplicate-review.md「排他選択」）。
     * 選んだ側だけが残り、同じ組の他方は削除候補になる。
     */
    fun onKeepPageSelected(
        groupId: UUID,
        pageId: UUID,
    ) {
        val group = mutableUiState.value.duplicateGroups.firstOrNull { it.groupId == groupId } ?: return
        if (group.pages.none { it.pageId == pageId }) return
        keepSelections[groupId] = pageId
        mutableUiState.update { current ->
            current.copy(
                duplicateGroups =
                    current.duplicateGroups.map { candidate ->
                        if (candidate.groupId == groupId) candidate.copy(keptPageId = pageId) else candidate
                    },
            )
        }
    }

    /** 黒画面候補の「残す」。ページはそのままで、今回の確認一覧から外す */
    fun onBlackPageKept(pageId: UUID) {
        if (mutableUiState.value.blackCandidates.none { it.pageId == pageId }) return
        keptBlackPageIds += pageId
        mutableUiState.update { current ->
            current.copy(blackCandidates = current.blackCandidates.filterNot { it.pageId == pageId })
        }
    }

    /** 黒画面候補の「削除」。すぐには消さず、件数を出す確認を挟む（docs/specs/08-page-editing.md §6） */
    fun onBlackPageDeleteRequested(pageId: UUID) {
        val current = mutableUiState.value
        if (current.deleting || current.blackCandidates.none { it.pageId == pageId }) return
        mutableUiState.update {
            it.copy(deleteConfirmation = DuplicateReviewDeleteConfirmationUiState(pageIds = setOf(pageId)))
        }
    }

    /**
     * 重複の削除候補をまとめて削除する。
     *
     * 削除候補が確定削除になるタイミングは素材に無い（docs/design/09-duplicate-review.md「未定事項」）ので、
     * 同ファイルが推測として挙げるとおり「件数を出す確認 → 実行」にしている。
     */
    fun onDuplicateDeleteRequested() {
        val current = mutableUiState.value
        val targets = current.duplicateDeleteCandidatePageIds
        if (current.deleting || targets.isEmpty()) return
        mutableUiState.update { it.copy(deleteConfirmation = DuplicateReviewDeleteConfirmationUiState(targets)) }
    }

    /** 確認ダイアログのキャンセル。選択は残したままにする */
    fun onDeleteDismissed() {
        mutableUiState.update { it.copy(deleteConfirmation = null) }
    }

    fun onDeleteConfirmed() {
        val current = mutableUiState.value
        val targets = current.deleteConfirmation?.pageIds.orEmpty()
        if (targets.isEmpty()) {
            onDeleteDismissed()
            return
        }
        val resolved = current.keptPagesResolvedBy(targets)
        mutableUiState.update { it.copy(deleteConfirmation = null, deleting = true) }
        viewModelScope.launch {
            val deleted =
                runCatching { pageRepository.deleteResolvingDuplicates(projectId, targets, resolved) }
            runCatching { pageRepository.findByProject(projectId) }
                .onSuccess { pages ->
                    mutableUiState.update { current ->
                        current
                            .withCandidates(pages)
                            .copy(
                                deleting = false,
                                undoableDelete =
                                    if (deleted.isSuccess) {
                                        DuplicateReviewUndoableDelete(pageCount = targets.size)
                                    } else {
                                        null
                                    },
                                operationError =
                                    if (deleted.isSuccess) null else DuplicateReviewOperationError.DELETE,
                            )
                    }
                }.onFailure {
                    mutableUiState.update {
                        it.copy(loading = false, loadFailed = true, deleting = false)
                    }
                }
        }
    }

    /**
     * 直前1操作の取り消し（docs/specs/08-page-editing.md §3.4）。
     * ページ削除の復元はごみ箱ではなくこの取り消しで担保する（同 §7）。
     */
    fun onUndoRequested() {
        if (mutableUiState.value.undoableDelete == null) return
        viewModelScope.launch {
            runCatching { pageRepository.undoLastEdit() }
                .onSuccess { restored ->
                    mutableUiState.update {
                        it.copy(
                            undoableDelete = null,
                            operationError = if (restored) null else DuplicateReviewOperationError.UNDO,
                        )
                    }
                }.onFailure {
                    mutableUiState.update {
                        it.copy(undoableDelete = null, operationError = DuplicateReviewOperationError.UNDO)
                    }
                }
            load()
        }
    }

    /** 取り消し案内・失敗表示を閉じる */
    fun onMessageDismissed() {
        mutableUiState.update { it.copy(undoableDelete = null, operationError = null) }
    }

    /**
     * この削除で重複が解消される「残すページ」。
     *
     * 組の削除候補がすべて [targets] に入っているときだけ、その組の残すページを対象にする。
     * 相手が消えれば残ったページの重複警告は指す先を失うので、削除と同じ1操作で消す
     * （[PageRepository.deleteResolvingDuplicates]）。消さずに置くと、詰め直しで隣に来た別の
     * ページと新しい組を作ってしまい、利用者が残すと選んだページが再び削除候補に現れる。
     */
    private fun DuplicateReviewUiState.keptPagesResolvedBy(targets: Set<UUID>): Set<UUID> =
        duplicateGroups
            .filter { it.deleteCandidatePageIds.isNotEmpty() && targets.containsAll(it.deleteCandidatePageIds) }
            .mapTo(mutableSetOf(), DuplicateGroupUiState::keptPageId)

    /**
     * 読み出したページから候補を組み直す。選択と「残す」で外した分は引き継ぐ。
     *
     * 選択・「残す」の控えは消えた組の分も残す。取り消しで組が戻ったときに、
     * 利用者が選んでいた側をそのまま復元するため（画面を開いているあいだだけの控えで、
     * 大きさは触った組の数に収まる）。
     */
    private fun DuplicateReviewUiState.withCandidates(pages: List<Page>): DuplicateReviewUiState {
        val ordered = pages.sortedBy(Page::sequence)
        val groups = buildDuplicateGroups(ordered, keepSelections)
        val black =
            ordered
                .filter { it.qualityState == PageQualityState.BLACK && it.id !in keptBlackPageIds }
                .map(Page::toPageListItemUiState)
        return copy(
            loading = false,
            loadFailed = false,
            duplicateGroups = groups,
            blackCandidates = black,
        )
    }

    companion object {
        fun factory(
            projectId: UUID,
            pageRepository: PageRepository,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { DuplicateReviewViewModel(projectId, pageRepository) }
            }
    }
}
