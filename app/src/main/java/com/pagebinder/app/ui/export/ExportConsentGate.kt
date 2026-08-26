package com.pagebinder.app.ui.export

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 書き出し開始をブロックするゲート（docs/specs/12-legal-guardrails.md §3.2）。
 *
 * 「この成果物を利用する権限を確認しました」にチェックが無い限り、書き出し処理を開始させない。
 * 画面単位の ViewModel（書き出し画面の `ExportViewModel`）がこれを保持して使う想定の状態ホルダで、
 * 再利用 UI 部品側には状態を持たせない（AGENTS.md §8）。
 * Framework 型に依存しないため JVM 単体テストで検証できる。
 */
class ExportConsentGate(
    initialState: ExportConsentUiState = ExportConsentUiState(),
) {
    private val mutableUiState = MutableStateFlow(initialState)
    val uiState: StateFlow<ExportConsentUiState> = mutableUiState.asStateFlow()

    /** 権限確認チェックの切り替え。チェックが付いた時点で未確認の案内は消す */
    fun onPermissionConfirmedChange(confirmed: Boolean) {
        mutableUiState.update {
            it.copy(
                permissionConfirmed = confirmed,
                confirmationRequiredVisible = if (confirmed) false else it.confirmationRequiredVisible,
            )
        }
    }

    /**
     * 書き出し開始の要求。権限確認が未チェックなら [startExport] を呼ばず、案内を表示して false を返す。
     * チェック済みのときだけ [startExport] を呼び、true を返す。
     */
    fun requestExport(startExport: () -> Unit): Boolean {
        if (!mutableUiState.value.canStartExport) {
            mutableUiState.update { it.copy(confirmationRequiredVisible = true) }
            return false
        }
        startExport()
        return true
    }
}
