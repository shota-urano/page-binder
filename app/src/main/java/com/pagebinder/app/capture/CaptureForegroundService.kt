package com.pagebinder.app.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.pagebinder.app.PageBinderApplication
import com.pagebinder.app.R
import com.pagebinder.app.domain.AutoCaptureSensitivity
import com.pagebinder.app.domain.AutoCaptureSettings
import com.pagebinder.app.domain.CaptureGatewayStartResult
import com.pagebinder.app.domain.CaptureMode
import com.pagebinder.app.domain.CaptureSessionCoordinator
import com.pagebinder.app.domain.CaptureSessionState
import com.pagebinder.app.domain.CaptureStopReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.time.Duration
import java.util.UUID

class CaptureForegroundServiceStopHandler(
    private val stopCaptureSession: () -> Boolean,
) {
    fun handle(action: String?): Boolean =
        if (action == CaptureForegroundService.ACTION_STOP_CAPTURE) stopCaptureSession() else false
}

class CaptureForegroundServiceStartGuard {
    fun canStart(state: CaptureSessionState): Boolean = state is CaptureSessionState.Idle
}

/** Rechecks the special-access grant immediately before consuming MediaProjection consent. */
class CaptureOverlayPermissionGuard {
    fun canStart(overlayGranted: Boolean): Boolean = overlayGranted
}

class CaptureForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var coordinator: CaptureSessionCoordinator
    private var stateObserver: Job? = null
    private var sessionStarted = false
    private var receiverRegistered = false

    private val screenOffReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                    coordinator.onScreenLocked()
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        coordinator = (application as PageBinderApplication).captureSessionCoordinator
        createNotificationChannel()
        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
        stateObserver =
            serviceScope.launch {
                coordinator.state.collect { state ->
                    if (sessionStarted && state is CaptureSessionState.Idle) {
                        (application as PageBinderApplication).capturePageController.clear()
                        val reason = coordinator.lastStopReason.value
                        stopServiceForeground()
                        if (reason != null && reason != CaptureStopReason.EXPLICIT) {
                            showUnexpectedStopNotification()
                        }
                    }
                }
            }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        if (CaptureForegroundServiceStopHandler(coordinator::stop).handle(intent?.action)) {
            clearCaptureController()
            stopServiceForeground()
            return START_NOT_STICKY
        }
        val requestedMode = intent?.captureMode() ?: CaptureMode.MANUAL
        val currentState = coordinator.state.value
        if (!CaptureForegroundServiceStartGuard().canStart(currentState)) {
            val activeMode =
                when (currentState) {
                    is CaptureSessionState.Active -> currentState.mode
                    is CaptureSessionState.Preparing -> currentState.mode
                    CaptureSessionState.Idle,
                    CaptureSessionState.Stopping,
                    -> requestedMode
                }
            // Every startForegroundService call is acknowledged without stopping the active session.
            startAsMediaProjectionForegroundService(activeMode)
            return START_NOT_STICKY
        }
        // The user can revoke this special access while the MediaProjection consent dialog is
        // visible. Do not create an active session that cannot present its capture controls.
        if (!CaptureOverlayPermissionGuard().canStart(Settings.canDrawOverlays(this))) {
            clearCaptureController()
            stopServiceForeground()
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val resultData = intent?.parcelableIntentExtra(EXTRA_RESULT_DATA)
        if (resultCode == Int.MIN_VALUE || resultData == null) {
            clearCaptureController()
            stopServiceForeground()
            return START_NOT_STICKY
        }

        // Consent has already completed. FGS must be active before the gateway obtains MediaProjection.
        startAsMediaProjectionForegroundService(requestedMode)
        if (!coordinator.prepare(requestedMode)) {
            clearCaptureController()
            stopServiceForeground()
            return START_NOT_STICKY
        }
        val permissionToken = AndroidCapturePermissionToken.fromPermissionResult(resultCode, resultData)
        intent.removeExtra(EXTRA_RESULT_DATA)
        val result = coordinator.start(permissionToken)
        sessionStarted = result is CaptureGatewayStartResult.Started
        if (!sessionStarted) {
            clearCaptureController()
            stopServiceForeground()
        } else {
            val settings = intent?.autoCaptureSettings() ?: AutoCaptureSettings()
            intent?.captureProjectId()?.let { projectId ->
                (application as PageBinderApplication).capturePageController.onSessionStarted(
                    projectId = projectId,
                    mode = requestedMode,
                    settings = settings,
                )
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        coordinator.stop()
        clearCaptureController()
        stopServiceForeground()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (sessionStarted) coordinator.stop()
        clearCaptureController()
        if (receiverRegistered) unregisterReceiver(screenOffReceiver)
        stateObserver?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startAsMediaProjectionForegroundService(mode: CaptureMode) {
        val stopPendingIntent =
            PendingIntent.getService(
                this,
                STOP_PENDING_INTENT_REQUEST_CODE,
                Intent(this, CaptureForegroundService::class.java).setAction(ACTION_STOP_CAPTURE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val notification =
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle(getString(R.string.capture_notification_title))
                .setContentText(
                    getString(
                        if (mode == CaptureMode.CONTINUOUS) {
                            R.string.capture_notification_continuous
                        } else {
                            R.string.capture_notification_manual
                        },
                    ),
                )
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(
                    Notification.Action.Builder(
                        null,
                        getString(R.string.capture_notification_stop),
                        stopPendingIntent,
                    ).build(),
                ).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.capture_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.capture_notification_channel_description)
                setShowBadge(false)
            }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun showUnexpectedStopNotification() {
        val notification =
            Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
                .setContentTitle(getString(R.string.capture_notification_stopped_title))
                .setContentText(getString(R.string.capture_notification_stopped_message))
                .setAutoCancel(true)
                .build()
        getSystemService(NotificationManager::class.java).notify(STOPPED_NOTIFICATION_ID, notification)
    }

    private fun stopServiceForeground() {
        sessionStarted = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun clearCaptureController() {
        (application as PageBinderApplication).capturePageController.clear()
    }

    private fun Intent.captureMode(): CaptureMode =
        if (getStringExtra(EXTRA_CAPTURE_MODE) == CaptureMode.CONTINUOUS.name) {
            CaptureMode.CONTINUOUS
        } else {
            CaptureMode.MANUAL
        }

    private fun Intent.captureProjectId(): UUID? =
        getStringExtra(EXTRA_PROJECT_ID)?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun Intent.autoCaptureSettings(): AutoCaptureSettings =
        AutoCaptureSettings(
            minimumInterval = Duration.ofSeconds(getIntExtra(EXTRA_MINIMUM_INTERVAL_SECONDS, 2).toLong()),
            maximumPages = getIntExtra(EXTRA_MAXIMUM_PAGES, 0).takeIf { it > 0 },
            maximumDuration =
                getIntExtra(EXTRA_MAXIMUM_DURATION_SECONDS, 0)
                    .takeIf { it > 0 }
                    ?.let { Duration.ofSeconds(it.toLong()) },
            sensitivity =
                getStringExtra(EXTRA_SENSITIVITY)
                    ?.let { value -> AutoCaptureSensitivity.entries.firstOrNull { it.name == value } }
                    ?: AutoCaptureSensitivity.MEDIUM,
        )

    @Suppress("DEPRECATION")
    private fun Intent.parcelableIntentExtra(key: String): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(key, Intent::class.java)
        } else {
            getParcelableExtra(key)
        }

    companion object {
        const val ACTION_STOP_CAPTURE = "com.pagebinder.app.capture.action.STOP"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_RESULT_DATA = "result_data"
        private const val EXTRA_CAPTURE_MODE = "capture_mode"
        private const val EXTRA_PROJECT_ID = "project_id"
        private const val EXTRA_MINIMUM_INTERVAL_SECONDS = "minimum_interval_seconds"
        private const val EXTRA_MAXIMUM_PAGES = "maximum_pages"
        private const val EXTRA_MAXIMUM_DURATION_SECONDS = "maximum_duration_seconds"
        private const val EXTRA_SENSITIVITY = "sensitivity"
        private const val NOTIFICATION_CHANNEL_ID = "capture_session"
        private const val NOTIFICATION_ID = 2501
        private const val STOPPED_NOTIFICATION_ID = 2503
        private const val STOP_PENDING_INTENT_REQUEST_CODE = 2502

        fun start(
            context: Context,
            resultCode: Int,
            resultData: Intent,
            mode: CaptureMode,
            projectId: UUID,
            autoCaptureSettings: AutoCaptureSettings,
        ) {
            val intent =
                Intent(context, CaptureForegroundService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_RESULT_DATA, resultData)
                    .putExtra(EXTRA_CAPTURE_MODE, mode.name)
                    .putExtra(EXTRA_PROJECT_ID, projectId.toString())
                    .putExtra(EXTRA_MINIMUM_INTERVAL_SECONDS, autoCaptureSettings.minimumInterval.seconds.toInt())
                    .putExtra(EXTRA_MAXIMUM_PAGES, autoCaptureSettings.maximumPages ?: 0)
                    .putExtra(
                        EXTRA_MAXIMUM_DURATION_SECONDS,
                        autoCaptureSettings.maximumDuration?.seconds?.toInt() ?: 0,
                    )
                    .putExtra(EXTRA_SENSITIVITY, autoCaptureSettings.sensitivity.name)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
