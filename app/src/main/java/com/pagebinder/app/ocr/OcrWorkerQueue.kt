package com.pagebinder.app.ocr

import android.content.Context
import android.os.PowerManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.pagebinder.app.domain.OcrExecutionPolicy
import com.pagebinder.app.domain.OcrJobRunner
import com.pagebinder.app.domain.OcrQueueScheduler
import com.pagebinder.app.domain.OcrRunResult
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WorkManagerOcrQueueScheduler(
    private val context: Context,
) : OcrQueueScheduler {
    override fun wake() {
        val request =
            OneTimeWorkRequestBuilder<OcrWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR,
                    OcrWorker.MIN_BACKOFF_SECONDS,
                    TimeUnit.SECONDS,
                ).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            OcrWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun onCaptureStarted() {
        CapturePriorityGate.isCaptureActive = true
        WorkManager.getInstance(context).cancelUniqueWork(OcrWorker.UNIQUE_WORK_NAME)
    }

    fun onCaptureStopped() {
        CapturePriorityGate.isCaptureActive = false
        wake()
    }
}

internal object CapturePriorityGate {
    private val active = AtomicBoolean(false)
    var isCaptureActive: Boolean
        get() = active.get()
        set(value) {
            active.set(value)
        }
}

internal class AndroidOcrExecutionPolicy(
    context: Context,
) : OcrExecutionPolicy {
    private val powerManager = context.getSystemService(PowerManager::class.java)

    override fun canRun(): Boolean =
        !CapturePriorityGate.isCaptureActive &&
            (
                android.os.Build.VERSION.SDK_INT < 29 ||
                    powerManager.currentThermalStatus < PowerManager.THERMAL_STATUS_SEVERE
            )
}

class OcrWorker(
    appContext: Context,
    workerParameters: WorkerParameters,
) : CoroutineWorker(appContext, workerParameters) {
    override suspend fun doWork(): Result {
        val application =
            applicationContext as? OcrWorkerDependencies
                ?: return Result.failure()
        return try {
            when (application.ocrJobRunner.drain()) {
                OcrRunResult.QueueEmpty -> Result.success()
                OcrRunResult.Deferred -> Result.retry()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "pagebinder-ocr-queue"
        const val MIN_BACKOFF_SECONDS = 10L
    }
}

interface OcrWorkerDependencies {
    val ocrJobRunner: OcrJobRunner
}

internal fun safeOcrImageFile(
    filesDir: File,
    relativePath: String,
): File {
    require(!File(relativePath).isAbsolute) { "OCR image path must be relative" }
    val root = filesDir.canonicalFile
    val image = File(root, relativePath).canonicalFile
    require(image.path.startsWith(root.path + File.separator)) { "OCR image path escapes app storage" }
    return image
}
