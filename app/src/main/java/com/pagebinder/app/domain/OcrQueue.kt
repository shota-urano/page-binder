package com.pagebinder.app.domain

import java.time.Instant
import java.util.UUID

enum class OcrState(val serializedName: String) {
    PENDING("pending"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    STALE("stale"),
}

data class OcrPage(
    val id: UUID,
    val projectId: UUID,
    val sequence: Int,
    val originalImagePath: String,
    val rotation: Int,
    val crop: OcrCrop,
    val capturedAt: Instant,
    val ocrState: OcrState,
)

data class StoredOcrResult(
    val pageId: UUID,
    val fullText: String,
    val blocksJson: String,
    val editedText: String?,
    val engineVersion: String,
    val sourceImageHash: String,
    val processedAt: Instant,
)

/** Persistence boundary used by the serial OCR worker. Implementations own atomic state changes. */
interface OcrJobRepository {
    suspend fun markPending(
        pageId: UUID,
        expectedStates: Set<OcrState>,
    ): Boolean

    suspend fun markProjectPending(
        projectId: UUID,
        expectedStates: Set<OcrState>,
    ): Int

    /** Returns and atomically changes the oldest pending page to running. */
    suspend fun claimNextPending(): OcrPage?

    /** Changes jobs interrupted by process death or worker cancellation back to pending. */
    suspend fun recoverInterrupted(): Int

    suspend fun storeSuccess(
        pageId: UUID,
        result: StoredOcrResult,
    ): Boolean

    suspend fun markFailed(pageId: UUID): Boolean

    suspend fun returnToPending(pageId: UUID): Boolean
}

fun interface OcrQueueScheduler {
    fun wake()
}

/** Entry point used by capture, retry, bulk OCR, and application-start recovery. */
class OcrQueue(
    private val repository: OcrJobRepository,
    private val scheduler: OcrQueueScheduler,
) {
    suspend fun enqueue(pageId: UUID): Boolean {
        val queued =
            repository.markPending(
                pageId,
                setOf(OcrState.FAILED, OcrState.STALE, OcrState.SUCCEEDED),
            )
        scheduler.wake()
        return queued
    }

    suspend fun enqueueProject(projectId: UUID): Int {
        val queued =
            repository.markProjectPending(
                projectId,
                setOf(OcrState.FAILED, OcrState.STALE),
            )
        scheduler.wake()
        return queued
    }

    suspend fun resumeIncomplete(): Int {
        val recovered = repository.recoverInterrupted()
        scheduler.wake()
        return recovered
    }
}

fun interface OcrExecutionPolicy {
    fun canRun(): Boolean
}

sealed interface OcrRunResult {
    data object QueueEmpty : OcrRunResult

    data object Deferred : OcrRunResult
}

/** Framework-free worker logic, kept separately testable from WorkManager. */
class OcrJobRunner(
    private val repository: OcrJobRepository,
    private val gateway: OcrGateway,
    private val imageSourceFactory: (String) -> OcrImageSource,
    private val executionPolicy: OcrExecutionPolicy,
    private val now: () -> Instant = Instant::now,
) {
    suspend fun drain(): OcrRunResult {
        repository.recoverInterrupted()
        while (executionPolicy.canRun()) {
            val page = repository.claimNextPending() ?: return OcrRunResult.QueueEmpty
            try {
                val output =
                    gateway.recognize(
                        OcrInput(
                            image = imageSourceFactory(page.originalImagePath),
                            rotationDegrees = page.rotation,
                            crop = page.crop,
                        ),
                    )
                repository.storeSuccess(
                    page.id,
                    StoredOcrResult(
                        pageId = page.id,
                        fullText = output.fullText,
                        blocksJson = output.blocksJson,
                        editedText = null,
                        engineVersion = output.engineVersion,
                        sourceImageHash = output.sourceImageHash,
                        processedAt = now(),
                    ),
                )
            } catch (error: kotlinx.coroutines.CancellationException) {
                repository.returnToPending(page.id)
                throw error
            } catch (_: OcrInputException) {
                repository.markFailed(page.id)
            } catch (_: OcrRecognitionException) {
                repository.markFailed(page.id)
            }
        }
        return OcrRunResult.Deferred
    }
}
