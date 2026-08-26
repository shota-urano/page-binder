package com.pagebinder.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.pagebinder.app.R
import com.pagebinder.app.ui.consent.ConsentGate
import com.pagebinder.app.ui.consent.ConsentScreen
import com.pagebinder.app.ui.consent.ConsentUiState

/**
 * 同意ゲート付きのアプリ本体。
 * [ConsentUiState.canEnterMainFeatures] が true になるまで主要機能（ホーム以降）を構成しない
 * — これが docs/specs/12-legal-guardrails.md §3.1 のナビゲーションガード。
 *
 * OS が描くステータスバー・ナビゲーションバーは自前で描かず、safeDrawing のインセットだけを避ける。
 */
@Composable
fun PageBinderApp(
    uiState: ConsentUiState,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().safeDrawingPadding()) {
        when (uiState.gate) {
            ConsentGate.Checking -> ConsentGateLoading()
            ConsentGate.ConsentRequired ->
                ConsentScreen(
                    uiState = uiState,
                    onAgree = onAgree,
                    onDecline = onDecline,
                )
            ConsentGate.Unlocked -> HomePlaceholderScreen()
        }
    }
}

/** 同意履歴の読み込み中。判定が出るまで何も見せない（未同意側にも同意側にも倒さない） */
@Composable
private fun ConsentGateLoading(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
}

/** ホーム画面（01-home）の実装までのプレースホルダ。ゲート通過後にだけ表示される */
@Composable
private fun HomePlaceholderScreen(modifier: Modifier = Modifier) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
