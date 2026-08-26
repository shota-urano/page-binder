package com.pagebinder.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.pagebinder.app.domain.ExportRecord
import com.pagebinder.app.domain.ExportRecordRepository
import com.pagebinder.app.domain.ExportState
import com.pagebinder.app.domain.ExportType
import java.time.Instant
import java.util.UUID

@Entity(tableName = "export_records")
data class ExportRecordEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "project_id")
    val projectId: String,
    val type: String,
    @ColumnInfo(name = "target_uri")
    val targetUri: String?,
    val state: String,
    @ColumnInfo(name = "created_at")
    val createdAt: String,
    @ColumnInfo(name = "completed_at")
    val completedAt: String?,
    @ColumnInfo(name = "error_code")
    val errorCode: String?,
)

@Dao
interface ExportRecordDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: ExportRecordEntity)

    @Query("SELECT * FROM export_records WHERE id = :id")
    suspend fun findById(id: String): ExportRecordEntity?

    @Query(
        """
        UPDATE export_records
        SET project_id = :updatedProjectId,
            type = :updatedType,
            target_uri = :updatedTargetUri,
            state = :updatedState,
            created_at = :updatedCreatedAt,
            completed_at = :updatedCompletedAt,
            error_code = :updatedErrorCode
        WHERE id = :expectedId
          AND project_id = :expectedProjectId
          AND type = :expectedType
          AND target_uri IS :expectedTargetUri
          AND state = :expectedState
          AND created_at = :expectedCreatedAt
          AND completed_at IS :expectedCompletedAt
          AND error_code IS :expectedErrorCode
        """,
    )
    suspend fun compareAndSet(
        expectedId: String,
        expectedProjectId: String,
        expectedType: String,
        expectedTargetUri: String?,
        expectedState: String,
        expectedCreatedAt: String,
        expectedCompletedAt: String?,
        expectedErrorCode: String?,
        updatedProjectId: String,
        updatedType: String,
        updatedTargetUri: String?,
        updatedState: String,
        updatedCreatedAt: String,
        updatedCompletedAt: String?,
        updatedErrorCode: String?,
    ): Int
}

/** Room-backed production adapter for export history persistence. */
class RoomExportRecordRepository(
    private val dao: ExportRecordDao,
) : ExportRecordRepository {
    override suspend fun insert(record: ExportRecord) {
        dao.insert(record.toEntity())
    }

    override suspend fun findById(id: UUID): ExportRecord? = dao.findById(id.toString())?.toDomain()

    override suspend fun compareAndSet(
        expected: ExportRecord,
        updated: ExportRecord,
    ): Boolean {
        require(expected.id == updated.id) { "A compare-and-set update cannot change the record id" }
        val expectedEntity = expected.toEntity()
        val updatedEntity = updated.toEntity()
        return dao.compareAndSet(
            expectedId = expectedEntity.id,
            expectedProjectId = expectedEntity.projectId,
            expectedType = expectedEntity.type,
            expectedTargetUri = expectedEntity.targetUri,
            expectedState = expectedEntity.state,
            expectedCreatedAt = expectedEntity.createdAt,
            expectedCompletedAt = expectedEntity.completedAt,
            expectedErrorCode = expectedEntity.errorCode,
            updatedProjectId = updatedEntity.projectId,
            updatedType = updatedEntity.type,
            updatedTargetUri = updatedEntity.targetUri,
            updatedState = updatedEntity.state,
            updatedCreatedAt = updatedEntity.createdAt,
            updatedCompletedAt = updatedEntity.completedAt,
            updatedErrorCode = updatedEntity.errorCode,
        ) == 1
    }
}

private fun ExportRecord.toEntity() =
    ExportRecordEntity(
        id = id.toString(),
        projectId = projectId.toString(),
        type = type.serializedName,
        targetUri = targetUri,
        state = state.serializedName,
        createdAt = createdAt.toString(),
        completedAt = completedAt?.toString(),
        errorCode = errorCode,
    )

private fun ExportRecordEntity.toDomain() =
    ExportRecord(
        id = UUID.fromString(id),
        projectId = UUID.fromString(projectId),
        type = ExportType.entries.single { it.serializedName == type },
        targetUri = targetUri,
        state = ExportState.entries.single { it.serializedName == state },
        createdAt = Instant.parse(createdAt),
        completedAt = completedAt?.let(Instant::parse),
        errorCode = errorCode,
    )
