package com.pagebinder.app.ui.captureprep

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pagebinder.app.R
import com.pagebinder.app.domain.AutoCaptureSensitivity
import com.pagebinder.app.ui.theme.ButtonCornerRadius
import com.pagebinder.app.ui.theme.CardCornerRadius
import com.pagebinder.app.ui.theme.ColorPrimary
import com.pagebinder.app.ui.theme.ColorSuccess
import com.pagebinder.app.ui.theme.ColorTextSecondary
import com.pagebinder.app.ui.theme.ColorWarning
import com.pagebinder.app.ui.theme.DISABLED_ALPHA
import com.pagebinder.app.ui.theme.MinTouchTarget
import com.pagebinder.app.ui.theme.ScreenHorizontalMargin
import com.pagebinder.app.ui.theme.SpaceUnit

data class CapturePrepActions(
    val onBack: () -> Unit,
    val onModeSelected: (CaptureMode) -> Unit,
    val onMinimumIntervalChanged: (Int) -> Unit,
    val onMaximumPagesChanged: (Int?) -> Unit,
    val onMaximumMinutesChanged: (Int?) -> Unit,
    val onSensitivityChanged: (AutoCaptureSensitivity) -> Unit = {},
    val onCaptureSoundChanged: (Boolean) -> Unit = {},
    val onOpenOverlaySettings: () -> Unit,
    val onRequestNotificationPermission: () -> Unit,
    val onStart: () -> Unit,
)

@Composable
fun CapturePrepScreen(
    uiState: CapturePrepUiState,
    actions: CapturePrepActions,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            CapturePrepTopBar(actions.onBack)
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = ScreenHorizontalMargin),
                verticalArrangement = Arrangement.spacedBy(SpaceUnit * 2),
            ) {
                InfoCard {
                    Text(stringResource(R.string.capture_prep_destination), color = ColorTextSecondary)
                    Text(
                        uiState.bookTitle,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CaptureModeCard(uiState, actions)
                PermissionsCard(uiState, actions)
            }
            CaptureStartArea(uiState, actions.onStart)
        }
    }
}

@Composable
private fun CapturePrepTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = SpaceUnit),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.capture_prep_back),
            )
        }
        Text(stringResource(R.string.capture_prep_title), style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun CaptureModeCard(
    uiState: CapturePrepUiState,
    actions: CapturePrepActions,
) {
    InfoCard {
        Text(stringResource(R.string.capture_prep_mode), style = MaterialTheme.typography.titleLarge)
        Row(Modifier.fillMaxWidth()) {
            ModeButton(
                label = stringResource(R.string.capture_prep_manual),
                selected = uiState.mode == CaptureMode.MANUAL,
                onClick = { actions.onModeSelected(CaptureMode.MANUAL) },
                modifier = Modifier.weight(1f),
            )
            ModeButton(
                label = stringResource(R.string.capture_prep_continuous),
                selected = uiState.mode == CaptureMode.CONTINUOUS,
                onClick = { actions.onModeSelected(CaptureMode.CONTINUOUS) },
                modifier = Modifier.weight(1f),
            )
        }
        if (uiState.mode == CaptureMode.CONTINUOUS) {
            ContinuousSettings(uiState, actions)
            SensitivitySettings(uiState, actions)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.capture_prep_sound_title))
                Text(
                    stringResource(R.string.capture_prep_sound_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = ColorTextSecondary,
                )
            }
            Switch(
                checked = uiState.captureSoundEnabled,
                onCheckedChange = actions.onCaptureSoundChanged,
            )
        }
    }
}

