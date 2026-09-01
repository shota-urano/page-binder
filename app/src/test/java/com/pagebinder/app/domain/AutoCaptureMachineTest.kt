package com.pagebinder.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Duration
import java.time.Instant

class AutoCaptureMachineTest {
    @Test
    fun `does not save until changed screen becomes stable`() {
        val machine = AutoCaptureMachine(AutoCaptureSettings(), START)
        machine.onFrame(frame("first", 0.0), START)
        assertEquals(AutoCaptureDecision.None, machine.onFrame(frame("changed", 8.0), START.plusSeconds(1)))
        assertEquals(AutoCaptureDecision.None, machine.onFrame(frame("changed", 0.0), START.plusSeconds(2)))
        assertEquals(AutoCaptureDecision.Save, machine.onFrame(frame("changed", 0.0), START.plusSeconds(3)))
    }

    @Test
    fun `skips duplicate and respects the minimum interval`() {
        val machine = AutoCaptureMachine(AutoCaptureSettings(minimumInterval = Duration.ofSeconds(2)), START)
        machine.onFrame(frame("initial", 0.0), START)
        machine.onFrame(frame("page-1", 8.0), START.plusSeconds(1))
        machine.onFrame(frame("page-1", 0.0), START.plusSeconds(2))
        assertEquals(AutoCaptureDecision.Save, machine.onFrame(frame("page-1", 0.0), START.plusSeconds(3)))
        machine.onSaved("page-1", START.plusSeconds(3))
        machine.onFrame(frame("page-1", 8.0), START.plusSeconds(4))
        machine.onFrame(frame("page-1", 0.0), START.plusSeconds(4))
        assertEquals(AutoCaptureDecision.DuplicateSkipped, machine.onFrame(frame("page-1", 0.0), START.plusSeconds(4)))
        machine.onFrame(frame("page-2", 8.0), START.plusSeconds(4))
        machine.onFrame(frame("page-2", 0.0), START.plusSeconds(4))
        assertEquals(AutoCaptureDecision.None, machine.onFrame(frame("page-2", 0.0), START.plusSeconds(4)))
    }

    @Test
    fun `stops at configured page limit`() {
        val machine = AutoCaptureMachine(AutoCaptureSettings(maximumPages = 1), START)
        assertEquals(AutoCaptureDecision.Stopped(AutoCaptureStopReason.MAXIMUM_PAGES), machine.onSaved("page", START))
        assertEquals(AutoCaptureState.Stopped(AutoCaptureStopReason.MAXIMUM_PAGES), machine.state())
    }

    @Test
    fun `keeps a stable candidate until the minimum interval has elapsed`() {
        val machine = AutoCaptureMachine(AutoCaptureSettings(minimumInterval = Duration.ofSeconds(3)), START)
        machine.onSaved("saved", START)
        machine.onFrame(frame("saved", 0.0), START.plusSeconds(1))
        machine.onFrame(frame("candidate", 8.0), START.plusSeconds(2))
        machine.onFrame(frame("candidate", 0.0), START.plusSeconds(2))
        assertEquals(AutoCaptureDecision.None, machine.onFrame(frame("candidate", 0.0), START.plusSeconds(2)))

        assertEquals(AutoCaptureDecision.Save, machine.onFrame(frame("candidate", 0.0), START.plusSeconds(3)))
    }

    @Test
    fun `new change invalidates a pending stable candidate`() {
        val machine = AutoCaptureMachine(AutoCaptureSettings(minimumInterval = Duration.ofSeconds(4)), START)
        machine.onSaved("0000000000000001", START)
        machine.onFrame(frame("0000000000000001", 0.0), START.plusSeconds(1))
        machine.onFrame(frame("0000000000000002", 8.0), START.plusSeconds(2))
        machine.onFrame(frame("0000000000000002", 0.0), START.plusSeconds(2))
        machine.onFrame(frame("0000000000000002", 0.0), START.plusSeconds(2))

        assertEquals(AutoCaptureDecision.None, machine.onFrame(frame("0000000000000003", 8.0), START.plusSeconds(4)))
        assertEquals(AutoCaptureDecision.None, machine.onFrame(frame("0000000000000003", 0.0), START.plusSeconds(4)))
    }

    @Test
    fun `initial saved fingerprint skips an existing last page`() {
        val machine =
            AutoCaptureMachine(
                AutoCaptureSettings(),
                START,
                initialLastSavedFingerprint = "0000000000000001",
            )
        machine.onFrame(frame("0000000000000001", 0.0), START)
        machine.onFrame(frame("0000000000000001", 8.0), START.plusSeconds(1))
        machine.onFrame(frame("0000000000000001", 0.0), START.plusSeconds(2))

        assertEquals(
            AutoCaptureDecision.DuplicateSkipped,
            machine.onFrame(frame("0000000000000001", 0.0), START.plusSeconds(3)),
        )
    }

    @Test
    fun `stops when maximum duration elapses`() {
        val machine = AutoCaptureMachine(AutoCaptureSettings(maximumDuration = Duration.ofSeconds(3)), START)
        assertEquals(
            AutoCaptureDecision.Stopped(AutoCaptureStopReason.MAXIMUM_DURATION),
            machine.onFrame(frame("late", 0.0), START.plusSeconds(3)),
        )
    }

    @Test
    fun `time limit stops without a frame`() {
        val machine = AutoCaptureMachine(AutoCaptureSettings(maximumDuration = Duration.ofSeconds(3)), START)

        assertEquals(
            AutoCaptureDecision.Stopped(AutoCaptureStopReason.MAXIMUM_DURATION),
            machine.onTime(START.plusSeconds(3)),
        )
    }

    @Test
    fun `time limit stops while paused`() {
        val machine = AutoCaptureMachine(AutoCaptureSettings(maximumDuration = Duration.ofSeconds(3)), START)
        machine.pause()

        assertEquals(
            AutoCaptureDecision.Stopped(AutoCaptureStopReason.MAXIMUM_DURATION),
            machine.onTime(START.plusSeconds(3)),
        )
    }

    @Test
    fun `paused machine ignores frames without losing paused state`() {
        val machine = AutoCaptureMachine(AutoCaptureSettings(), START)
        machine.pause()

        assertEquals(AutoCaptureDecision.None, machine.onFrame(frame("page", 0.0), START.plusSeconds(1)))
        assertEquals(AutoCaptureState.Paused, machine.state())
    }

    private fun frame(
        fingerprint: String,
        difference: Double,
    ) = AutoCaptureFrame(fingerprint, difference)

    private companion object {
        val START: Instant = Instant.parse("2026-08-31T00:00:00Z")
    }
}
