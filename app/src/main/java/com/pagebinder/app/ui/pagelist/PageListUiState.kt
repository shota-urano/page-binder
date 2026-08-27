package com.pagebinder.app.ui.pagelist

import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import java.util.UUID

/** 一覧の並べ方（docs/design/07-page-list.md「表示切替トグル（グリッド | リスト）」） */
enum class PageListViewMode {
    GRID,
    LIST,
}

/**
 * 一覧の絞り込み（docs/specs/08-page-editing.md §3.1 の警告表示に対応）。
 *
 * モックのツールバー右にある「ページ順 ▼」は docs/design/07-page-list.md が
 * **推測・要確認**と明記した要素で、spec §3.1 の表示順は sequence 固定・並べ替えはドラッグのみのため
 * 並び順の選択肢が存在しない。同じ位置・同じ様式のまま、実装単位の受け入れ基準にある
 * 「警告フィルタ」を割り当てている。
 */
enum class PageListFilter {
    ALL,
    DUPLICATE,
    BLACK,
    OCR_INCOMPLETE,
}

/**
 * 一覧セル1件の表示状態。
 *
 * 画像そのものは持たず、サムネイル生成に必要な非破壊属性（rotation / crop）だけを渡す
 * （docs/specs/07-image-quality.md §3.4。派生画像は表示時に都度生成する）。
 */
data class PageListItemUiState(
    val pageId: UUID,
    val sequence: Int,
    val rotation: Int,
    val crop: PageCrop,
    val ocrState: PageOcrState,
    val qualityState: PageQualityState,
) {
    /** 重複・黒画面・画像エラーのいずれかの警告を持つか */
    val hasQualityWarning: Boolean
        get() = qualityState != PageQualityState.NORMAL

    /** OCR が未完了（待機・実行中・失敗・再実行が必要）か */
    val ocrIncomplete: Boolean
        get() = ocrState != PageOcrState.SUCCEEDED
}

/**
 * ページ一覧画面の UiState（docs/design/07-page-list.md / docs/specs/08-page-editing.md §3.1）。
 *
 * 画面に出す値はすべてここから描く。モックのサンプルデータ（書籍名・本文）は保持しない
 * （docs/design/system/03-principles.md「モック画像の読み方」）。
 */
data class PageListUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val viewMode: PageListViewMode = PageListViewMode.GRID,
    val filter: PageListFilter = PageListFilter.ALL,
    /** sequence 昇順のページ全件 */
    val pages: List<PageListItemUiState> = emptyList(),
    val selectedPageIds: Set<UUID> = emptySet(),
) {
    /** 選択モード中か。1件でも選ばれていれば選択モードとし、選択モードバーへ切り替える */
    val selectionMode: Boolean
        get() = selectedPageIds.isNotEmpty()

    /** 選択モードバーに出す件数（docs/design/07-page-list.md「2件を選択中」の件数は動的） */
    val selectedCount: Int
        get() = selectedPageIds.size

    /** 絞り込み適用後の表示対象 */
    val visiblePages: List<PageListItemUiState>
        get() =
            when (filter) {
                PageListFilter.ALL -> pages
                PageListFilter.DUPLICATE -> pages.filter { it.qualityState == PageQualityState.DUPLICATE }
                PageListFilter.BLACK -> pages.filter { it.qualityState == PageQualityState.BLACK }
                PageListFilter.OCR_INCOMPLETE -> pages.filter(PageListItemUiState::ocrIncomplete)
            }

    /** ページが1件も無い（絞り込みではなく書籍自体が空） */
    val emptyProject: Boolean
        get() = !loading && !loadFailed && pages.isEmpty()

    /** 絞り込みの結果として0件になった */
    val emptyByFilter: Boolean
        get() = !loading && !loadFailed && pages.isNotEmpty() && visiblePages.isEmpty()

    fun isSelected(pageId: UUID): Boolean = pageId in selectedPageIds
}

/** Domain の [Page] を一覧セルの表示状態へ落とす。Room の型は Domain 側で閉じている */
fun Page.toPageListItemUiState(): PageListItemUiState =
    PageListItemUiState(
        pageId = id,
        sequence = sequence,
        rotation = rotation,
        crop = crop,
        ocrState = ocrState,
        qualityState = qualityState,
    )
