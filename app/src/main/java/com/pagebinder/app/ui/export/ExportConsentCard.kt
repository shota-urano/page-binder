package com.pagebinder.app.ui.export

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.pagebinder.app.R
import com.pagebinder.app.ui.theme.CardCornerRadius
import com.pagebinder.app.ui.theme.ColorPrimary
import com.pagebinder.app.ui.theme.ColorTextSecondary
import com.pagebinder.app.ui.theme.ColorWarning
import com.pagebinder.app.ui.theme.MinTouchTarget
import com.pagebinder.app.ui.theme.ScreenHorizontalMargin
import com.pagebinder.app.ui.theme.SpaceUnit

/**
 * 書き出し時の「利用上の注意」カード（docs/design/11-export.md / docs/specs/12-legal-guardrails.md §3.2）。
 *
 * 再利用部品なので ViewModel を持たない（AGENTS.md §8）。状態は [uiState] で受け、
 * チェックの変更は [onPermissionConfirmedChange] で呼び出し側（書き出し画面の ViewModel）へ返す。
 * 書き出しを開始してよいかの判定は [ExportConsentUiState.canStartExport] / [ExportConsentGate] 側にある。
 *
 * 表示文言は文字列リソースが持つ。権限確認の文言は specs 12 §4 の確定文言であり言い換えない。
 */
@Composable
fun ExportConsentCard(
    uiState: ExportConsentUiState,
    onPermissionConfirmedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(ScreenHorizontalMargin)) {
            Text(
                text = stringResource(R.string.export_consent_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(SpaceUnit))
            Text(
                text = stringResource(R.string.export_consent_description),
                style = MaterialTheme.typography.bodyMedium,
                color = ColorTextSecondary,
            )
            Spacer(modifier = Modifier.height(SpaceUnit))
            ExportConsentCheckRow(
                confirmed = uiState.permissionConfirmed,
                onConfirmedChange = onPermissionConfirmedChange,
            )
            if (uiState.confirmationRequiredVisible) {
                Text(
                    text = stringResource(R.string.export_consent_required),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ColorWarning,
                    modifier = Modifier.padding(top = SpaceUnit),
                )
            }
        }
    }
}

@Composable
private fun ExportConsentCheckRow(
    confirmed: Boolean,
    onConfirmedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = MinTouchTarget)
                .toggleable(
                    value = confirmed,
                    role = Role.Checkbox,
                    onValueChange = onConfirmedChange,
                ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = confirmed,
            // 行全体を toggleable にしているので、チェックボックス自体はクリック対象にしない
            onCheckedChange = null,
            colors =
                CheckboxDefaults.colors(
                    checkedColor = ColorPrimary,
                    checkmarkColor = Color.White,
                    // 未チェックの枠は白地でのコントラストを確保するため補助テキスト色を使う
                    // （--color-divider は白地で 3:1 に届かない — docs/design/system/03-principles.md）
                    uncheckedColor = ColorTextSecondary,
                ),
        )
        Spacer(modifier = Modifier.width(ScreenHorizontalMargin))
        Text(
            text = stringResource(R.string.export_consent_confirm),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
