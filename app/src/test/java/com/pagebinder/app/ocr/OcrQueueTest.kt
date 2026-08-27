package com.pagebinder.app.ocr

import androidx.work.ExistingWorkPolicy
import com.pagebinder.app.domain.CaptureSessionLifecycle
import com.pagebinder.app.domain.OcrCrop
import com.pagebinder.app.domain.OcrExecutionPolicy
import com.pagebinder.app.domain.OcrGateway
import com.pagebinder.app.domain.OcrImageSource
import com.pagebinder.app.domain.OcrJobRepository
import com.pagebinder.app.domain.OcrJobRunner
import com.pagebinder.app.domain.OcrOutput
import com.pagebinder.app.domain.OcrPage
import com.pagebinder.app.domain.OcrQueue
import com.pagebinder.app.domain.OcrQueueScheduler
import com.pagebinder.app.domain.OcrRecognitionException
import com.pagebinder.app.domain.OcrRunResult
import com.pagebinder.app.domain.OcrState
import com.pagebinder.app.domain.StoredOcrResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID

class OcrQueueTest {
    @Test
    fun `pending transitions through running to succeeded`() =
        runTest {
            val repository = FakeOcrJobRepository(page(OcrState.PENDING))
            val runner = runner(repository, successfulGateway())

            assertEquals(OcrRunResult.QueueEmpty, runner.drain())
            assertEquals(listOf(OcrState.RUNNING, OcrState.SUCCEEDED), repository.transitions)
            assertNotNull(repository.result)
        }

    @Test
    fun `recognition failure transitions running page to failed`() =
        runTest {
            val repository = FakeOcrJobRepository(page(OcrState.PENDING))
            val runner =
                runner(
                    repository,
                    gateway = OcrGateway { throw OcrRecognitionException("recognition failed") },
                )

            assertEquals(OcrRunResult.QueueEmpty, runner.drain())
            assertEquals(listOf(OcrState.RUNNING, OcrState.FAILED), repository.transitions)
        }

    @Test
    fun `stale page is made pending and rerun`() =
        runTest {
            val repository = FakeOcrJobRepository(page(OcrState.STALE))
            val scheduler = RecordingScheduler()
            val queue = OcrQueue(repository, scheduler)

            assertTrue(queue.enqueue(repository.onlyPageId))
            assertEquals(OcrState.PENDING, repository.state)
            assertEquals(OcrRunResult.QueueEmpty, runner(repository, successfulGateway()).drain())
            assertEquals(OcrState.SUCCEEDED, repository.state)
            assertEquals(1, scheduler.wakeCount)
        }

    @Test
    fun `failed page can be queued again`() =
        runTest {
            val repository = FakeOcrJobRepository(page(OcrState.FAILED))
            val scheduler = RecordingScheduler()

            assertTrue(OcrQueue(repository, scheduler).enqueue(repository.onlyPageId))
            assertEquals(OcrState.PENDING, repository.state)
        }

    @Test
    fun `bulk enqueue changes failed and stale pages only`() =
        runTest {
            val projectId = UUID.randomUUID()
            val repository =
                FakeOcrJobRepository(
                    page(OcrState.FAILED, projectId),
                    page(OcrState.STALE, projectId),
                    page(OcrState.SUCCEEDED, projectId),
                )
            val scheduler = RecordingScheduler()

            assertEquals(2, OcrQueue(repository, scheduler).enqueueProject(projectId))
            assertEquals(
                listOf(OcrState.PENDING, OcrState.PENDING, OcrState.SUCCEEDED),
                repository.states,
            )
            assertEquals(1, scheduler.wakeCount)
        }

    @Test
    fun `capture priority defers without claiming pending work`() =
        runTest {
            val repository = FakeOcrJobRepository(page(OcrState.PENDING))
            val runner =
                runner(
                    repository,
                    successfulGateway(),
                    executionPolicy = OcrExecutionPolicy { false },
                )

            assertEquals(OcrRunResult.Deferred, runner.drain())
            assertEquals(OcrState.PENDING, repository.state)
        }

    @Test
    fun `capture session lifecycle cancels running OCR and resumes it after capture`() =
        runTest {
            val policies = mutableListOf<ExistingWorkPolicy>()
            var cancellationCount = 0
            val scheduler =
                WorkManagerOcrQueueScheduler(
                    enqueueWork = policies::add,
                    cancelWork = { cancellationCount++ },
                )
            val lifecycle: CaptureSessionLifecycle = scheduler
            val repository = FakeOcrJobRepository(page(OcrState.PENDING))

            try {
                lifecycle.onSessionActive()

                assertEquals(
                    OcrRunResult.Deferred,
                    runner(
                        repository,
                        successfulGateway(),
                        executionPolicy = OcrExecutionPolicy { !CapturePriorityGate.isCaptureActive },
                    ).drain(),
                )
                assertEquals(OcrState.PENDING, repository.state)
                assertEquals(1, cancellationCount)

                lifecycle.onSessionIdle()

                assertEquals(listOf(ExistingWorkPolicy.APPEND_OR_REPLACE), policies)
            } finally {
                CapturePriorityGate.isCaptureActive = false
            }
        }

