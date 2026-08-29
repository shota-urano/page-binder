package com.pagebinder.app.ui.duplicatereview

import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.ui.pagelist.PageListItemUiState
import com.pagebinder.app.ui.pagelist.toPageListItemUiState
import java.util.UUID

/**
 * 重複候補ひと組（docs/design/09-duplicate-review.md「比較ペア」）。
 *
 * 判定そのものは行わない。保存済みの [PageQualityState.DUPLICATE] を並べ替えて見せるだけで、
 * ハッシュ距離の計算・閾値は検出側（docs/specs/07-image-quality.md §3.3）の責務。
 *
 * @param pages 比較対象。sequence 昇順で必ず2件以上
 * @param keptPageId 残すページ。組の中の1件だけを指す（**排他選択**）
 */
data class DuplicateGroupUiState(
    val pages: List<PageListItemUiState>,
    val keptPageId: UUID,
) {
    init {
        require(pages.size >= 2) { "A duplicate group must hold at least two pages to compare" }
        require(pages.any { it.pageId == keptPageId }) { "The kept page must belong to the group" }
    }

    /** 組の識別子。先頭ページの id をそのまま使う（組は先頭ページで一意に決まる） */
    val groupId: UUID
        get() = pages.first().pageId

    /**
     * 残さなかったページ＝削除候補
     * （docs/design/09-duplicate-review.md「選ばなかったページは削除候補になります」）。
     */
    val deleteCandidatePageIds: Set<UUID>
        get() = pages.map(PageListItemUiState::pageId).toSet() - keptPageId

    fun isKept(pageId: UUID): Boolean = pageId == keptPageId
}

/**
 * 削除確認ダイアログの表示状態。
 *
 * 件数は必ず持つ（docs/specs/08-page-editing.md §6「削除確認で件数を必ず表示」、
 * docs/design/system/02-components.md「破壊操作の確認は対象情報（件数・容量）を必ず本文に含める」）。
 */
data class DuplicateReviewDeleteConfirmationUiState(
    val pageIds: Set<UUID>,
) {
    val pageCount: Int
        get() = pageIds.size
}

/** 取り消せる直前の1操作（docs/specs/08-page-editing.md §3.4）。この画面が行う編集は削除だけ */
data class DuplicateReviewUndoableDelete(
    val pageCount: Int,
)

/** 操作の失敗（docs/specs/08-page-editing.md §6）。失敗時は読み直して状態を戻し、これを併せて出す */
enum class DuplicateReviewOperationError {
    DELETE,
    UNDO,
}

/**
 * 重複候補比較・黒画面候補一覧の UiState
 * （docs/design/09-duplicate-review.md / docs/specs/08-page-editing.md §3.2 FR-EDT-006・FR-EDT-007）。
 *
 * 画面に出す値はすべてここから描く。モックのサンプルデータ（ページ番号・本文）は保持しない
 * （docs/design/system/03-principles.md「モック画像の読み方」）。
 */
data class DuplicateReviewUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val duplicateGroups: List<DuplicateGroupUiState> = emptyList(),
    val blackCandidates: List<PageListItemUiState> = emptyList(),
    /** 表示中なら削除確認ダイアログを出す。null は非表示 */
    val deleteConfirmation: DuplicateReviewDeleteConfirmationUiState? = null,
    /** 削除の実行中。二重の削除要求を受け付けない */
    val deleting: Boolean = false,
    val undoableDelete: DuplicateReviewUndoableDelete? = null,
    val operationError: DuplicateReviewOperationError? = null,
) {
    /** 見出しに出す組数（docs/design/09-duplicate-review.md「重複の候補 (1組)」の組数は動的） */
    val duplicateGroupCount: Int
        get() = duplicateGroups.size

    /** 見出しに出す件数（同「黒画面の候補 (2件)」） */
    val blackCandidateCount: Int
        get() = blackCandidates.size

    /** 全組をまとめた削除候補。まとめて削除するときの対象になる */
    val duplicateDeleteCandidatePageIds: Set<UUID>
        get() = duplicateGroups.flatMapTo(mutableSetOf(), DuplicateGroupUiState::deleteCandidatePageIds)

    val hasCandidates: Boolean
        get() = duplicateGroups.isNotEmpty() || blackCandidates.isNotEmpty()

    /**
     * 確認するものが1件も無い。
     * 素材の無い状態（docs/design/09-duplicate-review.md「未定事項: 候補0件時の表示」）なので、
     * 画面を勝手に閉じずに案内だけを出す。
     */
    val empty: Boolean
        get() = !loading && !loadFailed && !hasCandidates
}

/**
 * 保存済みの判定結果から重複の組を作る。**検出はしない**。
 *
 * 重複は「直前保存ページとのハッシュ距離が閾値以下」のときに後ろのページへ付く
 * （docs/specs/07-image-quality.md §3.3）ので、連続する [PageQualityState.DUPLICATE] と
 * その直前の1ページをひと組として並べる。素材は2枚ペアだけだが（docs/design/09-duplicate-review.md
 * 「未定事項: 3枚以上の重複組の表示」）、連続して重複が付いた場合も同じ組に入れて比較できるようにしてある。
 *
 * @param pages sequence 昇順のページ全件
 * @param keepSelections 利用者が選んだ「残すページ」（組の識別子 → ページ）。
 *   選んでいない組は、重複の印が付いていない先頭ページを既定で残す。
 */
fun buildDuplicateGroups(
    pages: List<Page>,
    keepSelections: Map<UUID, UUID> = emptyMap(),
): List<DuplicateGroupUiState> {
    val ordered = pages.sortedBy(Page::sequence)
    val groups = mutableListOf<DuplicateGroupUiState>()
    var index = 0
    while (index < ordered.size) {
        if (ordered[index].qualityState != PageQualityState.DUPLICATE) {
            index++
            continue
        }
        // 直前のページが比較相手。先頭ページに重複が付いていたら相手がいないので組にしない
        val start = index - 1
        var end = index
        while (end + 1 < ordered.size && ordered[end + 1].qualityState == PageQualityState.DUPLICATE) {
            end++
        }
        if (start >= 0) {
            val members = ordered.subList(start, end + 1).map(Page::toPageListItemUiState)
            val groupId = members.first().pageId
            val memberIds = members.map(PageListItemUiState::pageId).toSet()
            groups +=
                DuplicateGroupUiState(
                    pages = members,
                    keptPageId = keepSelections[groupId]?.takeIf { it in memberIds } ?: groupId,
                )
        }
        index = end + 1
    }
    return groups
}
