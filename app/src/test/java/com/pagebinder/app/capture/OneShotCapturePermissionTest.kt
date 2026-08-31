package com.pagebinder.app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OneShotCapturePermissionTest {
    @Test
    fun `許可トークンの値は一回だけ取り出せる`() {
        val permission = OneShotCapturePermission("approved-once")

        assertEquals("approved-once", permission.take())
        assertNull(permission.take())
    }
}
