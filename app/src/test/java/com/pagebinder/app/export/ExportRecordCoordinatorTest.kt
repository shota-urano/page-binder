package com.pagebinder.app.export

import com.pagebinder.app.domain.ExportFailureCode
import com.pagebinder.app.domain.ExportRecord
import com.pagebinder.app.domain.ExportRecordRepository
import com.pagebinder.app.domain.ExportState
import com.pagebinder.app.domain.ExportType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class ExportRecordCoordinatorTest {
    private val recordId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val projectId = UUID.fromString("20000000-0000-0000-0000-000000000002")
    private val now = Instant.parse("2026-08-26T04:05:06Z")

    @Test
    fun `record transitions queued to running to succeeded`() =
        runBlocking {
            val repository = AtomicInMemoryExportRecordRepository()
            val coordinator = coordinator(repository)

            val queued = coordinator.enqueue(projectId, ExportType.MARKDOWN)
            val running = coordinator.markRunning(queued.id, "content://provider/document/redacted")
            val succeeded = coordinator.markSucceeded(running.id)

            assertEquals(ExportState.QUEUED, queued.state)
            assertNull(queued.targetUri)
            assertNull(queued.completedAt)
            assertEquals(ExportState.RUNNING, running.state)
            assertNull(running.completedAt)
            assertEquals(ExportState.SUCCEEDED, succeeded.state)
            assertEquals(now, succeeded.completedAt)
            assertNull(succeeded.errorCode)
            assertEquals(succeeded, repository.findById(recordId))
        }

    @Test
    fun `record transitions queued to running to failed with error code`() =
        runBlocking {
            val repository = AtomicInMemoryExportRecordRepository()
            val coordinator = coordinator(repository)

            val queued = coordinator.enqueue(projectId, ExportType.IMAGE_ZIP)
            coordinator.markRunning(queued.id, "content://provider/document/redacted")
            val failed = coordinator.markFailed(queued.id, "write_failed")

            assertEquals(ExportState.FAILED, failed.state)
            assertEquals(now, failed.completedAt)
            assertEquals("write_failed", failed.errorCode)
            assertEquals(failed, repository.findById(recordId))
        }

    /**
     * running のまま取り残されたレコードは failed で閉じる。保存先に書きかけの成果物が
     * 残りうるので成功扱いにはできない（docs/specs/11-export.md §3.2 手順5 / FR-EXP-007）。
     * 書かれる遷移は仕様どおりの running → failed だけ（同 手順6）。
     * これで InterruptedExportDetector の検出から外れ、書籍詳細の提示が消える。
     */
    @Test
    fun `interrupted running record is closed as failed`() =
        runBlocking {
            val repository = AtomicInMemoryExportRecordRepository()
            val coordinator = coordinator(repository)
            coordinator.enqueue(projectId, ExportType.MARKDOWN)
            coordinator.markRunning(recordId, "content://provider/document/redacted")

            val closed = coordinator.markInterrupted(recordId)

            assertEquals(ExportState.FAILED, closed?.state)
            assertEquals(now, closed?.completedAt)
            assertEquals(ExportFailureCode.INTERRUPTED, closed?.errorCode)
            assertEquals(closed, repository.findById(recordId))
            assertTrue(repository.findIncomplete().isEmpty())
            assertEquals(
                listOf(
                    ExportState.QUEUED to ExportState.RUNNING,
                    ExportState.RUNNING to ExportState.FAILED,
                ),
                repository.writtenTransitions,
            )
        }

    /**
     * queued のまま取り残されたレコード（強制終了が enqueue 後・running 前に起きた場合）も
     * failed で終端する。仕様の状態遷移 `queued → running → succeeded / failed`
     * （docs/specs/11-export.md §3.2 手順6）に queued からの終端は無いので、
     * queued → failed のような仕様外の遷移を書かず、queued → running を経てから閉じる。
     * 履歴レコードなので削除もしない（02-data-model §3.1）。
     */
    @Test
    fun `interrupted queued record is closed through queued to running to failed`() =
        runBlocking {
            val repository = AtomicInMemoryExportRecordRepository()
            val coordinator = coordinator(repository)
            coordinator.enqueue(projectId, ExportType.MARKDOWN)

            val closed = coordinator.markInterrupted(recordId)

            assertEquals(ExportState.FAILED, closed?.state)
            assertEquals(now, closed?.completedAt)
            assertEquals(ExportFailureCode.INTERRUPTED, closed?.errorCode)
            // 実行に入る前に落ちているので保存先は決まらないまま
            assertNull(closed?.targetUri)
            // 履歴として残り続ける
            assertEquals(closed, repository.findById(recordId))
            assertTrue(repository.findIncomplete().isEmpty())
            assertEquals(
                "仕様の状態遷移グラフに無い辺は書かれない",
                listOf(
                    ExportState.QUEUED to ExportState.RUNNING,
                    ExportState.RUNNING to ExportState.FAILED,
                ),
                repository.writtenTransitions,
            )
            // 終端後にもう一度呼ばれても書き足さない
            assertEquals(closed, coordinator.markInterrupted(recordId))
            assertEquals(2, repository.writtenTransitions.size)
        }

    /** 終端済みのレコードは書き換えない（再試行が二重に走っても壊れない） */
    @Test
    fun `interrupted marking leaves a terminal record untouched`() =
        runBlocking {
            val repository = AtomicInMemoryExportRecordRepository()
            val coordinator = coordinator(repository)
            coordinator.enqueue(projectId, ExportType.MARKDOWN)
            coordinator.markRunning(recordId, "content://provider/document/redacted")
            val succeeded = coordinator.markSucceeded(recordId)

            assertEquals(succeeded, coordinator.markInterrupted(recordId))
            assertEquals(succeeded, repository.findById(recordId))
        }

    @Test
    fun `terminal record cannot transition again`() {
        val repository = AtomicInMemoryExportRecordRepository()
        val coordinator = coordinator(repository)
        runBlocking {
            val queued = coordinator.enqueue(projectId, ExportType.TEXT_ZIP)
            coordinator.markRunning(queued.id, "content://provider/document/redacted")
            coordinator.markSucceeded(queued.id)
        }

        assertThrows(InvalidExportStateTransitionException::class.java) {
            runBlocking { coordinator.markFailed(recordId, "write_failed") }
        }
    }

    @Test
    fun `compare and set rejection prevents conflicting completion`() {
        val repository = AtomicInMemoryExportRecordRepository(rejectUpdates = true)
        val coordinator = coordinator(repository)
        runBlocking { coordinator.enqueue(projectId, ExportType.SEARCHABLE_PDF) }

        assertThrows(ConcurrentExportRecordUpdateException::class.java) {
            runBlocking {
                coordinator.markRunning(recordId, "content://provider/document/redacted")
            }
        }
        assertEquals(ExportState.QUEUED, runBlocking { repository.findById(recordId) }?.state)
    }

    private fun coordinator(repository: ExportRecordRepository) =
        ExportRecordCoordinator(
            repository = repository,
            clock = Clock.fixed(now, ZoneOffset.UTC),
            newId = { recordId },
        )

    /** Test persistence adapter; transition rules are exercised in production coordinator code. */
    private class AtomicInMemoryExportRecordRepository(
        private val rejectUpdates: Boolean = false,
    ) : ExportRecordRepository {
        private val records = mutableMapOf<UUID, ExportRecord>()

        /** 実際に保存された状態遷移。仕様外の遷移が書かれていないことを見るために記録する */
        val writtenTransitions = mutableListOf<Pair<ExportState, ExportState>>()

        override suspend fun insert(record: ExportRecord) {
            assertTrue(records.putIfAbsent(record.id, record) == null)
        }

        override suspend fun findById(id: UUID): ExportRecord? = records[id]

        override suspend fun findIncomplete(): List<ExportRecord> =
            records.values.filter { it.state == ExportState.QUEUED || it.state == ExportState.RUNNING }

        override suspend fun compareAndSet(
            expected: ExportRecord,
            updated: ExportRecord,
        ): Boolean {
            if (rejectUpdates || records[expected.id] != expected) return false
            records[expected.id] = updated
            writtenTransitions += expected.state to updated.state
            return true
        }
    }
}
