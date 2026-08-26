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

    suspend fun compareAndSet(
        expected: ExportRecord,
        updated: ExportRecord,
    ): Boolean
}
