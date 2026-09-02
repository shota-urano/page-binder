package com.pagebinder.app.capture

import com.pagebinder.app.domain.AutoCapture
import com.pagebinder.app.domain.AutoCaptureDecision
import com.pagebinder.app.domain.AutoCaptureMachine
import com.pagebinder.app.domain.AutoCaptureSettings
import com.pagebinder.app.domain.AutoCaptureSettingsRepository
import com.pagebinder.app.domain.AutoCaptureStopReason
import com.pagebinder.app.domain.CaptureFeedbackController
import com.pagebinder.app.domain.CaptureGateway
import com.pagebinder.app.domain.CaptureMode
import com.pagebinder.app.domain.CaptureOnePage
import com.pagebinder.app.domain.CaptureOverlayGateway
import com.pagebinder.app.domain.CaptureOverlayState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/** Bridges the overlay's synchronous click callback to the suspending one-page use case. */
class CapturePageController(
    private val scope: CoroutineScope,
    private val captureOnePage: CaptureOnePage,
    private val feedback: CaptureFeedbackController,
    private val captureGateway: CaptureGateway,
    private val overlayGateway: CaptureOverlayGateway,
    private val settingsRepository: AutoCaptureSettingsRepository,
    private val lastSavedFingerprint: suspend (UUID) -> String?,
    private val stopSession: () -> Unit,
    /** 上限到達による自動停止だけを知らせる（docs/specs/06-auto-capture.md §6） */
    private val onAutoStopped: (AutoCaptureStopReason) -> Unit = {},
) {
    private var projectId: UUID? = null
    private var continuousJob: Job? = null
    private var autoCapture: AutoCapture? = null
    private val autoCaptureMutex = Mutex()

    /** Called only after MediaProjection has started, so a rejected second start cannot retarget it. */
    fun onSessionStarted(
        projectId: UUID,
        mode: CaptureMode,
        settings: AutoCaptureSettings,
    ) {
        if (this.projectId != null) return
        this.projectId = projectId
        if (mode == CaptureMode.CONTINUOUS) startContinuous(settings)
    }

    fun clear() {
        continuousJob?.cancel()
        continuousJob = null
        autoCapture = null
        projectId = null
    }

    fun capture() {
        val destination = projectId ?: return
        scope.launch {
            feedback.present(captureOnePage.capture(destination))
        }
    }

    fun setPaused(paused: Boolean) {
        scope.launch {
            autoCaptureMutex.withLock {
                val capture = autoCapture ?: return@withLock
                if (paused) {
                    capture.pause()
                    overlayGateway.update(CaptureOverlayState.CONTINUOUS_PAUSED, capture.savedCount())
                } else {
                    capture.resume()
                    overlayGateway.update(CaptureOverlayState.CONTINUOUS_ACTIVE, capture.savedCount())
                }
            }
        }
    }

    private fun startContinuous(settings: AutoCaptureSettings) {
        val destination = projectId ?: return
        continuousJob =
            scope.launch {
                val machine = AutoCaptureMachine(settings, Instant.now(), lastSavedFingerprint(destination))
                val capture = AutoCapture(machine, captureOnePage)
                autoCaptureMutex.withLock { autoCapture = capture }
                settingsRepository.save(settings)
                val analyzer = ContinuousFrameAnalyzer()
                while (true) {
                    val currentTime = Instant.now()
                    val frame = captureGateway.latestFrame()?.let(analyzer::analyze)
                    val decision =
                        autoCaptureMutex.withLock {
                            val result =
                                frame?.let { analyzedFrame ->
                                    capture.onFrame(destination, analyzedFrame, currentTime)
                                } ?: capture.onTime(currentTime)
                            when (result) {
                                is AutoCaptureDecision.Saved -> {
                                    feedback.present(result.result)
                                    overlayGateway.update(
                                        if (result.stopReason == null) {
                                            CaptureOverlayState.CONTINUOUS_ACTIVE
                                        } else {
                                            CaptureOverlayState.STOPPED
                                        },
                                        capture.savedCount(),
                                    )
                                }
                                AutoCaptureDecision.Save -> Unit
                                is AutoCaptureDecision.Stopped ->
                                    overlayGateway.update(CaptureOverlayState.STOPPED, capture.savedCount())
                                AutoCaptureDecision.DuplicateSkipped,
                                is AutoCaptureDecision.Isolated,
                                AutoCaptureDecision.None,
                                -> Unit
                            }
                            result
                        }
                    when (decision) {
                        is AutoCaptureDecision.Saved ->
                            if (decision.stopReason != null) {
                                onAutoStopped(decision.stopReason)
                                stopSession()
                                return@launch
                            }
                        AutoCaptureDecision.Save -> Unit
                        is AutoCaptureDecision.Isolated ->
                            autoCaptureMutex.withLock { feedback.present(decision.result) }
                        is AutoCaptureDecision.Stopped -> {
                            onAutoStopped(decision.reason)
                            stopSession()
                            return@launch
                        }
                        AutoCaptureDecision.DuplicateSkipped,
                        AutoCaptureDecision.None,
                        -> Unit
                    }
                    delay(FRAME_POLL_INTERVAL_MILLIS)
                }
            }
    }

    private companion object {
        const val FRAME_POLL_INTERVAL_MILLIS = 250L
    }
}
