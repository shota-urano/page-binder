package com.pagebinder.app.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.domain.ExportRecord
import com.pagebinder.app.domain.ExportState
import com.pagebinder.app.domain.ExportType
import com.pagebinder.app.export.ExportRecordCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomExportRecordRepositoryTest {
    private val recordId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val projectId = UUID.fromString("20000000-0000-0000-0000-000000000002")
    private val now = Instant.parse("2026-08-26T04:05:06.123456789Z")
    private lateinit var database: TestExportRecordDatabase
    private lateinit var repository: RoomExportRecordRepository

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                TestExportRecordDatabase::class.java,
            ).build()
        repository = RoomExportRecordRepository(database.exportRecordDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insertAndFindByIdRoundTripAllFields() =
        runBlocking {
            val record =
                record(
                    state = ExportState.FAILED,
                    targetUri = "content://provider/document/redacted",
                    completedAt = now.plusSeconds(2),
                    errorCode = "write_failed",
                )

            repository.insert(record)

            assertEquals(record, repository.findById(record.id))
        }

    @Test
    fun coordinatorPersistsQueuedRunningSucceededTransitions() =
        runBlocking {
            val coordinator = coordinator()

            val queued = coordinator.enqueue(projectId, ExportType.MARKDOWN)
            val running = coordinator.markRunning(queued.id, "content://provider/document/redacted")
            val succeeded = coordinator.markSucceeded(running.id)

            assertEquals(ExportState.QUEUED, queued.state)
            assertEquals(ExportState.RUNNING, running.state)
            assertEquals(ExportState.SUCCEEDED, succeeded.state)
            assertEquals(succeeded, repository.findById(recordId))
        }

    @Test
    fun coordinatorPersistsQueuedRunningFailedTransitions() =
        runBlocking {
            val coordinator = coordinator()

            val queued = coordinator.enqueue(projectId, ExportType.IMAGE_ZIP)
            coordinator.markRunning(queued.id, "content://provider/document/redacted")
            val failed = coordinator.markFailed(queued.id, "write_failed")

            assertEquals(ExportState.FAILED, failed.state)
            assertEquals("write_failed", failed.errorCode)
            assertEquals(failed, repository.findById(recordId))
        }

    @Test
    fun findIncompleteReturnsOnlyQueuedAndRunningRecords() =
        runBlocking {
            val runningId = UUID.fromString("10000000-0000-0000-0000-000000000003")
            val succeededId = UUID.fromString("10000000-0000-0000-0000-000000000004")
            val queued = record(state = ExportState.QUEUED)
            val running =
                record(
                    state = ExportState.RUNNING,
                    targetUri = "content://provider/document/redacted",
                ).copy(id = runningId)
            val succeeded =
                record(
                    state = ExportState.SUCCEEDED,
                    targetUri = "content://provider/document/complete",
                    completedAt = now.plusSeconds(1),
                ).copy(id = succeededId)
            repository.insert(queued)
            repository.insert(running)
            repository.insert(succeeded)

            assertEquals(listOf(queued, running), repository.findIncomplete())
        }

    @Test
    fun staleExpectedRecordCannotOverwriteNewerValue() =
        runBlocking {
            val queued = record(state = ExportState.QUEUED)
            val running =
                queued.copy(
                    state = ExportState.RUNNING,
                    targetUri = "content://provider/document/redacted",
                )
            val failed = queued.copy(state = ExportState.FAILED, errorCode = "stale_writer")
            repository.insert(queued)

            assertTrue(repository.compareAndSet(queued, running))
            assertFalse(repository.compareAndSet(queued, failed))
            assertEquals(running, repository.findById(recordId))
        }

    @Test
    fun concurrentCompareAndSetAllowsExactlyOneWinner() =
        runBlocking {
            val queued = record(state = ExportState.QUEUED)
            repository.insert(queued)
            val candidates =
                listOf(
                    queued.copy(state = ExportState.RUNNING, targetUri = "content://provider/document/first"),
                    queued.copy(state = ExportState.RUNNING, targetUri = "content://provider/document/second"),
                )

            val results =
                withContext(Dispatchers.IO) {
                    candidates.map { candidate ->
                        async { repository.compareAndSet(queued, candidate) }
                    }.awaitAll()
                }

            assertEquals(1, results.count { it })
            assertTrue(repository.findById(recordId) in candidates)
        }

    private fun coordinator() =
        ExportRecordCoordinator(
            repository = repository,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            newId = { recordId },
        )

    private fun record(
        state: ExportState,
        targetUri: String? = null,
        completedAt: Instant? = null,
        errorCode: String? = null,
    ) = ExportRecord(
        id = recordId,
        projectId = projectId,
        type = ExportType.MARKDOWN,
        targetUri = targetUri,
        state = state,
        createdAt = now,
        completedAt = completedAt,
        errorCode = errorCode,
    )
}

@Database(
    entities = [ExportRecordEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TestExportRecordDatabase : RoomDatabase() {
    abstract fun exportRecordDao(): ExportRecordDao
}
