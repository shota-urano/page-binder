package com.pagebinder.app.domain

import java.time.Duration
import java.time.Instant

enum class AutoCaptureSensitivity {
    LOW,
    MEDIUM,
    HIGH,
}

data class AutoCaptureSettings(
    val minimumInterval: Duration = Duration.ofSeconds(2),
    val maximumPages: Int? = null,
    val maximumDuration: Duration? = null,
    val sensitivity: AutoCaptureSensitivity = AutoCaptureSensitivity.MEDIUM,
) {
    init {
        require(minimumInterval in MINIMUM_INTERVAL..MAXIMUM_INTERVAL) {
            "Minimum capture interval must be between 1 and 30 seconds"
        }
        require(maximumPages == null || maximumPages > 0) { "Maximum pages must be positive" }
        require(maximumDuration == null || !maximumDuration.isZero && !maximumDuration.isNegative) {
            "Maximum duration must be positive"
        }
    }

    companion object {
        val MINIMUM_INTERVAL: Duration = Duration.ofSeconds(1)
        val MAXIMUM_INTERVAL: Duration = Duration.ofSeconds(30)
    }
}

interface AutoCaptureSettingsRepository {
    suspend fun read(): AutoCaptureSettings

    suspend fun save(settings: AutoCaptureSettings)
}

/** Input from the capture/image layer; it contains no Android or bitmap type. */
data class AutoCaptureFrame(
    val fingerprint: String,
    /** Distance from the immediately preceding low-resolution frame. */
    val differenceFromPrevious: Double,
) {
    init {
        require(differenceFromPrevious >= 0.0 && differenceFromPrevious.isFinite())
    }
}

sealed interface AutoCaptureState {
    data object WaitingForChange : AutoCaptureState

    data object WaitingForStability : AutoCaptureState

    data object Paused : AutoCaptureState

    data class Stopped(val reason: AutoCaptureStopReason) : AutoCaptureState
}

enum class AutoCaptureStopReason { MAXIMUM_PAGES, MAXIMUM_DURATION, EXPLICIT }

sealed interface AutoCaptureDecision {
    data object None : AutoCaptureDecision

    data object DuplicateSkipped : AutoCaptureDecision

    data object Save : AutoCaptureDecision

    /** A continuous capture was committed; preserve the result for serialized user feedback. */
    data class Saved(
        val result: CapturePageResult.Saved,
        val stopReason: AutoCaptureStopReason? = null,
    ) : AutoCaptureDecision

    /** A protected/black frame was retained as an isolated capture and needs user feedback. */
    data class Isolated(val result: CapturePageResult.Isolated) : AutoCaptureDecision

    data class Stopped(val reason: AutoCaptureStopReason) : AutoCaptureDecision
}

/**
 * Pure state machine for docs/specs/06-auto-capture.md §3.  The caller invokes [onSaved] only
 * after CaptureOnePage succeeds; this prevents an attempted save from advancing page limits.
 */
