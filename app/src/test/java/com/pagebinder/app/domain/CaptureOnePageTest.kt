package com.pagebinder.app.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureOnePageTest {
    @Test
    fun `a second tap while capture is running saves only one page`() =
        runTest {
            val enteredWait = CompletableDeferred<Unit>()
            val releaseWait = CompletableDeferred<Unit>()
            val repository = InMemoryPages()
            val capture =
                CaptureOnePage(
                    captureGateway = FrameGateway(),
                    overlayGateway = RecordingOverlay(),
                    imageStore = RecordingStore(),
                    pageRepository = repository,
                    ocrQueue = queue(),
                    waitForStableFrame = {
                        enteredWait.complete(Unit)
                        releaseWait.await()
                    },
                )
            val first = async { capture.capture(PROJECT_ID) }
            enteredWait.await()

            assertEquals(CapturePageResult.IgnoredAlreadyCapturing, capture.capture(PROJECT_ID))
            releaseWait.complete(Unit)
            assertTrue(first.await() is CapturePageResult.Saved)
            assertEquals(1, repository.pages.size)
        }

    @Test
    fun `database failure rolls back the newly published image and restores overlay`() =
        runTest {
            val overlay = RecordingOverlay()
            val store = RecordingStore()
            val capture =
                CaptureOnePage(
                    captureGateway = FrameGateway(),
                    overlayGateway = overlay,
                    imageStore = store,
                    pageRepository = InMemoryPages(failInsert = true),
                    ocrQueue = queue(),
                    waitForStableFrame = {},
                )

            assertEquals(CapturePageResult.Failed(CapturePageFailure.SAVE_FAILED), capture.capture(PROJECT_ID))
            assertEquals(1, store.rollbackCount)
            assertEquals(listOf("hide", "restore"), overlay.calls)
        }

    @Test
    fun `OCR registration failure rolls back both the page record and image`() =
        runTest {
            val repository = InMemoryPages()
            val store = RecordingStore()
            val capture =
                CaptureOnePage(
                    captureGateway = FrameGateway(),
                    overlayGateway = RecordingOverlay(),
                    imageStore = store,
                    pageRepository = repository,
                    ocrQueue = failingQueue(),
                    waitForStableFrame = {},
                )

            assertEquals(CapturePageResult.Failed(CapturePageFailure.SAVE_FAILED), capture.capture(PROJECT_ID))
            assertEquals(1, store.rollbackCount)
            assertTrue(repository.pages.isEmpty())
        }

    @Test
    fun `black capture is isolated with failed OCR state and is not registered for OCR`() =
        runTest {
            val repository = InMemoryPages()
            val capture =
                CaptureOnePage(
                    captureGateway = FrameGateway(),
                    overlayGateway = RecordingOverlay(),
                    imageStore = RecordingStore(PageQualityState.BLACK),
                    pageRepository = repository,
                    ocrQueue = failingQueue(),
                    waitForStableFrame = {},
                )

            val result = capture.capture(PROJECT_ID)

            assertTrue(result is CapturePageResult.Isolated)
            assertEquals(PageOcrState.FAILED, repository.pages.single().ocrState)
        }

    private fun queue(): OcrQueue =
        OcrQueue(
            repository =
                object : OcrJobRepository {
                    override suspend fun markPending(
                        pageId: UUID,
                        expectedStates: Set<OcrState>,
                    ) = true

                    override suspend fun markProjectPending(
                        projectId: UUID,
                        expectedStates: Set<OcrState>,
                    ) = 0

                    override suspend fun claimNextPending(): OcrPage? = null

                    override suspend fun recoverInterrupted() = 0

                    override suspend fun storeSuccess(
                        pageId: UUID,
                        result: StoredOcrResult,
                    ) = true

                    override suspend fun markFailed(pageId: UUID) = true

                    override suspend fun returnToPending(pageId: UUID) = true
                },
            scheduler = OcrQueueScheduler {},
        )

    private fun failingQueue(): OcrQueue =
        OcrQueue(
            repository =
                object : OcrJobRepository {
                    override suspend fun markPending(
                        pageId: UUID,
                        expectedStates: Set<OcrState>,
                    ): Boolean = error("queue unavailable")

                    override suspend fun markProjectPending(
                        projectId: UUID,
                        expectedStates: Set<OcrState>,
                    ) = 0

                    override suspend fun claimNextPending(): OcrPage? = null

                    override suspend fun recoverInterrupted() = 0

                    override suspend fun storeSuccess(
                        pageId: UUID,
                        result: StoredOcrResult,
                    ) = true

                    override suspend fun markFailed(pageId: UUID) = true

                    override suspend fun returnToPending(pageId: UUID) = true
                },
            scheduler = OcrQueueScheduler {},
        )

    private class FrameGateway : CaptureGateway {
        override val events = kotlinx.coroutines.flow.emptyFlow<CaptureGatewayEvent>()

        override fun start(permissionToken: CapturePermissionToken): CaptureGatewayStartResult = error("unused")

        override fun stop() = Unit

        override fun latestFrame() = CapturedFrame(1, 1, intArrayOf(0xff000000.toInt()))
    }

    private class RecordingOverlay : CaptureOverlayGateway {
        val calls = mutableListOf<String>()

        override fun show(
            state: CaptureOverlayState,
            savedCount: Int,
        ) = Unit

        override fun update(
            state: CaptureOverlayState,
            savedCount: Int,
        ) = Unit

        override fun hideForCapture() {
            calls += "hide"
        }

        override fun restoreAfterCapture() {
            calls += "restore"
        }

        override fun remove() = Unit
    }

    private class RecordingStore(
        private val qualityState: PageQualityState = PageQualityState.NORMAL,
    ) : CaptureImageStore {
        var rollbackCount = 0

        override fun saveAtomically(
            projectId: UUID,
            pageId: UUID,
            frame: CapturedFrame,
        ) = StoredCaptureImage(
            relativePath = "projects/$projectId/images/$pageId.webp",
            width = 1,
            height = 1,
            contentHash = "content",
            perceptualHash = "0000000000000000",
            qualityState = qualityState,
            rollback = { rollbackCount++ },
        )
    }

    private class InMemoryPages(
        private val failInsert: Boolean = false,
    ) : PageRepository {
        val pages = mutableListOf<Page>()

        override suspend fun insert(page: Page) {
            if (failInsert) error("database failed")
            pages += page
        }

        override suspend fun rollbackCaptureInsert(
            projectId: UUID,
            pageId: UUID,
        ) {
            pages.removeAll { it.projectId == projectId && it.id == pageId }
        }

        override suspend fun findById(id: UUID) = pages.find { it.id == id }

        override suspend fun findByProject(projectId: UUID) = pages.filter { it.projectId == projectId }

        override suspend fun reorder(
            projectId: UUID,
            orderedPageIds: List<UUID>,
        ) = Unit

        override suspend fun delete(
            projectId: UUID,
            pageIds: Set<UUID>,
        ) = Unit

        override suspend fun deleteResolvingDuplicates(
            projectId: UUID,
            pageIds: Set<UUID>,
            resolvedDuplicatePageIds: Set<UUID>,
        ) = Unit

        override suspend fun updateRotation(
            pageId: UUID,
            rotation: Int,
        ) = Unit

        override suspend fun updateCrop(
            pageId: UUID,
            crop: PageCrop,
        ) = Unit

        override suspend fun updatePageEdit(
            pageId: UUID,
            rotation: Int,
            crop: PageCrop,
            cropScope: PageCropScope,
        ) = 0

        override suspend fun undoLastEdit() = false
    }

    private companion object {
        val PROJECT_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
