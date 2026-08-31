package com.pagebinder.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID

/** The completed, app-private original image.  [rollback] is only used for failed captures. */
data class StoredCaptureImage(
    val relativePath: String,
    val width: Int,
    val height: Int,
    val contentHash: String,
    val perceptualHash: String,
    val qualityState: PageQualityState = PageQualityState.NORMAL,
    val rollback: () -> Unit,
)

/** Keeps bitmap encoding and app-private atomic file replacement outside domain code. */
fun interface CaptureImageStore {
    fun saveAtomically(
        projectId: UUID,
        pageId: UUID,
        frame: CapturedFrame,
    ): StoredCaptureImage
}

sealed interface CapturePageResult {
    data class Saved(val page: Page) : CapturePageResult

    /** A black/protected frame is retained for diagnosis but excluded from normal pages and OCR. */
    data class Isolated(val page: Page) : CapturePageResult

    /** A press while another capture is running is intentionally coalesced. */
    data object IgnoredAlreadyCapturing : CapturePageResult

    data class Failed(val reason: CapturePageFailure) : CapturePageResult
}

enum class CapturePageFailure {
    NO_FRAME,
    SAVE_FAILED,
    BLACK_SCREEN,
    ROLLBACK_FAILED,
}

/**
 * The one-page transaction defined in docs/specs/05-manual-capture.md §3.1.
 *
 * The mutex deliberately uses tryLock: a second floating-button tap while a capture is in flight
 * cannot enqueue a second snapshot of the same rendered page.  Files are atomically published by
 * [CaptureImageStore]; any later persistence failure rolls that new file back.
 */
class CaptureOnePage(
    private val captureGateway: CaptureGateway,
    private val overlayGateway: CaptureOverlayGateway,
    private val imageStore: CaptureImageStore,
    private val pageRepository: PageRepository,
    private val ocrQueue: OcrQueue,
    private val now: () -> Instant = Instant::now,
    private val waitForStableFrame: suspend () -> Unit = { delay(OVERLAY_SETTLE_DELAY_MILLIS) },
) {
    private val captureMutex = Mutex()

    suspend fun capture(projectId: UUID): CapturePageResult {
        if (!captureMutex.tryLock()) return CapturePageResult.IgnoredAlreadyCapturing

        var storedImage: StoredCaptureImage? = null
        var insertedPage: Page? = null
        try {
            // 2–4: remove the overlay, wait for WindowManager to draw, then read the latest frame.
            overlayGateway.hideForCapture()
            waitForStableFrame()
            val frame = captureGateway.latestFrame() ?: return CapturePageResult.Failed(CapturePageFailure.NO_FRAME)

            // 5–6: frame orientation is normalized by CaptureImageStore before an atomic publish.
            // Allocate the id before any side effect, and retain the complete Page before insert.
            // This leaves no cancellation window in which a committed DB row lacks a rollback target.
            val pageId = UUID.randomUUID()
            storedImage = imageStore.saveAtomically(projectId, pageId, frame)

            // 7: Page data is inserted before quality annotation and OCR registration.
            val sequence = pageRepository.findByProject(projectId).size + 1
            val page =
                Page(
                    id = pageId,
                    projectId = projectId,
                    sequence = sequence,
                    originalImagePath = storedImage.relativePath,
                    width = storedImage.width,
                    height = storedImage.height,
                    rotation = 0,
                    crop = PageCrop(),
                    capturedAt = now(),
                    contentHash = storedImage.contentHash,
                    perceptualHash = storedImage.perceptualHash,
                    qualityState = qualityState(projectId, storedImage),
                    ocrState =
                        if (storedImage.qualityState == PageQualityState.BLACK) {
                            PageOcrState.FAILED
                        } else {
                            PageOcrState.PENDING
                        },
                )
            insertedPage = page
            pageRepository.insert(page)

            // 8–9: quality is represented on the stored page, then OCR receives the page id.
            if (page.qualityState == PageQualityState.BLACK) return CapturePageResult.Isolated(page)
            ocrQueue.enqueue(pageId)
            return CapturePageResult.Saved(page)
        } catch (failure: Exception) {
            val rollbackFailure =
                withContext(NonCancellable) {
                    var firstFailure: Exception? = null
                    insertedPage?.let { page ->
                        try {
                            pageRepository.rollbackCaptureInsert(projectId, page.id)
                        } catch (rollbackError: Exception) {
                            firstFailure = rollbackError
                        }
                    }
                    // A failed DB compensation leaves the row pointing to this image. Keeping the
                    // image is recoverable; deleting it would turn that into an unrecoverable row.
                    if (firstFailure == null) {
                        try {
                            storedImage?.rollback?.invoke()
                        } catch (rollbackError: Exception) {
                            firstFailure = rollbackError
                        }
                    }
                    firstFailure
                }
            if (rollbackFailure != null) return CapturePageResult.Failed(CapturePageFailure.ROLLBACK_FAILED)
            if (failure is CancellationException) throw failure
            return CapturePageResult.Failed(CapturePageFailure.SAVE_FAILED)
        } finally {
            // 10: no return path can leave the floating controls hidden.
            overlayGateway.restoreAfterCapture()
            captureMutex.unlock()
        }
    }

    private suspend fun qualityState(
        projectId: UUID,
        image: StoredCaptureImage,
    ): PageQualityState =
        if (image.qualityState != PageQualityState.NORMAL) {
            image.qualityState
        } else if (
            pageRepository.findByProject(projectId).any { existing ->
                isNearDuplicateHash(image.perceptualHash, existing.perceptualHash)
            }
        ) {
            PageQualityState.DUPLICATE
        } else {
            PageQualityState.NORMAL
        }

    private companion object {
        const val OVERLAY_SETTLE_DELAY_MILLIS = 150L
    }
}
