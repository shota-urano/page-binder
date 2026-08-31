package com.pagebinder.app.ui.captureprep

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pagebinder.app.domain.AutoCaptureSettings
import java.time.Duration

data class AuthorizedCaptureRequest(
    val resultCode: Int,
    val permissionData: Intent,
    val mode: CaptureMode,
    val autoCaptureSettings: AutoCaptureSettings,
)

@Composable
fun CapturePrepRoute(
    viewModel: CapturePrepViewModel,
    onBack: () -> Unit,
    onCaptureAuthorized: (AuthorizedCaptureRequest) -> Unit,
    onCaptureDenied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    fun refreshPermissions() {
        viewModel.refreshPermissions(
            overlayGranted = Settings.canDrawOverlays(context),
            notificationPermissionRequired = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
            notificationGranted = context.notificationPermissionGranted(),
        )
    }

    val overlaySettingsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshPermissions()
        }
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshPermissions()
        }
    val projectionManager =
        remember(context) { context.getSystemService(MediaProjectionManager::class.java) }
    val projectionConsentLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val permissionData = result.data
            val granted = result.resultCode == Activity.RESULT_OK && permissionData != null
            viewModel.onProjectionConsentResult(granted)
            if (granted) {
                onCaptureAuthorized(
                    AuthorizedCaptureRequest(
                        resultCode = result.resultCode,
                        permissionData = checkNotNull(permissionData),
                        mode = uiState.mode,
                        autoCaptureSettings =
                            AutoCaptureSettings(
                                minimumInterval = Duration.ofSeconds(uiState.minimumIntervalSeconds.toLong()),
                                maximumPages = uiState.maximumPages,
                                maximumDuration = uiState.maximumMinutes?.let { Duration.ofMinutes(it.toLong()) },
                                sensitivity = uiState.sensitivity,
                            ),
                    ),
                )
            } else {
                onCaptureDenied()
            }
        }

    DisposableEffect(lifecycleOwner, context) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) refreshPermissions()
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(context) {
        refreshPermissions()
    }

    LaunchedEffect(uiState.projectionConsentRequest) {
        if (uiState.projectionConsentRequest != null) {
            projectionConsentLauncher.launch(projectionManager.createScreenCaptureIntent())
            viewModel.onProjectionConsentLaunched()
        }
    }

    CapturePrepScreen(
        uiState = uiState,
        actions =
            CapturePrepActions(
                onBack = onBack,
                onModeSelected = viewModel::onModeSelected,
                onMinimumIntervalChanged = viewModel::onMinimumIntervalChanged,
                onMaximumPagesChanged = viewModel::onMaximumPagesChanged,
                onMaximumMinutesChanged = viewModel::onMaximumMinutesChanged,
                onSensitivityChanged = viewModel::onSensitivityChanged,
                onCaptureSoundChanged = viewModel::onCaptureSoundChanged,
                onOpenOverlaySettings = {
                    overlaySettingsLauncher.launch(
                        Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"),
                        ),
                    )
                },
                onRequestNotificationPermission = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                },
                onStart = viewModel::onStartRequested,
            ),
        modifier = modifier,
    )
}

private fun Context.notificationPermissionGranted(): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
