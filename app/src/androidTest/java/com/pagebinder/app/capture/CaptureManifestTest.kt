package com.pagebinder.app.capture

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CaptureManifestTest {
    @Test
    fun captureServiceAndRequiredForegroundPermissionsAreDeclared() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val packageInfo =
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            )
        val permissions = packageInfo.requestedPermissions.orEmpty().toSet()

        assertTrue(Manifest.permission.FOREGROUND_SERVICE in permissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION in permissions)

        @Suppress("DEPRECATION")
        val serviceInfo =
            context.packageManager.getServiceInfo(
                ComponentName(context, CaptureForegroundService::class.java),
                0,
            )
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            serviceInfo.foregroundServiceType,
        )
    }
}
