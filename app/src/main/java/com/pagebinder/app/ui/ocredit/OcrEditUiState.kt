package com.pagebinder.app.ui.ocredit

import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageOcrState
import java.util.UUID

/** 画面が出す一時的な通知。文言は string リソース側に持ち、ここでは種類だけを扱う */
enum class OcrEditMessage {
    SAVED,
    SAVE_FAILED,
    REVERTED,
    REVERT_FAILED,
    RERUN_QUEUED,
    RERUN_FAILED,
}

/**
 * ページ内検索の状態（docs/specs/09-ocr.md §3.5「ページ内検索」）。
 *
 * 一致位置は表示中の本文（[OcrEditUiState.draftText]）に対する索引で、
 * 本文か検索語が変われば ViewModel が数え直す。展開後の見た目は素材が無い（docs/design/10-ocr-edit.md「未定事項」）ため、
 * アプリバー直下に検索欄・件数・前後移動を置く最小構成にしている。
 */
data class OcrEditSearchUiState(
    val visible: Boolean = false,
    val query: String = "",
    val matches: List<IntRange> = emptyList(),
    val currentIndex: Int = 0,
) {
    val matchCount: Int
        get() = matches.size

    /** いま選ばれている一致。件数0なら null */
    val currentMatch: IntRange?
        get() = matches.getOrNull(currentIndex)

    /** 表示用の「何件目 / 全何件」。1始まり */
    val currentMatchNumber: Int
        get() = if (matches.isEmpty()) 0 else currentIndex + 1

    val noMatch: Boolean
        get() = query.isNotEmpty() && matches.isEmpty()

    val canStep: Boolean
        get() = matches.size > 1
}

/**
 * OCR編集画面の UiState（docs/design/10-ocr-edit.md / docs/specs/09-ocr.md §3.5）。
 *
 * 画面に出す値はすべてここから描く。モックのサンプルデータ（書籍名・本文）は保持しない
 * （docs/design/system/03-principles.md「モック画像の読み方」）。
 *
 * 本文は3つに分けて持つ。
 * - [originalText]: OCRエンジンが出した結果。この画面のどの操作でも書き換えない
 * - [savedEditedText]: 保存済みの手動修正。null なら未修正
 * - [draftText]: 編集中の本文。保存で [savedEditedText] になり、「元へ戻す」で [originalText] に戻る
 */
data class OcrEditUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val pageId: UUID? = null,
    /** アプリバーのタイトルに出すページ番号。読み込み前は null */
    val pageSequence: Int? = null,
    val rotation: Int = 0,
    val crop: PageCrop = PageCrop(),
    val ocrState: PageOcrState = PageOcrState.PENDING,
    /** OCR結果の行があるか。未実行・失敗のページでは false で、本文は編集できない */
    val resultAvailable: Boolean = false,
    val originalText: String = "",
    val savedEditedText: String? = null,
    val draftText: String = "",
    val saving: Boolean = false,
    val message: OcrEditMessage? = null,
    val revertDialogVisible: Boolean = false,
    val search: OcrEditSearchUiState = OcrEditSearchUiState(),
    val zoomPercent: Int = DEFAULT_ZOOM_PERCENT,
    /** 上ペイン（画像）が占める割合。分割ハンドルのドラッグで動く */
    val splitRatio: Float = DEFAULT_SPLIT_RATIO,
) {
    /** 手動修正が保存済みか（モックの「修正済み」pill の出る条件） */
    val edited: Boolean
        get() = savedEditedText != null

    /** 未保存の編集があるか */
    val unsavedChanges: Boolean
        get() = resultAvailable && draftText != (savedEditedText ?: originalText)

    val canSave: Boolean
        get() = !saving && unsavedChanges

    /** 保存済みの修正か未保存の編集があるときだけ「元のOCR結果へ戻す」を有効にする */
    val canRevert: Boolean
        get() = !saving && resultAvailable && (edited || unsavedChanges)

    /**
     * 再実行を受け付けられるか。待機中・実行中のページは既にキューに載っているので押させない
     * （docs/specs/09-ocr.md §3.2 の状態遷移）。
     */
    val canRerun: Boolean
        get() = pageId != null && ocrState !in setOf(PageOcrState.PENDING, PageOcrState.RUNNING)

    /** 画像ペインの表示倍率（1.0 = 原寸相当） */
    val zoomScale: Float
        get() = zoomPercent / PERCENT_BASE

    val canZoomIn: Boolean
        get() = zoomPercent < MAX_ZOOM_PERCENT

    val canZoomOut: Boolean
        get() = zoomPercent > MIN_ZOOM_PERCENT
}

const val DEFAULT_ZOOM_PERCENT = 100
const val MIN_ZOOM_PERCENT = 50
const val MAX_ZOOM_PERCENT = 300
const val ZOOM_STEP_PERCENT = 25

const val DEFAULT_SPLIT_RATIO = 0.5f
const val MIN_SPLIT_RATIO = 0.25f
const val MAX_SPLIT_RATIO = 0.75f

private const val PERCENT_BASE = 100f
