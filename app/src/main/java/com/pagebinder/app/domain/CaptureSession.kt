package com.pagebinder.app.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CaptureMode {
    MANUAL,
    CONTINUOUS,
}

data class CaptureSize(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0)
        require(height > 0)
    }
}

data class CapturedFrame(
    val width: Int,
    val height: Int,
    val argbPixels: IntArray,
)

/** Opaque one-shot proof of consent. Android types stay in the capture implementation. */
interface CapturePermissionToken

sealed interface CaptureSessionState {
    data object Idle : CaptureSessionState

    data class Preparing(val mode: CaptureMode) : CaptureSessionState

    data class Active(
        val mode: CaptureMode,
        val size: CaptureSize,
    ) : CaptureSessionState

    data object Stopping : CaptureSessionState
}

sealed interface CaptureGatewayStartResult {
    data class Started(val size: CaptureSize) : CaptureGatewayStartResult

    data class Rejected(val reason: CaptureStartRejection) : CaptureGatewayStartResult

    data class Failed(val reason: CaptureStartFailure) : CaptureGatewayStartResult
}

enum class CaptureStartRejection {
    PERMISSION_ALREADY_CONSUMED,
    INVALID_PERMISSION_TOKEN,
    GATEWAY_ALREADY_ACTIVE,
}

enum class CaptureStartFailure {
    MEDIA_PROJECTION_UNAVAILABLE,
    VIRTUAL_DISPLAY_UNAVAILABLE,
}

sealed interface CaptureGatewayEvent {
    data class ContentResized(val size: CaptureSize) : CaptureGatewayEvent

    data class ProjectionStopped(val reason: CaptureStopReason) : CaptureGatewayEvent
}

enum class CaptureStopReason {
    EXPLICIT,
    OS_STOPPED,
    OTHER_PROJECTION_STARTED,
    SCREEN_LOCKED,
    ERROR,
}

interface CaptureGateway {
    val events: Flow<CaptureGatewayEvent>

    fun start(permissionToken: CapturePermissionToken): CaptureGatewayStartResult

    fun stop()

    fun latestFrame(): CapturedFrame?
}

enum class CaptureOverlayState {
    MANUAL_ACTIVE,
    CONTINUOUS_ACTIVE,
    CONTINUOUS_PAUSED,
    STOPPED,
}

interface CaptureOverlayGateway {
    fun show(
        state: CaptureOverlayState,
        savedCount: Int = 0,
    )

    fun update(
        state: CaptureOverlayState,
        savedCount: Int = 0,
    )

    fun hideForCapture()

    fun restoreAfterCapture()

    fun remove()
}

class CaptureSessionCoordinator(
    private val captureGateway: CaptureGateway,
    private val captureSessionLifecycle: CaptureSessionLifecycle,
    private val overlayGateway: CaptureOverlayGateway,
    eventScope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<CaptureSessionState>(CaptureSessionState.Idle)
    val state: StateFlow<CaptureSessionState> = mutableState.asStateFlow()
    private val mutableLastStopReason = MutableStateFlow<CaptureStopReason?>(null)
    val lastStopReason: StateFlow<CaptureStopReason?> = mutableLastStopReason.asStateFlow()

    init {
        eventScope.launch {
            captureGateway.events.collect(::handleGatewayEvent)
        }
    }

    fun prepare(mode: CaptureMode): Boolean {
        if (mutableState.value !is CaptureSessionState.Idle) return false
        mutableLastStopReason.value = null
        mutableState.value = CaptureSessionState.Preparing(mode)
        return true
    }

    fun start(permissionToken: CapturePermissionToken): CaptureGatewayStartResult? {
        val preparing = mutableState.value as? CaptureSessionState.Preparing ?: return null
        return captureGateway.start(permissionToken).also { result ->
            when (result) {
                is CaptureGatewayStartResult.Started -> activate(preparing.mode, result.size)
                is CaptureGatewayStartResult.Rejected,
                is CaptureGatewayStartResult.Failed,
                -> mutableState.value = CaptureSessionState.Idle
            }
        }
    }

    fun onPermissionDenied() {
        if (mutableState.value is CaptureSessionState.Preparing) {
            mutableState.value = CaptureSessionState.Idle
        }
    }

    fun onScreenLocked(): Boolean = stop(CaptureStopReason.SCREEN_LOCKED)

    fun stop(reason: CaptureStopReason = CaptureStopReason.EXPLICIT): Boolean {
        val wasActive = mutableState.value is CaptureSessionState.Active
        if (!wasActive && mutableState.value !is CaptureSessionState.Preparing) return false
        finishSession(wasActive, reason)
        return true
    }

    private fun activate(
        mode: CaptureMode,
        size: CaptureSize,
    ) {
        mutableState.value = CaptureSessionState.Active(mode, size)
        captureSessionLifecycle.onSessionActive()
        overlayGateway.show(
            if (mode == CaptureMode.MANUAL) {
                CaptureOverlayState.MANUAL_ACTIVE
            } else {
                CaptureOverlayState.CONTINUOUS_ACTIVE
            },
        )
    }

    private fun handleGatewayEvent(event: CaptureGatewayEvent) {
        when (event) {
            is CaptureGatewayEvent.ContentResized -> {
                val active = mutableState.value as? CaptureSessionState.Active ?: return
                mutableState.value = active.copy(size = event.size)
            }
            is CaptureGatewayEvent.ProjectionStopped -> {
                val wasActive = mutableState.value is CaptureSessionState.Active
                if (wasActive || mutableState.value is CaptureSessionState.Preparing) {
                    finishSession(wasActive, event.reason)
                }
            }
        }
    }

    private fun finishSession(
        wasActive: Boolean,
        reason: CaptureStopReason,
    ) {
        mutableState.value = CaptureSessionState.Stopping
        mutableLastStopReason.value = reason
        overlayGateway.update(CaptureOverlayState.STOPPED)
        captureGateway.stop()
        overlayGateway.remove()
        mutableState.value = CaptureSessionState.Idle
        if (wasActive) captureSessionLifecycle.onSessionIdle()
    }
}
