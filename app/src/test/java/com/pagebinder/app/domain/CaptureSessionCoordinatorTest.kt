package com.pagebinder.app.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureSessionCoordinatorTest {
    @Test
    fun `開始は preparing から active へ遷移して OCR を撮影優先にする`() =
        runTest {
            val fixture = fixture(backgroundScope)

            assertTrue(fixture.coordinator.prepare(CaptureMode.MANUAL))
            assertEquals(CaptureSessionState.Preparing(CaptureMode.MANUAL), fixture.coordinator.state.value)
            assertEquals(CaptureGatewayStartResult.Started(PORTRAIT), fixture.coordinator.start(FakePermissionToken))

            assertEquals(CaptureSessionState.Active(CaptureMode.MANUAL, PORTRAIT), fixture.coordinator.state.value)
            assertEquals(1, fixture.lifecycle.activeCount)
            assertEquals(listOf(CaptureOverlayState.MANUAL_ACTIVE), fixture.overlay.shown)
        }

    @Test
    fun `許可拒否は gateway を開始せず idle に戻る`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.coordinator.prepare(CaptureMode.CONTINUOUS)

            fixture.coordinator.onPermissionDenied()

            assertEquals(CaptureSessionState.Idle, fixture.coordinator.state.value)
            assertEquals(0, fixture.gateway.startCount)
            assertEquals(0, fixture.lifecycle.activeCount)
        }

    @Test
    fun `OS 停止は安全解放と OCR 再開を一回だけ行う`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.coordinator.prepare(CaptureMode.MANUAL)
            fixture.coordinator.start(FakePermissionToken)

            fixture.gateway.emit(CaptureGatewayEvent.ProjectionStopped(CaptureStopReason.OS_STOPPED))
            runCurrent()

            assertEquals(CaptureSessionState.Idle, fixture.coordinator.state.value)
            assertEquals(1, fixture.gateway.stopCount)
            assertEquals(1, fixture.lifecycle.idleCount)
            assertTrue(fixture.overlay.removed)
            assertEquals(CaptureStopReason.OS_STOPPED, fixture.coordinator.lastStopReason.value)
        }

    @Test
    fun `単一アプリ共有の対象が不可視なら専用理由で安全停止する`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.coordinator.prepare(CaptureMode.MANUAL)
            fixture.coordinator.start(FakePermissionToken)

            fixture.gateway.emit(
                CaptureGatewayEvent.ProjectionStopped(CaptureStopReason.SHARED_CONTENT_NOT_VISIBLE),
            )
            runCurrent()

            assertEquals(CaptureSessionState.Idle, fixture.coordinator.state.value)
            assertEquals(CaptureStopReason.SHARED_CONTENT_NOT_VISIBLE, fixture.coordinator.lastStopReason.value)
            assertEquals(1, fixture.gateway.stopCount)
            assertEquals(1, fixture.lifecycle.idleCount)
        }

    @Test
    fun `共有領域のリサイズは active 状態の次フレーム寸法に反映する`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.coordinator.prepare(CaptureMode.CONTINUOUS)
            fixture.coordinator.start(FakePermissionToken)

            fixture.gateway.emit(CaptureGatewayEvent.ContentResized(LANDSCAPE))
            runCurrent()

            assertEquals(CaptureSessionState.Active(CaptureMode.CONTINUOUS, LANDSCAPE), fixture.coordinator.state.value)
            assertEquals(0, fixture.gateway.stopCount)
        }

    @Test
    fun `明示停止は冪等で gateway と lifecycle を一度だけ停止する`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.coordinator.prepare(CaptureMode.MANUAL)
            fixture.coordinator.start(FakePermissionToken)

            assertTrue(fixture.coordinator.stop())
            assertFalse(fixture.coordinator.stop())

            assertEquals(CaptureSessionState.Idle, fixture.coordinator.state.value)
            assertEquals(1, fixture.gateway.stopCount)
            assertEquals(1, fixture.lifecycle.idleCount)
        }

    @Test
    fun `画面ロックは理由を保持して安全停止する`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.coordinator.prepare(CaptureMode.MANUAL)
            fixture.coordinator.start(FakePermissionToken)

            assertTrue(fixture.coordinator.onScreenLocked())

            assertEquals(CaptureSessionState.Idle, fixture.coordinator.state.value)
            assertEquals(CaptureStopReason.SCREEN_LOCKED, fixture.coordinator.lastStopReason.value)
            assertEquals(1, fixture.gateway.stopCount)
        }

    @Test
    fun `active 中の開始要求は permission token を gateway へ渡さない`() =
        runTest {
            val fixture = fixture(backgroundScope)
            fixture.coordinator.prepare(CaptureMode.MANUAL)
            fixture.coordinator.start(FakePermissionToken)

            assertNull(fixture.coordinator.start(FakePermissionToken))
            assertEquals(1, fixture.gateway.startCount)
        }

    private fun fixture(scope: kotlinx.coroutines.CoroutineScope): Fixture {
        val gateway = FakeCaptureGateway()
        val lifecycle = RecordingLifecycle()
        val overlay = RecordingOverlay()
        return Fixture(
            coordinator = CaptureSessionCoordinator(gateway, lifecycle, overlay, scope),
            gateway = gateway,
            lifecycle = lifecycle,
            overlay = overlay,
        )
    }

    private data class Fixture(
        val coordinator: CaptureSessionCoordinator,
        val gateway: FakeCaptureGateway,
        val lifecycle: RecordingLifecycle,
        val overlay: RecordingOverlay,
    )

    private class FakeCaptureGateway : CaptureGateway {
        private val mutableEvents = MutableSharedFlow<CaptureGatewayEvent>(replay = 1)
        override val events: SharedFlow<CaptureGatewayEvent> = mutableEvents
        var startCount = 0
        var stopCount = 0

        override fun start(permissionToken: CapturePermissionToken): CaptureGatewayStartResult {
            startCount++
            return CaptureGatewayStartResult.Started(PORTRAIT)
        }

        override fun stop() {
            stopCount++
        }

        override fun latestFrame(): CapturedFrame? = null

        fun emit(event: CaptureGatewayEvent) {
            assertTrue(mutableEvents.tryEmit(event))
        }
    }

    private class RecordingLifecycle : CaptureSessionLifecycle {
        var activeCount = 0
        var idleCount = 0

        override fun onSessionActive() {
            activeCount++
        }

        override fun onSessionIdle() {
            idleCount++
        }
    }

    private class RecordingOverlay : CaptureOverlayGateway {
        val shown = mutableListOf<CaptureOverlayState>()
        var removed = false

        override fun show(
            state: CaptureOverlayState,
            savedCount: Int,
        ) {
            shown += state
        }

        override fun update(
            state: CaptureOverlayState,
            savedCount: Int,
        ) = Unit

        override fun hideForCapture() = Unit

        override fun restoreAfterCapture() = Unit

        override fun remove() {
            removed = true
        }
    }

    private data object FakePermissionToken : CapturePermissionToken

    private companion object {
        val PORTRAIT = CaptureSize(width = 1080, height = 1920)
        val LANDSCAPE = CaptureSize(width = 1920, height = 1080)
    }
}
