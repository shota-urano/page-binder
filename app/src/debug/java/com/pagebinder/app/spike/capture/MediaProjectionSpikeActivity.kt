package com.pagebinder.app.spike.capture

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * Debug-only Phase 0 entry point.
 *
 * A new consent Intent is created for every Activity instance. The granted result is handed once
 * to the foreground service; neither it nor the resulting MediaProjection is retained for reuse.
 */
class MediaProjectionSpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        val consentLauncher =
            registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode != Activity.RESULT_OK || result.data == null) {
                    MediaProjectionSpikeRecord(this).finishWithFailure("CONSENT_DENIED")
                    finish()
                    return@registerForActivityResult
                }

                MediaProjectionSpikeRecord(this).resetWithConsentGranted()
                val serviceIntent =
                    Intent(this, MediaProjectionSpikeService::class.java)
                        .putExtra(MediaProjectionSpikeService.EXTRA_RESULT_CODE, result.resultCode)
                        .putExtra(MediaProjectionSpikeService.EXTRA_RESULT_DATA, result.data)
                ContextCompat.startForegroundService(this, serviceIntent)
                finish()
            }

        consentLauncher.launch(projectionManager.createScreenCaptureIntent())
    }
}
