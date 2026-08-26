package com.pagebinder.app.ui.export

/**
 * 書き出し画面の UiState（docs/design/11-export.md）。
 *
 * 本タスク（pagebinder-r3j.3）では権限確認の部分だけを持つ。
 * 出力形式・ファイル名・ページ範囲・PDF画質・OCR未完了警告・進捗は pagebinder-gph.6 でここへ足す。
 */
data class ExportUiState(
    val consent: ExportConsentUiState = ExportConsentUiState(),
)