@Composable
private fun SensitivitySettings(
    uiState: CapturePrepUiState,
    actions: CapturePrepActions,
) {
    Text(stringResource(R.string.capture_prep_sensitivity), style = MaterialTheme.typography.bodyLarge)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SpaceUnit)) {
        AutoCaptureSensitivity.entries.forEach { sensitivity ->
            ModeButton(
                label =
                    stringResource(
                        when (sensitivity) {
                            AutoCaptureSensitivity.LOW -> R.string.capture_prep_sensitivity_low
                            AutoCaptureSensitivity.MEDIUM -> R.string.capture_prep_sensitivity_medium
                            AutoCaptureSensitivity.HIGH -> R.string.capture_prep_sensitivity_high
                        },
                    ),
                selected = uiState.sensitivity == sensitivity,
                onClick = { actions.onSensitivityChanged(sensitivity) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ModeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(ButtonCornerRadius)
    if (selected) {
        Button(onClick = onClick, modifier = modifier.heightIn(min = MinTouchTarget), shape = shape) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier.heightIn(min = MinTouchTarget), shape = shape) {
            Text(label)
        }
    }
}

@Composable
private fun ContinuousSettings(
    uiState: CapturePrepUiState,
    actions: CapturePrepActions,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SpaceUnit)) {
        NumberSetting(
            label = stringResource(R.string.capture_prep_minimum_interval),
            value = uiState.minimumIntervalSeconds.toString(),
            suffix = stringResource(R.string.capture_prep_seconds),
            onValueChange = { it.toIntOrNull()?.let(actions.onMinimumIntervalChanged) },
            modifier = Modifier.weight(1f),
        )
        NumberSetting(
            label = stringResource(R.string.capture_prep_maximum_pages),
            value = uiState.maximumPages?.toString().orEmpty(),
            suffix = stringResource(R.string.capture_prep_pages),
            onValueChange = { actions.onMaximumPagesChanged(it.toIntOrNull()) },
            modifier = Modifier.weight(1f),
        )
        NumberSetting(
            label = stringResource(R.string.capture_prep_maximum_time),
            value = uiState.maximumMinutes?.toString().orEmpty(),
            suffix = stringResource(R.string.capture_prep_minutes),
            onValueChange = { actions.onMaximumMinutesChanged(it.toIntOrNull()) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NumberSetting(
    label: String,
    value: String,
    suffix: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = { Text(label, maxLines = 1) },
        suffix = { Text(suffix) },
        placeholder = { Text(stringResource(R.string.capture_prep_no_limit)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

@Composable
private fun PermissionsCard(
    uiState: CapturePrepUiState,
    actions: CapturePrepActions,
) {
    InfoCard {
        Text(stringResource(R.string.capture_prep_permissions), style = MaterialTheme.typography.titleLarge)
        PermissionRow(
            granted = uiState.overlayGranted,
            title = stringResource(R.string.capture_prep_overlay_title),
            description = stringResource(R.string.capture_prep_overlay_description),
            actionLabel =
                if (uiState.overlayGranted) {
                    null
                } else {
                    stringResource(R.string.capture_prep_open_settings)
                },
            onAction = actions.onOpenOverlaySettings,
        )
        if (uiState.notificationPermissionRequired) {
            HorizontalDivider()
            PermissionRow(
                granted = uiState.notificationGranted,
                title = stringResource(R.string.capture_prep_notification_title),
                description = stringResource(R.string.capture_prep_notification_description),
                actionLabel =
                    if (uiState.notificationGranted) {
                        null
                    } else {
                        stringResource(R.string.capture_prep_allow)
                    },
                onAction = actions.onRequestNotificationPermission,
            )
        }
        HorizontalDivider()
        Row(horizontalArrangement = Arrangement.spacedBy(SpaceUnit), verticalAlignment = Alignment.Top) {
            Icon(Icons.Filled.Info, contentDescription = null, tint = ColorPrimary)
            Column {
                Text(stringResource(R.string.capture_prep_projection_title))
                Text(
                    stringResource(R.string.capture_prep_projection_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = ColorTextSecondary,
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    granted: Boolean,
    title: String,
    description: String,
    actionLabel: String?,
    onAction: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(SpaceUnit), verticalAlignment = Alignment.Top) {
        Icon(
            if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
            contentDescription = null,
            tint = if (granted) ColorSuccess else ColorWarning,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(SpaceUnit / 2)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpaceUnit),
            ) {
                Text(title, modifier = Modifier.weight(1f))
                PermissionChip(granted)
                actionLabel?.let {
                    OutlinedButton(onClick = onAction, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                        Text(it)
                    }
                }
            }
            Text(description, style = MaterialTheme.typography.bodyMedium, color = ColorTextSecondary)
        }
    }
}

@Composable
private fun PermissionChip(granted: Boolean) {
    Surface(
        shape = RoundedCornerShape(50),
        color = (if (granted) ColorSuccess else ColorWarning).copy(alpha = 0.12f),
    ) {
        Text(
            stringResource(if (granted) R.string.capture_prep_granted else R.string.capture_prep_not_granted),
            modifier = Modifier.padding(horizontal = SpaceUnit, vertical = SpaceUnit / 2),
            style = MaterialTheme.typography.labelSmall,
            color = if (granted) ColorSuccess else ColorWarning,
        )
    }
}

@Composable
private fun CaptureStartArea(
    uiState: CapturePrepUiState,
    onStart: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(ScreenHorizontalMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SpaceUnit),
    ) {
        val reason =
            when {
                uiState.blockedByOverlay -> R.string.capture_prep_overlay_required
                uiState.blockedByNotifications -> R.string.capture_prep_notification_required
                uiState.projectionDenied -> R.string.capture_prep_projection_denied
                else -> null
            }
        reason?.let { Text(stringResource(it), color = ColorTextSecondary) }
        Button(
            onClick = onStart,
            enabled = uiState.canStart,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
            shape = RoundedCornerShape(ButtonCornerRadius),
            colors =
                ButtonDefaults.buttonColors(
                    disabledContainerColor = ColorPrimary.copy(alpha = DISABLED_ALPHA),
                    disabledContentColor = Color.White.copy(alpha = DISABLED_ALPHA),
                ),
        ) {
            Text(stringResource(R.string.capture_prep_start))
        }
    }
}

@Composable
private fun InfoCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CardCornerRadius),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(SpaceUnit * 2),
            verticalArrangement = Arrangement.spacedBy(SpaceUnit),
            content = content,
        )
    }
}
