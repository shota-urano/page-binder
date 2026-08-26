package com.pagebinder.app.spike.saf

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SafGoogleDriveSpikeTest {
    @Test
    fun writesCompletedTempFileToSelectedDriveProviderAndVerifiesErrors() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext

        val fileName = "pagebinder-saf-spike-${System.currentTimeMillis()}.bin"
        val source = createCompletedTemporaryExport(targetContext)
        val sourceBytes = source.readBytes()
        val sourceSha256 = sha256(sourceBytes)

        val pickerStartedAt = System.nanoTime()
        launchPicker(targetContext, fileName)
        selectDriveAndSave(instrumentation)
        val pickerResult = requireNotNull(SafPickerSpikeActivity.awaitResult(PICKER_TIMEOUT_SECONDS))
        val selectedUri = requireNotNull(pickerResult.uri)
        val pickerElapsedMs = elapsedMillis(pickerStartedAt)
        assertEquals(Activity.RESULT_OK, pickerResult.resultCode)
        assertEquals(DRIVE_AUTHORITY, selectedUri.authority)

        val writeStartedAt = System.nanoTime()
        targetContext.contentResolver.openOutputStream(selectedUri, "w").use { output ->
            requireNotNull(output).write(sourceBytes)
        }
        val writeElapsedMs = elapsedMillis(writeStartedAt)

        val readBackStartedAt = System.nanoTime()
        val readBack = readWithRetry(targetContext, selectedUri)
        val readBackElapsedMs = elapsedMillis(readBackStartedAt)
        assertEquals(sourceBytes.size, readBack.size)
        assertEquals(sourceSha256, sha256(readBack))

        launchPicker(targetContext, "pagebinder-saf-cancel-${System.currentTimeMillis()}.bin")
        waitForNode(instrumentation, description = SHOW_ROOTS_DESCRIPTION)
        val cancelledResult = cancelPicker(instrumentation)
        assertEquals(Activity.RESULT_CANCELED, cancelledResult.resultCode)
        assertNull(cancelledResult.uri)

        val providerWriteError = captureInvalidDriveDocumentWriteError(targetContext)
        assertNotNull("Expected an invalid Drive document write to fail", providerWriteError)

        artifactFile(targetContext).writeText(
            buildString {
                appendLine("device=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                appendLine("api=${android.os.Build.VERSION.SDK_INT}")
                appendLine("drive_package=$DRIVE_PACKAGE")
                appendLine("drive_authority=$DRIVE_AUTHORITY")
                appendLine("source_bytes=${sourceBytes.size}")
                appendLine("source_sha256=$sourceSha256")
                appendLine("picker_elapsed_ms=$pickerElapsedMs")
                appendLine("write_elapsed_ms=$writeElapsedMs")
                appendLine("read_back_elapsed_ms=$readBackElapsedMs")
                appendLine("read_back_bytes=${readBack.size}")
                appendLine("read_back_sha256=${sha256(readBack)}")
                appendLine("picker_cancel_result=${cancelledResult.resultCode}")
                appendLine("invalid_drive_document_write_error=${providerWriteError!!::class.java.name}")
            },
        )
    }

    private fun createCompletedTemporaryExport(context: Context): File {
        val payload = ByteArray(PAYLOAD_BYTES) { index -> ((index * 31 + 17) and 0xff).toByte() }
        val output = File(context.cacheDir, TEMP_EXPORT_NAME)
        output.outputStream().use { it.write(payload) }
        assertEquals(payload.size.toLong(), output.length())
        return output
    }

    private fun launchPicker(
        context: Context,
        fileName: String,
    ) {
        SafPickerSpikeActivity.resetResult()
        context.startActivity(
            Intent(context, SafPickerSpikeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(SafPickerSpikeActivity.EXTRA_FILE_NAME, fileName)
            },
        )
    }

    private fun selectDriveAndSave(instrumentation: Instrumentation) {
        clickWhenReady(instrumentation, description = SHOW_ROOTS_DESCRIPTION)
        clickWhenReady(instrumentation, text = DRIVE_ROOT_LABEL, resourceId = "android:id/title")
        waitForNode(
            instrumentation,
            textPrefix = DRIVE_HEADER_PREFIX,
            resourceId = "com.google.android.documentsui:id/header_title",
        )
        clickWhenReady(instrumentation, text = MY_DRIVE_LABEL)
        waitForNode(
            instrumentation,
            text = MY_DRIVE_LABEL,
            resourceId = "com.google.android.documentsui:id/breadcrumb_text",
        )
        clickWhenReady(instrumentation, resourceId = "android:id/button1")
    }

    private fun cancelPicker(instrumentation: Instrumentation): SafPickerSpikeActivity.PickerResult {
        repeat(MAX_CANCEL_BACK_PRESSES) {
            assertTrue(
                "SAF picker did not handle the Back action",
                instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK),
            )
            SafPickerSpikeActivity.awaitResult(CANCEL_RESULT_POLL_SECONDS)?.let { return it }
        }
        throw AssertionError("SAF picker did not return a cancelled result")
    }

    private fun waitForNode(
        instrumentation: Instrumentation,
        text: String? = null,
        textPrefix: String? = null,
        description: String? = null,
        resourceId: String? = null,
    ): AccessibilityNodeInfo {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(UI_TIMEOUT_SECONDS)
        do {
            val root = instrumentation.uiAutomation.rootInActiveWindow
            val matches =
                when {
                    resourceId != null ->
                        root
                            ?.descendants()
                            .orEmpty()
                            .filter { it.viewIdResourceName == resourceId }
                    text != null -> root?.findAccessibilityNodeInfosByText(text).orEmpty()
                    description != null ->
                        root?.descendants().orEmpty()
                            .filter { it.contentDescription?.toString() == description }
                    else -> emptyList()
                }
            matches
                .firstOrNull {
                    it.isVisibleToUser &&
                        it.isEnabled &&
                        (text == null || it.text?.toString() == text) &&
                        (textPrefix == null || it.text?.toString()?.startsWith(textPrefix) == true) &&
                        (description == null || it.contentDescription?.toString() == description)
                }?.let { return it }
            Thread.sleep(UI_POLL_MILLIS)
        } while (System.nanoTime() < deadline)
        throw AssertionError("Timed out waiting for SAF picker node")
    }

    private fun clickWhenReady(
        instrumentation: Instrumentation,
        text: String? = null,
        description: String? = null,
        resourceId: String? = null,
    ) {
        repeat(UI_CLICK_ATTEMPTS) {
            if (click(waitForNode(instrumentation, text = text, description = description, resourceId = resourceId))) {
                return
            }
            Thread.sleep(UI_POLL_MILLIS)
        }
        throw AssertionError("SAF picker node was not clickable")
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) {
            clickable = clickable.parent
        }
        return clickable?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun AccessibilityNodeInfo.descendants(): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(this)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            result += current
            repeat(current.childCount) { index ->
                current.getChild(index)?.let(pending::addLast)
            }
        }
        return result
    }

    private fun readWithRetry(
        context: Context,
        uri: Uri,
    ): ByteArray = retryProviderRead { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }

    private fun <T : Any> retryProviderRead(read: () -> T?): T {
        var lastFailure: Throwable? = null
        repeat(PROVIDER_READ_ATTEMPTS) { attempt ->
            try {
                read()?.let { return it }
            } catch (failure: FileNotFoundException) {
                lastFailure = failure
            }
            Thread.sleep(PROVIDER_RETRY_MILLIS * (attempt + 1))
        }
        throw AssertionError("Drive provider did not expose the completed document", lastFailure)
    }

    private fun captureInvalidDriveDocumentWriteError(context: Context): Throwable? {
        val invalidDriveDocument =
            DocumentsContract.buildDocumentUri(
                DRIVE_AUTHORITY,
                "pagebinder-invalid-${System.currentTimeMillis()}",
            )
        return runCatching {
            context.contentResolver.openOutputStream(invalidDriveDocument, "w").use { output ->
                requireNotNull(output).write(1)
            }
        }.exceptionOrNull()
    }

    private fun artifactFile(context: Context): File {
        val directory = requireNotNull(context.getExternalFilesDir(null)).resolve("spikes")
        check(directory.mkdirs() || directory.isDirectory)
        return directory.resolve(METRICS_FILE)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun elapsedMillis(startedAt: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    companion object {
        private const val DRIVE_PACKAGE = "com.google.android.apps.docs"
        private const val DRIVE_AUTHORITY = "com.google.android.apps.docs.storage"
        private const val DRIVE_ROOT_LABEL = "Drive"
        private const val DRIVE_HEADER_PREFIX = "Files from Drive"
        private const val MY_DRIVE_LABEL = "My Drive"
        private const val SHOW_ROOTS_DESCRIPTION = "Show roots"
        private const val PAYLOAD_BYTES = 256 * 1024
        private const val TEMP_EXPORT_NAME = "gph-1-completed-export.tmp"
        private const val METRICS_FILE = "gph-1-saf-drive-metrics.txt"
        private const val PICKER_TIMEOUT_SECONDS = 30L
        private const val CANCEL_RESULT_POLL_SECONDS = 2L
        private const val MAX_CANCEL_BACK_PRESSES = 3
        private const val UI_TIMEOUT_SECONDS = 20L
        private const val UI_POLL_MILLIS = 250L
        private const val UI_CLICK_ATTEMPTS = 5
        private const val PROVIDER_READ_ATTEMPTS = 8
        private const val PROVIDER_RETRY_MILLIS = 500L
    }
}
