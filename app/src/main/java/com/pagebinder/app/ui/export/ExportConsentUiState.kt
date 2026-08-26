package com.pagebinder.app.ui.export

/**
 * 書き出し時の権限確認の状態（docs/specs/12-legal-guardrails.md §3.2 / docs/design/11-export.md）。
 *
 * 書き出し画面の UiState がこの値を保持し、[canStartExport] が true のときだけ書き出しを開始してよい。
 * 再利用 UI 部品（[ExportConsentCard]）はこの不変値を引数で受け取るだけで、自前の状態を持たない。
 */
data class ExportConsentUiState(
    /** 「この成果物を利用する権限を確認しました」のチェック状態 */
    val permissionConfirmed: Boolean = false,
    /**
     * 未確認のまま書き出しを要求したときの案内の表示可否。
     * 未チェック時の表現は docs/design/11-export.md「未定事項」のため暫定（テキスト案内のみ）。
     */
    val confirmationRequiredVisible: Boolean = false,
) {
    /** 書き出しを開始してよいか。権限確認のチェックが無い限り false（specs 12 §3.2） */
    val canStartExport: Boolean
        get() = permissionConfirmed
}
