package com.pagebinder.app.ui.overlay

import com.pagebinder.app.domain.CaptureOverlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureOverlayControllerTest {
    @Test
    fun `capture flow can hide then restore an attached overlay`() {
        val window = FakeOverlayWindow()
        val controller = CaptureOverlayController(window)

        controller.show(CaptureOverlayState.MANUAL_ACTIVE)
        controller.hideForCapture()
        assertFalse(window.visibleState)

        controller.restoreAfterCapture()
        assertTrue(window.visibleState)
        assertEquals(1, window.attachCalls)
    }

    @Test
    fun `manual continuous paused and stopped states reach the overlay window`() {
        val window = FakeOverlayWindow()
        val controller = CaptureOverlayController(window)

        controller.show(CaptureOverlayState.MANUAL_ACTIVE)
        controller.update(CaptureOverlayState.CONTINUOUS_ACTIVE, 24)
        controller.update(CaptureOverlayState.CONTINUOUS_PAUSED, 24)
        controller.update(CaptureOverlayState.STOPPED, 24)

        assertEquals(
            listOf(
                CaptureOverlayState.MANUAL_ACTIVE,
                CaptureOverlayState.CONTINUOUS_ACTIVE,
                CaptureOverlayState.CONTINUOUS_PAUSED,
                CaptureOverlayState.STOPPED,
            ),
            window.states,
        )
    }

    @Test
    fun `snap chooses the nearest screen edge`() {
        assertEquals(0, OverlaySnapCalculator.snapX(currentX = 100, overlayWidth = 120, screenWidth = 1080))
        assertEquals(960, OverlaySnapCalculator.snapX(currentX = 800, overlayWidth = 120, screenWidth = 1080))
    }

    private class FakeOverlayWindow : OverlayWindow {
        var visibleState = false
        var attachCalls = 0
        val states = mutableListOf<CaptureOverlayState>()

        override fun attach(
            state: CaptureOverlayState,
            savedCount: Int,
        ) {
            attachCalls += 1
            visibleState = true
            states += state
        }

        override fun update(
            state: CaptureOverlayState,
            savedCount: Int,
        ) {
            states += state
        }

        override fun setVisible(visible: Boolean) {
            visibleState = visible
        }

        override fun detach() {
            visibleState = false
        }
    }
}
