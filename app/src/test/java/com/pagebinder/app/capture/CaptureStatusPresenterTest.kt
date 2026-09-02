package com.pagebinder.app.capture

import com.pagebinder.app.domain.CaptureOverlayGateway
import com.pagebinder.app.domain.CaptureOverlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 撮影状態がオーバーレイと常駐通知の両方へ届くこと（FR-AUTO-005 / docs/specs/06-auto-capture.md §3.4）。
 */
class CaptureStatusPresenterTest {
    private val overlay = RecordingOverlayGateway()
    private val posted = mutableListOf<Pair<CaptureOverlayState, Int>>()
    private val presenter = CaptureStatusPresenter(overlay) { state, savedCount -> posted += state to savedCount }

    @Test
    fun `連続撮影の開始と保存枚数の更新が両方へ届く`() {
        presenter.show(CaptureOverlayState.CONTINUOUS_ACTIVE, 0)
        presenter.update(CaptureOverlayState.CONTINUOUS_ACTIVE, 24)
        presenter.update(CaptureOverlayState.CONTINUOUS_PAUSED, 24)

        val expected =
            listOf(
                CaptureOverlayState.CONTINUOUS_ACTIVE to 0,
                CaptureOverlayState.CONTINUOUS_ACTIVE to 24,
                CaptureOverlayState.CONTINUOUS_PAUSED to 24,
            )
        assertEquals(expected, overlay.states)
        assertEquals(expected, posted)
    }

    @Test
    fun `撮影直前の一時非表示は通知へ届かない`() {
        presenter.show(CaptureOverlayState.MANUAL_ACTIVE, 0)
        posted.clear()

        presenter.hideForCapture()
        presenter.restoreAfterCapture()
        presenter.remove()

        assertTrue(posted.isEmpty())
        assertEquals(listOf(false, true), overlay.visibility)
        assertTrue(overlay.removed)
    }

    private class RecordingOverlayGateway : CaptureOverlayGateway {
        val states = mutableListOf<Pair<CaptureOverlayState, Int>>()
        val visibility = mutableListOf<Boolean>()
        var removed = false

        override fun show(
            state: CaptureOverlayState,
            savedCount: Int,
        ) {
            states += state to savedCount
        }

        override fun update(
            state: CaptureOverlayState,
            savedCount: Int,
        ) {
            states += state to savedCount
        }

        override fun hideForCapture() {
            visibility += false
        }

        override fun restoreAfterCapture() {
            visibility += true
        }

        override fun remove() {
            removed = true
        }
    }
}
