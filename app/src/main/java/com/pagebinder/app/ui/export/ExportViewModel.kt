package com.pagebinder.app.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pagebinder.app.domain.ExportStarter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 書き出し画面（docs/design/11-export.md）の ViewModel。
 *
 * 本タスク（pagebinder-r3j.3）では権限確認ゲートだけを持つ最小構成。
 * 出力形式選択・ファイル名・ページ範囲・SAF起動・進捗表示は pagebinder-gph.6 でここへ足す。
 *
 * 書き出し開始の入口は [onStartExportRequested] ただ一つで、
 * 「この成果物を利用する権限を確認しました」が未チェックの間は [ExportStarter] を呼ばない
 * （docs/specs/12-legal-guardrails.md §3.2）。
 */
class ExportViewModel(
    private val exportStarter: ExportStarter,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ExportUiState())
    val uiState: StateFlow<ExportUiState> = mutableUiState.asStateFlow()

    /** 権限確認チェックの切り替え（[ExportConsentCard] からのコールバック） */
    fun onPermissionConfirmedChange(confirmed: Boolean) {
        mutableUiState.update { it.copy(consent = it.consent.withPermissionConfirmed(confirmed)) }
    }

    /**
     * 書き出し開始の要求。書き出しを開始する唯一の入口。
     * 権限確認が未チェックなら [ExportStarter] を呼ばず、確認を促す案内だけを出す。
     */
    fun onStartExportRequested() {
        if (!mutableUiState.value.consent.canStartExport) {
            mutableUiState.update { it.copy(consent = it.consent.withConfirmationRequired()) }
            return
        }
        viewModelScope.launch { exportStarter.startExport() }
    }

    companion object {
        fun factory(exportStarter: ExportStarter): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ExportViewModel(exportStarter) }
            }
    }
}
