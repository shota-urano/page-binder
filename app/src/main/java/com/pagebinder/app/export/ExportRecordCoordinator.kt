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
     * プロセス終了で queued / running のまま取り残されたレコードを片付ける
     * （docs/specs/11-export.md §3.2 末尾「アプリ強制終了後、未完了の書き出しを検出して再試行できる」）。
     * 呼ぶのは再試行の書き出しが成功したときだけ。
     *
     * 仕様の状態遷移は `queued → running → succeeded / failed`（同 手順6）で、queued から終端へ
     * 直接向かう遷移は無い。そこで取り残されたレコードは**実行に入っていたか**で扱いを分ける。
     *
     * - running: 保存先に書きかけの成果物が残りうる。完了で確定していないので成功扱いにはできず
     *   （同 手順5 / FR-EXP-007）、[markFailed] と同じ `running → failed` で errorCode に
     *   `interrupted` を付けて閉じる
     * - queued: 実行に入っておらず、保存先も成果物も無い。仕様に無い終端状態を作らず、
     *   書き出しの履歴から取り除く（この書き出しの履歴は再試行側のレコードが持つ）
     *
     * すでに終端しているレコードには触れずそのまま返す。queued を取り除いた場合と、
     * 取り除いたあとにもう一度呼ばれた場合は null を返すので、多重に呼ばれても壊れない。
     */
    suspend fun markInterrupted(id: UUID): ExportRecord? {
        val current = repository.findById(id) ?: return null
        return when (current.state) {
            ExportState.RUNNING -> markFailed(id, ExportFailureCode.INTERRUPTED)
            ExportState.QUEUED -> {
                if (!repository.compareAndDelete(current)) {
                    throw ConcurrentExportRecordUpdateException(id)
                }
                null
            }
            ExportState.SUCCEEDED, ExportState.FAILED -> current
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
