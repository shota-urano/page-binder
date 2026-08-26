package com.pagebinder.app.ui.consent

import com.pagebinder.app.legal.CURRENT_CONSENT_WORDING
import com.pagebinder.app.legal.ConsentWording

/**
 * 同意ゲートの状態。[Unlocked] のときだけホーム以降の主要機能を表示してよい
 * （docs/specs/12-legal-guardrails.md §3.1 のナビゲーションガード）。
 */
enum class ConsentGate {
    /** 同意履歴の読み込み中。まだどちらとも判定していない */
    Checking,

    /** 未同意（読み込み失敗も安全側に倒して未同意扱い — specs §6）。同意画面を表示する */
    ConsentRequired,

    /** 同意済み。主要機能へ進める */
    Unlocked,
}

data class ConsentUiState(
    val gate: ConsentGate = ConsentGate.Checking,
    val wording: ConsentWording = CURRENT_CONSENT_WORDING,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val declineNoticeVisible: Boolean = false,
) {
    /** 主要機能へ進んでよいか。UI はこの値だけを見てホームを出す */
    val canEnterMainFeatures: Boolean
        get() = gate == ConsentGate.Unlocked
}
