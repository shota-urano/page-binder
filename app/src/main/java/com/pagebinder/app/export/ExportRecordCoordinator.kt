package com.pagebinder.app.export

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
