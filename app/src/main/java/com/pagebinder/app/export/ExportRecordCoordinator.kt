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
     * （docs/specs/11-export.md §3.2 末尾「アプリ強制終了後、未完了の書き出しを検出して再試行できる」）。
     * 呼ぶのは再試行の書き出しが成功したときだけ。
     *
     * 終端は必ず failed（errorCode = `interrupted`）。取り残されたレコードは §3.2 手順5 の
     * 「完了で確定する」に達しておらず、成功扱いにはできない（FR-EXP-007）。履歴レコードなので
     * 消さずに残す（docs/specs/02-data-model.md §3.1 ExportRecord は書き出しの履歴）。
     *
     * 経路は仕様の状態遷移グラフ `queued → running → succeeded / failed`（同 手順6）だけを使う。
     *
     * - running: そのまま `running → failed`
     * - queued: 仕様上 queued の唯一の後続である `queued → running` を経てから `running → failed`。
     *   queued からの終端遷移という仕様に無い辺を作らない
     *
     * すでに終端しているレコードには触れずそのまま返すので、多重に呼ばれても壊れない。
     * 該当レコードが無い場合だけ null を返す。
     */
    suspend fun markInterrupted(id: UUID): ExportRecord? {
        val current = repository.findById(id) ?: return null
        return when (current.state) {
            ExportState.RUNNING -> markFailed(id, ExportFailureCode.INTERRUPTED)
            ExportState.QUEUED -> {
                resumeInterruptedRun(id)
                markFailed(id, ExportFailureCode.INTERRUPTED)
            }
            ExportState.SUCCEEDED, ExportState.FAILED -> current
        }
    }

    /**
     * 取り残された queued のレコードを、仕様の唯一の後続 `queued → running` へ進める
     * （docs/specs/11-export.md §3.2 手順6）。実行に入る前に落ちているので保存先は決まっておらず、
     * `targetUri` は null のまま（02-data-model §3.1 で nullable）。この状態から
     * [markFailed] が `running → failed` で終端させる。
     */
    private suspend fun resumeInterruptedRun(id: UUID): ExportRecord =
        transition(id, ExportState.QUEUED) { current ->
            current.copy(
                state = ExportState.RUNNING,
                completedAt = null,
                errorCode = null,
            )
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
