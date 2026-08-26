package com.pagebinder.app.ui.consent

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pagebinder.app.R
import com.pagebinder.app.legal.ConsentTerm
import com.pagebinder.app.ui.theme.ButtonCornerRadius
import com.pagebinder.app.ui.theme.CardCornerRadius
import com.pagebinder.app.ui.theme.ColorPrimary
import com.pagebinder.app.ui.theme.ColorPrimaryDark
import com.pagebinder.app.ui.theme.ColorTextSecondary
import com.pagebinder.app.ui.theme.DISABLED_ALPHA
import com.pagebinder.app.ui.theme.MinTouchTarget
import com.pagebinder.app.ui.theme.ScreenHorizontalMargin
import com.pagebinder.app.ui.theme.SpaceUnit

/**
 * 初回同意画面（docs/design/12-consent.md）。
 * 表示する4項目は [ConsentUiState.wording] から描く（ハードコードした一覧を持たない）。
 */
@Composable
fun ConsentScreen(
    uiState: ConsentUiState,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = ScreenHorizontalMargin),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(modifier = Modifier.height(SpaceUnit * 4))
                Image(
                    painter = painterResource(R.drawable.pagebinder_logo),
                    contentDescription = stringResource(R.string.consent_logo_description),
                    modifier = Modifier.size(SpaceUnit * 12),
                )
                Spacer(modifier = Modifier.height(SpaceUnit * 2))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(modifier = Modifier.height(SpaceUnit))
                Text(
                    text = stringResource(R.string.consent_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = ColorTextSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(SpaceUnit * 3))
                ConsentTermsCard(terms = uiState.wording.terms)
                Spacer(modifier = Modifier.height(SpaceUnit * 3))
            }
            ConsentActions(
                uiState = uiState,
                onAgree = onAgree,
                onDecline = onDecline,
            )
        }
    }
}

@Composable
private fun ConsentTermsCard(
    terms: List<ConsentTerm>,
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
                text = stringResource(R.string.consent_card_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            terms.forEachIndexed { index, term ->
                if (index > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                }
                ConsentTermRow(term = term)
            }
        }
    }
}

@Composable
private fun ConsentTermRow(
    term: ConsentTerm,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = SpaceUnit * 1.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_consent_shield),
            contentDescription = null,
            tint = ColorPrimary,
            modifier = Modifier.size(SpaceUnit * 3),
        )
        Spacer(modifier = Modifier.size(ScreenHorizontalMargin))
        Text(
            text = stringResource(term.textRes()),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ConsentActions(
    uiState: ConsentUiState,
    onAgree: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalMargin)
                .padding(bottom = SpaceUnit * 2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpaceUnit),
    ) {
        if (uiState.declineNoticeVisible) {
            ConsentNotice(
                textRes = R.string.consent_decline_notice,
                color = ColorTextSecondary,
            )
        }
        if (uiState.saveFailed) {
            ConsentNotice(
                textRes = R.string.consent_save_failed,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // Primary ボタン（docs/design/system/02-components.md）:
        // default = --color-primary / pressed = --color-primary-dark / disabled = 38%不透明
        val agreeInteractionSource = remember { MutableInteractionSource() }
        val agreePressed by agreeInteractionSource.collectIsPressedAsState()
        Button(
            onClick = onAgree,
            enabled = !uiState.saving,
            shape = RoundedCornerShape(ButtonCornerRadius),
            interactionSource = agreeInteractionSource,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = if (agreePressed) ColorPrimaryDark else ColorPrimary,
                    contentColor = Color.White,
                    disabledContainerColor = ColorPrimary.copy(alpha = DISABLED_ALPHA),
                    disabledContentColor = Color.White.copy(alpha = DISABLED_ALPHA),
                ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MinTouchTarget),
        ) {
            Text(
                text = stringResource(R.string.consent_agree),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        TextButton(
            onClick = onDecline,
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) {
            Text(
                text = stringResource(R.string.consent_decline),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ConsentNotice(
    @StringRes textRes: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(textRes),
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}

@StringRes
private fun ConsentTerm.textRes(): Int =
    when (this) {
        ConsentTerm.LAWFUL_CONTENT -> R.string.consent_term_lawful_content
        ConsentTerm.USER_VERIFIES_REUSE -> R.string.consent_term_user_verifies_reuse
        ConsentTerm.NO_PROTECTION_BYPASS -> R.string.consent_term_no_protection_bypass
        ConsentTerm.NO_UNAUTHORIZED_DISTRIBUTION -> R.string.consent_term_no_unauthorized_distribution
    }