    @Test
    fun `wake racing with running worker uses policy that guarantees a successor`() {
        val policies = mutableListOf<ExistingWorkPolicy>()
        val scheduler =
            WorkManagerOcrQueueScheduler(
                enqueueWork = policies::add,
                cancelWork = {},
            )

        scheduler.wake()

        assertEquals(listOf(ExistingWorkPolicy.APPEND_OR_REPLACE), policies)
    }

    private fun runner(
        repository: FakeOcrJobRepository,
        gateway: OcrGateway = successfulGateway(),
        executionPolicy: OcrExecutionPolicy = OcrExecutionPolicy { true },
    ) = OcrJobRunner(
        repository = repository,
        gateway = gateway,
        imageSourceFactory = { OcrImageSource { ByteArrayInputStream(byteArrayOf(1)) } },
        executionPolicy = executionPolicy,
        now = { PROCESSED_AT },
    )

    private fun successfulGateway() =
        OcrGateway {
            OcrOutput(
                fullText = "recognized",
                blocksJson = "{\"blocks\":[]}",
                engineVersion = "test-engine",
                sourceImageHash = "hash",
            )
        }

    private fun page(
        state: OcrState,
        projectId: UUID = UUID.randomUUID(),
    ) = OcrPage(
        id = UUID.randomUUID(),
        projectId = projectId,
        sequence = 1,
        originalImagePath = "projects/project/images/page.webp",
        rotation = 0,
        crop = OcrCrop(),
        capturedAt = Instant.parse("2026-08-27T00:00:00Z"),
        ocrState = state,
    )

    private class RecordingScheduler : OcrQueueScheduler {
        var wakeCount = 0

        override fun wake() {
            wakeCount++
        }
    }

    private class FakeOcrJobRepository(
        vararg initialPages: OcrPage,
    ) : OcrJobRepository {
        private val pages = initialPages.associateBy(OcrPage::id).toMutableMap()
        val transitions = mutableListOf<OcrState>()
        var result: StoredOcrResult? = null

        val onlyPageId: UUID get() = pages.keys.single()
        val state: OcrState get() = pages.values.single().ocrState
        val states: List<OcrState> get() = pages.values.map(OcrPage::ocrState)

        override suspend fun markPending(
            pageId: UUID,
            expectedStates: Set<OcrState>,
        ): Boolean = transition(pageId, expectedStates, OcrState.PENDING)

        override suspend fun markProjectPending(
            projectId: UUID,
            expectedStates: Set<OcrState>,
        ): Int =
            pages.values
                .filter { it.projectId == projectId && it.ocrState in expectedStates }
                .count { transition(it.id, expectedStates, OcrState.PENDING) }

        override suspend fun claimNextPending(): OcrPage? {
            val page = pages.values.firstOrNull { it.ocrState == OcrState.PENDING } ?: return null
            transition(page.id, setOf(OcrState.PENDING), OcrState.RUNNING)
            return pages.getValue(page.id)
        }

        override suspend fun recoverInterrupted(): Int =
            pages.values
                .filter { it.ocrState == OcrState.RUNNING }
                .count { transition(it.id, setOf(OcrState.RUNNING), OcrState.PENDING) }

        override suspend fun storeSuccess(
            pageId: UUID,
            result: StoredOcrResult,
        ): Boolean {
            val changed = transition(pageId, setOf(OcrState.RUNNING), OcrState.SUCCEEDED)
            if (changed) this.result = result
            return changed
        }

        override suspend fun markFailed(pageId: UUID): Boolean =
            transition(pageId, setOf(OcrState.RUNNING), OcrState.FAILED)

        override suspend fun returnToPending(pageId: UUID): Boolean =
            transition(pageId, setOf(OcrState.RUNNING), OcrState.PENDING)

        private fun transition(
            pageId: UUID,
            expectedStates: Set<OcrState>,
            target: OcrState,
        ): Boolean {
            val page = pages[pageId] ?: return false
            if (page.ocrState !in expectedStates) return false
            pages[pageId] = page.copy(ocrState = target)
            transitions += target
            return true
        }
    }

    private companion object {
        val PROCESSED_AT: Instant = Instant.parse("2026-08-27T01:00:00Z")
    }
}
