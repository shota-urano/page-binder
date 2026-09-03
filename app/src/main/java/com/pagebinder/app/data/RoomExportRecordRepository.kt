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
    val id: UUID,
    @ColumnInfo(name = "project_id")
    val projectId: UUID,
    val type: ExportType,
    @ColumnInfo(name = "target_uri")
    val targetUri: String?,
    val state: ExportState,
    @ColumnInfo(name = "created_at")
    val createdAt: Instant,
    @ColumnInfo(name = "completed_at")
    val completedAt: Instant?,
    @ColumnInfo(name = "error_code")
    val errorCode: String?,
)

@Dao
interface ExportRecordDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(record: ExportRecordEntity)

    @Query("SELECT * FROM export_records WHERE id = :id")
    suspend fun findById(id: String): ExportRecordEntity?

    @Query("SELECT * FROM export_records WHERE state IN ('queued', 'running') ORDER BY created_at, id")
    suspend fun findIncomplete(): List<ExportRecordEntity>

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

    @Query("DELETE FROM export_records WHERE id = :id")
    suspend fun deleteById(id: String): Int

    @Query(
        """
        DELETE FROM export_records
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
    suspend fun compareAndDelete(
        expectedId: String,
        expectedProjectId: String,
        expectedType: String,
        expectedTargetUri: String?,
        expectedState: String,
        expectedCreatedAt: String,
        expectedCompletedAt: String?,
        expectedErrorCode: String?,
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

    override suspend fun findIncomplete(): List<ExportRecord> = dao.findIncomplete().map(ExportRecordEntity::toDomain)

    override suspend fun compareAndSet(
        expected: ExportRecord,
        updated: ExportRecord,
    ): Boolean {
        require(expected.id == updated.id) { "A compare-and-set update cannot change the record id" }
        val expectedEntity = expected.toEntity()
        val updatedEntity = updated.toEntity()
        return dao.compareAndSet(
            expectedId = expectedEntity.id.toString(),
            expectedProjectId = expectedEntity.projectId.toString(),
            expectedType = expectedEntity.type.serializedName,
            expectedTargetUri = expectedEntity.targetUri,
            expectedState = expectedEntity.state.serializedName,
            expectedCreatedAt = expectedEntity.createdAt.toString(),
            expectedCompletedAt = expectedEntity.completedAt?.toString(),
            expectedErrorCode = expectedEntity.errorCode,
            updatedProjectId = updatedEntity.projectId.toString(),
            updatedType = updatedEntity.type.serializedName,
            updatedTargetUri = updatedEntity.targetUri,
            updatedState = updatedEntity.state.serializedName,
            updatedCreatedAt = updatedEntity.createdAt.toString(),
            updatedCompletedAt = updatedEntity.completedAt?.toString(),
            updatedErrorCode = updatedEntity.errorCode,
        ) == 1
    }

    override suspend fun compareAndDelete(expected: ExportRecord): Boolean {
        val expectedEntity = expected.toEntity()
        return dao.compareAndDelete(
            expectedId = expectedEntity.id.toString(),
            expectedProjectId = expectedEntity.projectId.toString(),
            expectedType = expectedEntity.type.serializedName,
            expectedTargetUri = expectedEntity.targetUri,
            expectedState = expectedEntity.state.serializedName,
            expectedCreatedAt = expectedEntity.createdAt.toString(),
            expectedCompletedAt = expectedEntity.completedAt?.toString(),
            expectedErrorCode = expectedEntity.errorCode,
        ) == 1
    }
}
