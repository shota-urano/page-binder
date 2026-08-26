package com.pagebinder.app.ui.consent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pagebinder.app.domain.ConsentRecord
import com.pagebinder.app.domain.ConsentRepository
import com.pagebinder.app.legal.CURRENT_CONSENT_WORDING
import com.pagebinder.app.legal.ConsentWording
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CancellationException

/**
 * 初回同意画面の ViewModel 兼ナビゲーションガード。
 * 同意履歴が読めるまで、および未同意の間は [ConsentUiState.canEnterMainFeatures] を false のままにする。
 */
class ConsentViewModel(
    private val consentRepository: ConsentRepository,
    private val wording: ConsentWording = CURRENT_CONSENT_WORDING,
    private val clock: Clock = Clock.systemUTC(),
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(ConsentUiState(wording = wording))
    val uiState: StateFlow<ConsentUiState> = mutableUiState.asStateFlow()

    init {
        viewModelScope.launch { restoreConsent() }
    }

    /** 「同意して始める」。保存できた場合にだけ主要機能を解放する */
    fun onAgree() {
        if (mutableUiState.value.saving) return
        mutableUiState.update { it.copy(saving = true, saveFailed = false, declineNoticeVisible = false) }
        viewModelScope.launch {
            val saved =
                try {
                    consentRepository.saveConsent(
                        ConsentRecord(
                            consentedAt = Instant.now(clock),
                            wordingVersion = wording.version,
                        ),
                    )
                    true
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    false
                }
            mutableUiState.update {
                if (saved) {
                    it.copy(gate = ConsentGate.Unlocked, saving = false, saveFailed = false)
                } else {
                    it.copy(gate = ConsentGate.ConsentRequired, saving = false, saveFailed = true)
                }
            }
        }
    }

    /**
     * 「同意しない」。specs §3.1 のとおり主要機能へは進めない。
     * 同意画面に留まり、進めない旨だけを表示する（画面表現は spec 未定 — docs/design/12-consent.md「未定事項」）。
     */
    fun onDecline() {
        mutableUiState.update {
            it.copy(gate = ConsentGate.ConsentRequired, declineNoticeVisible = true, saveFailed = false)
        }
    }

    private suspend fun restoreConsent() {
        // getConsent() は読み込み失敗時も null を返す（安全側 = 未同意扱い。specs §6）
        val record = consentRepository.getConsent()
        mutableUiState.update {
            it.copy(gate = if (record == null) ConsentGate.ConsentRequired else ConsentGate.Unlocked)
        }
    }

    companion object {
        fun factory(consentRepository: ConsentRepository): ViewModelProvider.Factory =
            viewModelFactory {
                initializer { ConsentViewModel(consentRepository) }
            }
    }
}
