package com.pagebinder.app.ui.pageedit

import com.pagebinder.app.domain.PageCrop
import java.util.UUID

/**
 * 画面が出す通知。文言は string リソース側に持ち、ここでは種類と差し込む値だけを扱う。
 */
sealed interface PageEditMessage {
    /** このページだけへ保存した */
    data object Saved : PageEditMessage

    /** 同一書籍の全ページへ切り取りを適用した（FR-IMG-005/006） */
    data class SavedToAllPages(
        val pageCount: Int,
    ) : PageEditMessage

    data object SaveFailed : PageEditMessage

    /** 保存済みの直前1操作を取り消した（docs/specs/08-page-editing.md §3.4） */
    data object EditUndone : PageEditMessage

    data object UndoFailed : PageEditMessage

    /** 失敗の通知か（画面が色とアイコンを選ぶのに使う） */
    val isFailure: Boolean
        get() = this is SaveFailed || this is UndoFailed
}

/**
 * 切り取り枠のつまみ（docs/design/08-page-edit.md「crop 枠: --color-accent の線 + 白丸ハンドル8個」）。
 *
 * どの辺を動かすかだけを持つ。座標の計算は [movedBy] にまとめてあり、画面側は
 * 「どのつまみを、正規化座標でどれだけ動かしたか」だけを ViewModel へ渡す。
 */
enum class PageCropHandle(
    val movesLeft: Boolean,
    val movesTop: Boolean,
    val movesRight: Boolean,
    val movesBottom: Boolean,
) {
    TOP_LEFT(movesLeft = true, movesTop = true, movesRight = false, movesBottom = false),
    TOP(movesLeft = false, movesTop = true, movesRight = false, movesBottom = false),
    TOP_RIGHT(movesLeft = false, movesTop = true, movesRight = true, movesBottom = false),
    LEFT(movesLeft = true, movesTop = false, movesRight = false, movesBottom = false),
    RIGHT(movesLeft = false, movesTop = false, movesRight = true, movesBottom = false),
    BOTTOM_LEFT(movesLeft = true, movesTop = false, movesRight = false, movesBottom = true),
    BOTTOM(movesLeft = false, movesTop = false, movesRight = false, movesBottom = true),
    BOTTOM_RIGHT(movesLeft = false, movesTop = false, movesRight = true, movesBottom = true),
}

/** 切り取り範囲の最小の辺の長さ（正規化）。つまみ同士が重なって操作できなくなるのを防ぐ */
const val MIN_CROP_SIZE = 0.1f

/**
 * [handle] を正規化座標で [dx] / [dy] だけ動かした切り取り範囲。
 *
 * 結果は必ず 0〜1 に収まり、辺の長さも [MIN_CROP_SIZE] を下回らない（[PageCrop] の require を満たす）。
 * 画面のピクセルは ViewModel へ渡す前に正規化されるので、端末の解像度や表示倍率は保存値に影響しない。
 */
fun PageCrop.movedBy(
    handle: PageCropHandle,
    dx: Float,
    dy: Float,
): PageCrop {
    if (!dx.isFinite() || !dy.isFinite()) return this
    val newLeft = if (handle.movesLeft) (left + dx).coerceIn(0f, right - MIN_CROP_SIZE) else left
    val newRight = if (handle.movesRight) (right + dx).coerceIn(left + MIN_CROP_SIZE, 1f) else right
    val newTop = if (handle.movesTop) (top + dy).coerceIn(0f, bottom - MIN_CROP_SIZE) else top
    val newBottom = if (handle.movesBottom) (bottom + dy).coerceIn(top + MIN_CROP_SIZE, 1f) else bottom
    return PageCrop(left = newLeft, top = newTop, right = newRight, bottom = newBottom)
}

/**
 * 画像を時計回りに90度回した後の座標系へ切り取り範囲を移す。
 *
 * crop は「回転後の画像に対する正規化座標」（[PageCrop] の定義）なので、回転すると同じ範囲でも
 * 数値が変わる。回した後も利用者が選んだ紙面の場所が動かないように、ここで座標を移し替える。
 * 時計回り90度では、回転前の点 (x, y) が回転後の (1 - y, x) へ来る。
 */
fun PageCrop.rotatedClockwise(): PageCrop =
    PageCrop(
        left = 1f - bottom,
        top = left,
        right = 1f - top,
        bottom = right,
    )

/**
 * 回転・切り取り編集画面の UiState（docs/design/08-page-edit.md / docs/specs/08-page-editing.md §3.2）。
 *
 * 画面に出す値はすべてここから描く。モックのサンプルデータ（ページ番号12・書籍の紙面）は保持しない
 * （docs/design/system/03-principles.md「モック画像の読み方」）。
 *
 * 編集中の値（[rotation] / [crop]）と保存済みの値（[savedRotation] / [savedCrop]）を分けて持つ。
 * 元画像には触れず、保存するのはこの属性だけ（FR-IMG-007 の非破壊編集）。
 */
data class PageEditUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val pageId: UUID? = null,
    /** アプリバーのタイトルに出すページ番号。読み込み前は null */
    val pageSequence: Int? = null,
    /** 一括適用の対象になる同一書籍のページ数（確認ダイアログに出す） */
    val projectPageCount: Int = 0,
    val savedRotation: Int = 0,
    val savedCrop: PageCrop = PageCrop(),
    /** 編集中の回転角。0/90/180/270 のみ（FR-EDT-004） */
    val rotation: Int = 0,
    /** 編集中の切り取り範囲。0〜1 の正規化座標（FR-EDT-005） */
    val crop: PageCrop = PageCrop(),
    /** 「この書籍の全ページに同じ切り取りを適用」（FR-IMG-005/006） */
    val applyCropToAllPages: Boolean = false,
    /** 直前1操作を取り消せるか（docs/specs/08-page-editing.md §3.4。履歴の深さは1で確定） */
    val undoAvailable: Boolean = false,
    val saving: Boolean = false,
    /** 一括適用つきの保存を確認中 */
    val bulkConfirmationVisible: Boolean = false,
    /** 未保存の編集を破棄して閉じてよいか確認中 */
    val discardConfirmationVisible: Boolean = false,
    val message: PageEditMessage? = null,
) {
    /** 保存していない回転・切り取りの変更があるか */
    val unsavedChanges: Boolean
        get() = pageId != null && (rotation != savedRotation || crop != savedCrop)

    /**
     * 保存できるか。未保存の変更が無くても一括適用にチェックがあれば保存できる
     * （このページの既存の切り取りを書籍全体へ広げる操作になるため）。
     */
    val canSave: Boolean
        get() = pageId != null && !saving && (unsavedChanges || applyCropToAllPages)

    val canEdit: Boolean
        get() = pageId != null && !saving

    val canUndo: Boolean
        get() = canEdit && undoAvailable
}
