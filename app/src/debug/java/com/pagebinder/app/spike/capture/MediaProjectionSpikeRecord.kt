package com.pagebinder.app.spike.capture

import android.content.Context
import java.io.File
import java.time.Instant

internal class MediaProjectionSpikeRecord(context: Context) {
    private val directory = File(context.filesDir, DIRECTORY)
    private val record = File(directory, RECORD_FILE)

    @Synchronized
    fun resetWithConsentGranted() {
        directory.mkdirs()
        record.writeText(
            buildString {
                appendLine("PageBinder Phase 0 MediaProjection verification")
                appendLine("timestamp=${Instant.now()}")
                appendLine("deviceSdk=${android.os.Build.VERSION.SDK_INT}")
                appendLine("CONSENT_GRANTED")
            },
        )
    }

    @Synchronized
    fun append(event: String) {
        directory.mkdirs()
        record.appendText("$event\n")
    }

    fun imageFile(): File {
        directory.mkdirs()
        return File(directory, IMAGE_FILE)
    }

    fun finishWithFailure(reason: String) {
        append("FAILED=$reason")
    }

    companion object {
        const val DIRECTORY = "phase0"
        const val RECORD_FILE = "media-projection-spike.txt"
        const val IMAGE_FILE = "media-projection-spike.png"
    }
}
