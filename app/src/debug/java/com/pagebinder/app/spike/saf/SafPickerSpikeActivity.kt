package com.pagebinder.app.spike.saf

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class SafPickerSpikeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = MIME_TYPE
                putExtra(Intent.EXTRA_TITLE, intent.getStringExtra(EXTRA_FILE_NAME))
            },
            REQUEST_CREATE_DOCUMENT,
        )
    }

    @Deprecated("The spike deliberately exercises the platform activity result contract")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?,
    ) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CREATE_DOCUMENT) {
            results.offer(PickerResult(resultCode, data?.data))
            finish()
        }
    }

    data class PickerResult(
        val resultCode: Int,
        val uri: Uri?,
    )

    companion object {
        const val EXTRA_FILE_NAME = "file_name"
        private const val MIME_TYPE = "application/octet-stream"
        private const val REQUEST_CREATE_DOCUMENT = 7001
        private val results = ArrayBlockingQueue<PickerResult>(1)

        fun resetResult() {
            results.clear()
        }

        fun awaitResult(timeoutSeconds: Long): PickerResult? = results.poll(timeoutSeconds, TimeUnit.SECONDS)
    }
}
