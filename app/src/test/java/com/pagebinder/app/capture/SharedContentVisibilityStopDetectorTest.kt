package com.pagebinder.app.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedContentVisibilityStopDetectorTest {
    @Test
    fun `開始直後の初回 false では停止しない`() {
        val detector = SharedContentVisibilityStopDetector()

        assertFalse(detector.shouldStop(isVisible = false))
    }

    @Test
    fun `一度 visible になった後の false で停止する`() {
        val detector = SharedContentVisibilityStopDetector()

        assertFalse(detector.shouldStop(isVisible = true))
        assertTrue(detector.shouldStop(isVisible = false))
    }
}
