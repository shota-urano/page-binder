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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
        val pickerResult = createDriveDocument(instrumentation, targetContext, fileName)
        val selectedUri = requireNotNull(pickerResult.uri)
        val pickerElapsedMs = elapsedMillis(pickerStartedAt)
        assertEquals(Activity.RESULT_OK, pickerResult.resultCode)
        assertEquals(DRIVE_AUTHORITY, selectedUri.authority)

        val writeStartedAt = System.nanoTime()
        val successfulWrite = writeToProvider(targetContext, selectedUri, sourceBytes)
        val writeElapsedMs = elapsedMillis(writeStartedAt)
        assertTrue("Expected the selected Drive write to complete", successfulWrite is ProviderWriteResult.Completed)

        val readBackStartedAt = System.nanoTime()
        val readBack = readWithRetry(targetContext, selectedUri)
        val readBackElapsedMs = elapsedMillis(readBackStartedAt)
        assertEquals(sourceBytes.size, readBack.size)
        assertEquals(sourceSha256, sha256(readBack))

        openPickerRootsWithRetry(
            instrumentation,
            targetContext,
            "pagebinder-saf-cancel-${System.currentTimeMillis()}.bin",
        )
        val cancelledResult = cancelPicker(instrumentation)
        assertEquals(Activity.RESULT_CANCELED, cancelledResult.resultCode)
        assertNull(cancelledResult.uri)

        val ungrantedUriError = assertUngrantedDriveDocumentIsRejected(targetContext)

        val failingPickerResult =
            createDriveDocument(
                instrumentation,
                targetContext,
                "pagebinder-saf-failure-${System.currentTimeMillis()}.bin",
            )
        val failingSelectedUri = requireNotNull(failingPickerResult.uri)
        assertEquals(Activity.RESULT_OK, failingPickerResult.resultCode)
        assertEquals(DRIVE_AUTHORITY, failingSelectedUri.authority)

        val selectedUriWriteResult =
            writeToProvider(targetContext, failingSelectedUri, sourceBytes) { providerStream ->
                FailAfterBytesOutputStream(providerStream, FAIL_AFTER_BYTES)
            }
        assertTrue(
            "A mid-stream error for a selected Drive URI must not be treated as complete",
            selectedUriWriteResult is ProviderWriteResult.Failed,
        )
        val selectedUriWriteError = (selectedUriWriteResult as ProviderWriteResult.Failed).error
        assertEquals(IOException::class.java, selectedUriWriteError::class.java)
        assertEquals(MID_STREAM_FAILURE_MESSAGE, selectedUriWriteError.message)

        val incompleteReadBack = readWithRetry(targetContext, failingSelectedUri)
        assertEquals(FAIL_AFTER_BYTES, incompleteReadBack.size)
        assertFalse(
            "A selected Drive URI containing only a prefix must not satisfy completion",
            sourceBytes.contentEquals(incompleteReadBack),
        )

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
                appendLine("ungranted_drive_document_write_error=${ungrantedUriError::class.java.name}")
                appendLine("selected_drive_write_error=${selectedUriWriteError::class.java.name}")
                appendLine("selected_drive_incomplete_bytes=${incompleteReadBack.size}")
                appendLine("selected_drive_write_complete=false")
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
        // awaitResult synchronizes this transition; an additional idle wait can outlive the temporary URI grant.
        clickWhenReady(
            instrumentation,
            resourceId = "android:id/button1",
            waitForIdleAfterClick = false,
        )
    }

    private fun createDriveDocument(
        instrumentation: Instrumentation,
        context: Context,
        fileName: String,
    ): SafPickerSpikeActivity.PickerResult {
        openPickerRootsWithRetry(instrumentation, context, fileName)
        selectDriveAndSave(instrumentation)
        return requireNotNull(SafPickerSpikeActivity.awaitResult(PICKER_TIMEOUT_SECONDS))
    }

    private fun openPickerRootsWithRetry(
        instrumentation: Instrumentation,
        context: Context,
        fileName: String,
    ) {
        var firstFailure: AssertionError? = null
        repeat(PICKER_LAUNCH_ATTEMPTS) { attempt ->
            launchPicker(context, fileName)
            try {
                clickWhenReady(instrumentation, description = SHOW_ROOTS_DESCRIPTION)
                return
            } catch (failure: AssertionError) {
                if (attempt == PICKER_LAUNCH_ATTEMPTS - 1) {
                    firstFailure?.let(failure::addSuppressed)
                    throw failure
                }
                firstFailure = failure
                cancelPicker(instrumentation)
            }
        }
    }

    private fun cancelPicker(instrumentation: Instrumentation): SafPickerSpikeActivity.PickerResult {
        repeat(MAX_CANCEL_BACK_PRESSES) {
            assertTrue(
                "SAF picker did not handle the Back action",
                instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK),
            )
            SafPickerSpikeActivity.awaitResult(CANCEL_RESULT_POLL_SECONDS)?.let { result ->
                waitForPickerDismissed(instrumentation)
                return result
            }
        }
        throw AssertionError("SAF picker did not return a cancelled result")
    }

    private fun waitForPickerDismissed(instrumentation: Instrumentation) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(UI_TIMEOUT_SECONDS)
        do {
            waitForPickerIdle(instrumentation)
            val activePackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
            if (activePackage != null && activePackage != DOCUMENTS_UI_PACKAGE) {
                Thread.sleep(PICKER_DISMISS_STABILITY_MILLIS)
                waitForPickerIdle(instrumentation)
                val stablePackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
                if (stablePackage != null && stablePackage != DOCUMENTS_UI_PACKAGE) return
            }
            Thread.sleep(UI_POLL_MILLIS)
        } while (System.nanoTime() < deadline)
        throw AssertionError("Timed out waiting for SAF picker dismissal")
    }

    private fun waitForNode(
        instrumentation: Instrumentation,
        text: String? = null,
        textPrefix: String? = null,
        description: String? = null,
        resourceId: String? = null,
        deadlineNanos: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(UI_TIMEOUT_SECONDS),
    ): AccessibilityNodeInfo {
        do {
            waitForPickerIdle(instrumentation)
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
        } while (System.nanoTime() < deadlineNanos)
        throw AssertionError(
            "Timed out waiting for SAF picker node " +
                "(text=$text, textPrefix=$textPrefix, description=$description, resourceId=$resourceId)",
        )
    }

    private fun clickWhenReady(
        instrumentation: Instrumentation,
        text: String? = null,
        description: String? = null,
        resourceId: String? = null,
        waitForIdleAfterClick: Boolean = true,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(UI_TIMEOUT_SECONDS)
        do {
            if (
                click(
                    waitForNode(
                        instrumentation,
                        text = text,
                        description = description,
                        resourceId = resourceId,
                        deadlineNanos = deadline,
                    ),
                )
            ) {
                if (waitForIdleAfterClick) waitForPickerIdle(instrumentation)
                return
            }
            Thread.sleep(UI_POLL_MILLIS)
        } while (System.nanoTime() < deadline)
        throw AssertionError("SAF picker node was not clickable")
    }

    private fun waitForPickerIdle(instrumentation: Instrumentation) {
        try {
            instrumentation.uiAutomation.waitForIdle(UI_IDLE_MILLIS, UI_IDLE_TIMEOUT_MILLIS)
        } catch (_: TimeoutException) {
            // A busy provider may never become fully idle; node polling remains the source of truth.
        }
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

    private fun assertUngrantedDriveDocumentIsRejected(context: Context): SecurityException {
        val invalidDriveDocument =
            DocumentsContract.buildDocumentUri(
                DRIVE_AUTHORITY,
                "pagebinder-invalid-${System.currentTimeMillis()}",
            )
        return assertThrows(SecurityException::class.java) {
            context.contentResolver.openOutputStream(invalidDriveDocument, "w").use { output ->
                requireNotNull(output).write(1)
            }
        }
    }

    private fun writeToProvider(
        context: Context,
        uri: Uri,
        bytes: ByteArray,
        decorate: (OutputStream) -> OutputStream = { it },
    ): ProviderWriteResult =
        try {
            val providerStream =
                context.contentResolver.openOutputStream(uri, "w")
                    ?: throw FileNotFoundException("Provider returned no output stream")
            decorate(providerStream).use { output -> output.write(bytes) }
            ProviderWriteResult.Completed
        } catch (error: IOException) {
            ProviderWriteResult.Failed(error)
        }

    private sealed interface ProviderWriteResult {
        data object Completed : ProviderWriteResult

        data class Failed(
            val error: IOException,
        ) : ProviderWriteResult
    }

    private class FailAfterBytesOutputStream(
        private val delegate: OutputStream,
        private val byteLimit: Int,
    ) : OutputStream() {
        private var writtenBytes = 0

        override fun write(value: Int) {
            if (writtenBytes >= byteLimit) throw IOException(MID_STREAM_FAILURE_MESSAGE)
            delegate.write(value)
            writtenBytes++
        }

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            val writableBytes = minOf(length, byteLimit - writtenBytes)
            if (writableBytes > 0) {
                delegate.write(bytes, offset, writableBytes)
                writtenBytes += writableBytes
            }
            if (writableBytes < length) throw IOException(MID_STREAM_FAILURE_MESSAGE)
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()
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
        private const val DOCUMENTS_UI_PACKAGE = "com.google.android.documentsui"
        private const val DRIVE_ROOT_LABEL = "Drive"
        private const val DRIVE_HEADER_PREFIX = "Files from Drive"
        private const val MY_DRIVE_LABEL = "My Drive"
        private const val SHOW_ROOTS_DESCRIPTION = "Show roots"
        private const val PAYLOAD_BYTES = 256 * 1024
        private const val TEMP_EXPORT_NAME = "gph-1-completed-export.tmp"
        private const val METRICS_FILE = "gph-1-saf-drive-metrics.txt"
        private const val PICKER_TIMEOUT_SECONDS = 30L
        private const val PICKER_LAUNCH_ATTEMPTS = 2
        private const val CANCEL_RESULT_POLL_SECONDS = 2L
        private const val MAX_CANCEL_BACK_PRESSES = 3
        private const val UI_TIMEOUT_SECONDS = 60L
        private const val UI_IDLE_MILLIS = 500L
        private const val UI_IDLE_TIMEOUT_MILLIS = 3_000L
        private const val UI_POLL_MILLIS = 250L
        private const val PICKER_DISMISS_STABILITY_MILLIS = 500L
        private const val PROVIDER_READ_ATTEMPTS = 8
        private const val PROVIDER_RETRY_MILLIS = 500L
        private const val FAIL_AFTER_BYTES = 8 * 1024
        private const val MID_STREAM_FAILURE_MESSAGE = "controlled selected-Drive write failure"
    }
}
