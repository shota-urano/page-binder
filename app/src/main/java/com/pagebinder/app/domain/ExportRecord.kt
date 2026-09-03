package com.pagebinder.app.domain

import java.time.Instant
import java.util.UUID

enum class ExportType(val serializedName: String) {
    SEARCHABLE_PDF("searchable_pdf"),
    IMAGE_PDF("image_pdf"),
    MARKDOWN("markdown"),
    TEXT_ZIP("text_zip"),
    IMAGE_ZIP("image_zip"),
}

enum class ExportState(val serializedName: String) {
    QUEUED("queued"),
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
}

data class ExportRecord(
    val id: UUID,
    val projectId: UUID,
    val type: ExportType,
    val targetUri: String?,
    val state: ExportState,
    val createdAt: Instant,
    val completedAt: Instant?,
    val errorCode: String?,
)

/**
 * Export history persistence boundary.
 *
 * [compareAndSet] must update atomically only when the stored record equals [expected].
 * This prevents two workers from completing the same export with conflicting states.
 */
interface ExportRecordRepository {
    suspend fun insert(record: ExportRecord)

    suspend fun findById(id: UUID): ExportRecord?

    /** Records left unfinished by cancellation or process termination. */
    suspend fun findIncomplete(): List<ExportRecord>

    suspend fun compareAndSet(
        expected: ExportRecord,
        updated: ExportRecord,
    ): Boolean

    /**
     * 書き出しの履歴から [expected] を取り除く。[compareAndSet] と同じ規約で、保存されている
     * レコードが [expected] と一致するときだけ削除し、一致しなければ何もせず false を返す。
     *
     * 用途は、実行に入らないまま取り残された queued レコードの片付けだけ。仕様の状態遷移
     * （docs/specs/11-export.md §3.2 手順6 `queued → running → succeeded / failed`）に
     * queued からの終端は無いため終端状態を作れない — `export/` の ExportRecordCoordinator を見よ。
     */
    suspend fun compareAndDelete(expected: ExportRecord): Boolean
}

/**
 * 前のプロセスが残した未完了の書き出し1件（docs/specs/11-export.md §3.2 末尾
 * 「アプリ強制終了後、未完了の書き出しを検出して再試行できる」）。
 *
 * 「未完了」= [ExportRecord] が queued / running のまま終端（succeeded / failed）へ達していない状態
 * （同 §3.2 手順6）。再試行の対象を指すために [recordId] を持つが、保存先URIは持たない
 * （AGENTS.md ルール6: 保存URIをログにも UI にも出さない）。
 */
data class InterruptedExport(
    val recordId: UUID,
    val type: ExportType,
)
