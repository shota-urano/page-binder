package com.pagebinder.app.capture

import android.app.Activity
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidCaptureAuthorizationTest {
    @Test
    fun authorizationCanOnlyBeConsumedOnce() {
        val permissionData = Intent("test.permission.result")
        val authorization =
            AndroidCapturePermissionToken.fromPermissionResult(
                Activity.RESULT_OK,
                permissionData,
            ) as AndroidCapturePermissionToken

        val consumed = authorization.consume()

        assertEquals(Activity.RESULT_OK, consumed?.resultCode)
        assertSame(permissionData, consumed?.resultData)
        assertNull(authorization.consume())
    }
}
