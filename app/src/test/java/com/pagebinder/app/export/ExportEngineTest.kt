package com.pagebinder.app.export

import com.pagebinder.app.domain.CompletedExportSource
import com.pagebinder.app.domain.ExportDestination
import com.pagebinder.app.domain.ExportRecord
import com.pagebinder.app.domain.ExportRecordRepository
import com.pagebinder.app.domain.ExportState
import com.pagebinder.app.domain.ExportStorageGateway
import com.pagebinder.app.domain.ExportStorageResult
import com.pagebinder.app.domain.ExportType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ExportEngineTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val recordId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val projectId = UUID.fromString("20000000-0000-0000-0000-000000000002")
    private val now = Instant.parse("2026-08-26T04:05:06Z")

    @Test
    fun `cancellation marks production record failed and removes partial cache file`() =
        runTest {
            val cache = temporaryFolder.newFolder("exports-cache")
            val repository = InMemoryExportRecordRepository()
            val generationStarted = CompletableDeferred<Unit>()
            val engine =
                engine(
                    cache = cache,
                    repository = repository,
                    generator =
                        ExportArtifactGenerator { _, outputFile, _ ->
                            outputFile.writeText("partial export")
                            generationStarted.complete(Unit)
                            awaitCancellation()
                        },
                )

            val collection = launch { engine.export(markdownRequest()).collect {} }
            generationStarted.await()
            collection.cancelAndJoin()

            val record = repository.findById(recordId)
            assertEquals(ExportState.FAILED, record?.state)
            assertEquals("cancelled", record?.errorCode)
            assertEquals(now, record?.completedAt)
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `markdown is generated written and reported through production engine`() =
        runTest {
            val cache = temporaryFolder.newFolder("exports-cache-success")
            val repository = InMemoryExportRecordRepository()
            val storage = RecordingStorageGateway()
            val engine =
                ExportEngine(
                    exportsCacheDirectory = cache,
                    recordCoordinator = coordinator(repository),
                    storageGateway = storage,
                    artifactGenerator = StandardExportArtifactGenerator(Dispatchers.Unconfined),
                )

            val events = engine.export(markdownRequest()).toList()

            assertEquals("## Page 1\n\nedited", storage.content)
            assertEquals(ExportType.MARKDOWN, repository.findById(recordId)?.type)
            assertEquals(ExportState.SUCCEEDED, repository.findById(recordId)?.state)
            assertTrue(events.any { it is ExportEvent.Progress && it.phase == ExportPhase.GENERATING })
            assertTrue(events.last() is ExportEvent.Succeeded)
            assertTrue(cache.listFiles().orEmpty().isEmpty())
        }

    @Test
    fun `unfinished records become retry candidates after process restart`() =
        runTest {
            val repository = InMemoryExportRecordRepository()
            val queued = record(recordId, ExportState.QUEUED, null, ExportType.TEXT_ZIP)
            val runningId = UUID.fromString("10000000-0000-0000-0000-000000000003")
            val running =
                record(
                    runningId,
                    ExportState.RUNNING,
                    "content://provider/document/redacted",
                    ExportType.IMAGE_ZIP,
                )
            repository.insert(queued)
            repository.insert(running)
            repository.insert(
                record(
                    UUID.fromString("10000000-0000-0000-0000-000000000004"),
                    ExportState.SUCCEEDED,
                    "content://provider/document/done",
                    ExportType.MARKDOWN,
                ),
            )

            val candidates = InterruptedExportDetector(repository).detect()

            assertEquals(listOf(recordId, runningId), candidates.map(RetryableExport::recordId))
            assertNull(candidates.first().destination)
            assertEquals(ExportDestination("content://provider/document/redacted"), candidates.last().destination)
        }

    private fun engine(
        cache: File,
        repository: ExportRecordRepository,
        generator: ExportArtifactGenerator,
    ) = ExportEngine(
        exportsCacheDirectory = cache,
        recordCoordinator = coordinator(repository),
        storageGateway = RecordingStorageGateway(),
        artifactGenerator = generator,
    )

    private fun coordinator(repository: ExportRecordRepository) =
        ExportRecordCoordinator(
            repository = repository,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            newId = { recordId },
        )

    private fun markdownRequest() =
        ExportRequest(
            projectId = projectId,
            destination = ExportDestination("content://provider/document/redacted"),
            artifact =
                ExportArtifact.Markdown(
                    listOf(TextExportPage(sequence = 1, fullText = "original", editedText = "edited")),
                ),
        )

    private fun record(
        id: UUID,
        state: ExportState,
        targetUri: String?,
        type: ExportType,
    ) = ExportRecord(
        id = id,
        projectId = projectId,
        type = type,
        targetUri = targetUri,
        state = state,
        createdAt = now,
        completedAt = if (state == ExportState.SUCCEEDED) now else null,
        errorCode = null,
    )

    private class InMemoryExportRecordRepository : ExportRecordRepository {
        private val records = linkedMapOf<UUID, ExportRecord>()

        override suspend fun insert(record: ExportRecord) {
            check(records.putIfAbsent(record.id, record) == null)
        }

        override suspend fun findById(id: UUID): ExportRecord? = records[id]

        override suspend fun findIncomplete(): List<ExportRecord> =
            records.values.filter { it.state == ExportState.QUEUED || it.state == ExportState.RUNNING }

        override suspend fun compareAndSet(
            expected: ExportRecord,
            updated: ExportRecord,
        ): Boolean {
            if (records[expected.id] != expected) return false
            records[expected.id] = updated
            return true
        }
    }

    private class RecordingStorageGateway : ExportStorageGateway {
        var content: String? = null
            private set

        override suspend fun write(
            source: CompletedExportSource,
            destination: ExportDestination,
        ): ExportStorageResult {
            val bytes = source.openInputStream().use { it.readBytes() }
            content = bytes.toString(Charsets.UTF_8)
            return ExportStorageResult.Succeeded(bytes.size.toLong())
        }
    }
}
