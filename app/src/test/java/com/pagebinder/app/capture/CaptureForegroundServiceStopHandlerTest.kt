package com.pagebinder.app.capture

import com.pagebinder.app.domain.CaptureMode
import com.pagebinder.app.domain.CaptureSessionState
import com.pagebinder.app.domain.CaptureSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureForegroundServiceStopHandlerTest {
    @Test
    fun `通知停止 action は Coordinator 停止へ到達する`() {
        var stopCalls = 0
        val handler =
            CaptureForegroundServiceStopHandler {
                stopCalls++
                true
            }

        assertTrue(handler.handle(CaptureForegroundService.ACTION_STOP_CAPTURE))
        assertEquals(1, stopCalls)
    }

    @Test
    fun `別 action は Coordinator を停止しない`() {
        val handler = CaptureForegroundServiceStopHandler { true }

        assertFalse(handler.handle("unrelated.action"))
    }

    @Test
    fun `稼働中の二重開始は既存 FGS を停止せず拒否する`() {
        val guard = CaptureForegroundServiceStartGuard()

        assertTrue(guard.canStart(CaptureSessionState.Idle))
        assertFalse(
            guard.canStart(
                CaptureSessionState.Active(
                    CaptureMode.MANUAL,
                    CaptureSize(1080, 2400),
                ),
            ),
        )
    }
}
