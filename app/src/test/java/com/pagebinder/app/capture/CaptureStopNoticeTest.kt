package com.pagebinder.app.capture

import com.pagebinder.app.domain.CaptureStopReason
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureStopNoticeTest {
    @Test
    fun `単一アプリ共有の対象が不可視なら画面全体を選び直す案内を選ぶ`() {
        assertEquals(
            CaptureStopNotice.SELECT_ENTIRE_SCREEN,
            CaptureStopReason.SHARED_CONTENT_NOT_VISIBLE.stopNotice(),
        )
    }

    @Test
    fun `他の予期しない停止は汎用の停止通知を選ぶ`() {
        assertEquals(CaptureStopNotice.GENERIC, CaptureStopReason.OS_STOPPED.stopNotice())
    }
}