class AutoCaptureMachine(
    private val settings: AutoCaptureSettings,
    private val startedAt: Instant,
    initialLastSavedFingerprint: String? = null,
) {
    private var previousFrame: AutoCaptureFrame? = null
    private var lastSavedFingerprint: String? = initialLastSavedFingerprint
    private var lastSavedAt: Instant? = null
    private var pendingStableFingerprint: String? = null
    private var savedCount = 0
    private var stableFrameCount = 0
    private var state: AutoCaptureState = AutoCaptureState.WaitingForChange

    fun state(): AutoCaptureState = state

    fun savedCount(): Int = savedCount

    fun pause() {
        if (state !is AutoCaptureState.Stopped) state = AutoCaptureState.Paused
    }

    fun resume() {
        if (state is AutoCaptureState.Paused) state = AutoCaptureState.WaitingForChange
    }

    fun stop(): AutoCaptureDecision = stop(AutoCaptureStopReason.EXPLICIT)

    fun onFrame(
        frame: AutoCaptureFrame,
        now: Instant,
    ): AutoCaptureDecision {
        if (state is AutoCaptureState.Stopped) return AutoCaptureDecision.None
        if (maximumDurationReached(now)) return stop(AutoCaptureStopReason.MAXIMUM_DURATION)
        if (state is AutoCaptureState.Paused) return AutoCaptureDecision.None

        pendingStableFingerprint?.let { pending ->
            if (frame.differenceFromPrevious > stabilityThreshold()) {
                pendingStableFingerprint = null
            } else if (cooldownElapsed(now)) {
                pendingStableFingerprint = null
                // Saving an old stable candidate after the page changed would be incorrect.
                if (!frame.fingerprint.isNearDuplicateOf(pending)) return AutoCaptureDecision.None
                return if (frame.fingerprint.isNearDuplicateOf(lastSavedFingerprint)) {
                    AutoCaptureDecision.DuplicateSkipped
                } else {
                    AutoCaptureDecision.Save
                }
            }
        }

        val previous = previousFrame
        previousFrame = frame
        if (previous == null) return AutoCaptureDecision.None

        if (frame.differenceFromPrevious > stabilityThreshold()) {
            state = AutoCaptureState.WaitingForStability
            stableFrameCount = 0
            return AutoCaptureDecision.None
        }
        if (state !is AutoCaptureState.WaitingForStability) return AutoCaptureDecision.None
        stableFrameCount += 1
        if (stableFrameCount < REQUIRED_STABLE_FRAME_COUNT) return AutoCaptureDecision.None
        state = AutoCaptureState.WaitingForChange
        stableFrameCount = 0

        if (frame.fingerprint.isNearDuplicateOf(lastSavedFingerprint)) return AutoCaptureDecision.DuplicateSkipped
        if (!cooldownElapsed(now)) {
            pendingStableFingerprint = frame.fingerprint
            return AutoCaptureDecision.None
        }
        return AutoCaptureDecision.Save
    }

    fun onSaved(
        fingerprint: String,
        at: Instant,
    ): AutoCaptureDecision {
        lastSavedFingerprint = fingerprint
        lastSavedAt = at
        savedCount += 1
        return if (settings.maximumPages != null && savedCount >= settings.maximumPages) {
            stop(AutoCaptureStopReason.MAXIMUM_PAGES)
        } else {
            AutoCaptureDecision.None
        }
    }

    /** Advances time limits even when ImageReader has no currently available frame. */
    fun onTime(now: Instant): AutoCaptureDecision =
        if (state is AutoCaptureState.Stopped) {
            AutoCaptureDecision.None
        } else if (maximumDurationReached(now)) {
            stop(AutoCaptureStopReason.MAXIMUM_DURATION)
        } else if (state is AutoCaptureState.Paused) {
            AutoCaptureDecision.None
        } else {
            AutoCaptureDecision.None
        }

    private fun maximumDurationReached(now: Instant): Boolean =
        settings.maximumDuration?.let { !now.isBefore(startedAt.plus(it)) } ?: false

    private fun cooldownElapsed(now: Instant): Boolean =
        lastSavedAt?.let { Duration.between(it, now) >= settings.minimumInterval } ?: true

    private fun stop(reason: AutoCaptureStopReason): AutoCaptureDecision {
        state = AutoCaptureState.Stopped(reason)
        return AutoCaptureDecision.Stopped(reason)
    }

    private fun stabilityThreshold(): Double =
        when (settings.sensitivity) {
            AutoCaptureSensitivity.LOW -> 6.0
            AutoCaptureSensitivity.MEDIUM -> 4.0
            AutoCaptureSensitivity.HIGH -> 2.0
        }

    private fun String.isNearDuplicateOf(other: String?): Boolean {
        if (other == null) return false
        if (this == other) return true
        if (length != PERCEPTUAL_HASH_HEX_LENGTH || other.length != PERCEPTUAL_HASH_HEX_LENGTH) return false
        return runCatching {
            java.lang.Long.bitCount(
                java.lang.Long.parseUnsignedLong(this, HEX_RADIX) xor
                    java.lang.Long.parseUnsignedLong(other, HEX_RADIX),
            ) <= NEAR_DUPLICATE_HASH_DISTANCE
        }.getOrDefault(false)
    }

    private companion object {
        const val REQUIRED_STABLE_FRAME_COUNT = 2
        const val PERCEPTUAL_HASH_HEX_LENGTH = 16
        const val HEX_RADIX = 16
        const val NEAR_DUPLICATE_HASH_DISTANCE = 5
    }
}

/**
 * Reuses the manual transaction for automatic saves.  Frame sampling/bitmap analysis stays in
 * `capture/` and `image/`; callers feed its compact [AutoCaptureFrame] observations here.
 */
class AutoCapture(
    private val machine: AutoCaptureMachine,
    private val captureOnePage: CaptureOnePage,
) {
    suspend fun onFrame(
        projectId: java.util.UUID,
        frame: AutoCaptureFrame,
        now: Instant,
    ): AutoCaptureDecision {
        when (val decision = machine.onFrame(frame, now)) {
            AutoCaptureDecision.Save -> {
                return when (val result = captureOnePage.capture(projectId)) {
                    is CapturePageResult.Saved ->
                        AutoCaptureDecision.Saved(
                            result = result,
                            stopReason =
                                (
                                    machine.onSaved(result.page.perceptualHash, now) as? AutoCaptureDecision.Stopped
                                )?.reason,
                        )
                    is CapturePageResult.Isolated -> AutoCaptureDecision.Isolated(result)
                    CapturePageResult.IgnoredAlreadyCapturing,
                    is CapturePageResult.Failed,
                    -> AutoCaptureDecision.None
                }
            }
            else -> return decision
        }
    }

    fun pause() = machine.pause()

    fun resume() = machine.resume()

    fun stop(): AutoCaptureDecision = machine.stop()

    fun savedCount(): Int = machine.savedCount()

    fun onTime(now: Instant): AutoCaptureDecision = machine.onTime(now)
}
