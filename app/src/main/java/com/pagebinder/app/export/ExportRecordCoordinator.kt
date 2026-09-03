package com.pagebinder.app.export

import com.pagebinder.app.domain.ExportFailureCode
import com.pagebinder.app.domain.ExportRecord
import com.pagebinder.app.domain.ExportRecordRepository
import com.pagebinder.app.domain.ExportState
import com.pagebinder.app.domain.ExportType
import java.time.Clock
import java.time.Instant
import java.util.UUID

class ExportRecordCoordinator(
    private val repository: ExportRecordRepository,
    private val clock: Clock = Clock.systemUTC(),
    private val newId: () -> UUID = UUID::randomUUID,
) {
    suspend fun enqueue(
        projectId: UUID,
        type: ExportType,
    ): ExportRecord =
        ExportRecord(
            id = newId(),
            projectId = projectId,
            type = type,
            targetUri = null,
            state = ExportState.QUEUED,
            createdAt = Instant.now(clock),
            completedAt = null,
            errorCode = null,
        ).also { repository.insert(it) }

    suspend fun markRunning(
        id: UUID,
        targetUri: String,
    ): ExportRecord {
        require(targetUri.isNotBlank()) { "targetUri must not be blank" }
        return transition(id, ExportState.QUEUED) { current ->
            current.copy(
                targetUri = targetUri,
                state = ExportState.RUNNING,
                completedAt = null,
                errorCode = null,
            )
        }
    }

    suspend fun markSucceeded(id: UUID): ExportRecord =
        transition(id, ExportState.RUNNING) { current ->
            checkNotNull(current.targetUri) { "A running export must have a target URI" }
            current.copy(
                state = ExportState.SUCCEEDED,
                completedAt = Instant.now(clock),
                errorCode = null,
            )
        }

    suspend fun markFailed(
        id: UUID,
        errorCode: String,
    ): ExportRecord {
        require(errorCode.isNotBlank()) { "errorCode must not be blank" }
        return transition(id, ExportState.RUNNING) { current ->
            current.copy(
                state = ExportState.FAILED,
                completedAt = Instant.now(clock),
                errorCode = errorCode,
            )
        }
    }

    /**
     * プロセス終了で queued / running のまま取り残されたレコードを終端させる
     * （docs/specs/11-export.md §3.2 手順6「queued → running → succeeded / failed を記録する」を閉じる）。
     *
     * 呼ぶのは再試行の書き出しが成功したときだけ。取り残された方は完了で確定していない
     * （同 手順5 / FR-EXP-007: 不完全ファイルを成功扱いしない）ので failed + `interrupted` で閉じる。
     * すでに終端しているレコードはそのまま返すので、多重に呼ばれても壊れない。
     */
    suspend fun markInterrupted(id: UUID): ExportRecord {
        val current = repository.findById(id) ?: throw ExportRecordNotFoundException(id)
        if (current.state != ExportState.QUEUED && current.state != ExportState.RUNNING) return current

        val updated =
            current.copy(
                state = ExportState.FAILED,
                completedAt = Instant.now(clock),
                errorCode = ExportFailureCode.INTERRUPTED,
            )
        if (!repository.compareAndSet(current, updated)) {
            throw ConcurrentExportRecordUpdateException(id)
        }
        return updated
    }

    private suspend fun transition(
        id: UUID,
        requiredState: ExportState,
        update: (ExportRecord) -> ExportRecord,
    ): ExportRecord {
        val current = repository.findById(id) ?: throw ExportRecordNotFoundException(id)
        if (current.state != requiredState) {
            throw InvalidExportStateTransitionException(current.state, requiredState)
        }

        val updated = update(current)
        if (!repository.compareAndSet(current, updated)) {
            throw ConcurrentExportRecordUpdateException(id)
        }
        return updated
    }
}

class ExportRecordNotFoundException(id: UUID) :
    IllegalStateException("Export record was not found: $id")

class InvalidExportStateTransitionException(
    actual: ExportState,
    required: ExportState,
) : IllegalStateException("Export state was $actual, expected $required")

class ConcurrentExportRecordUpdateException(id: UUID) :
    IllegalStateException("Export record changed concurrently: $id")
