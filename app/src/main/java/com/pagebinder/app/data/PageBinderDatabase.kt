package com.pagebinder.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.pagebinder.app.domain.OcrCrop
import com.pagebinder.app.domain.OcrJobRepository
import com.pagebinder.app.domain.OcrPage
import com.pagebinder.app.domain.OcrState
import com.pagebinder.app.domain.StoredOcrResult
import java.time.Instant
import java.util.UUID

@Entity(tableName = "book_projects")
data class BookProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val author: String?,
    val note: String?,
    @ColumnInfo(name = "created_at") val createdAt: String,
    @ColumnInfo(name = "updated_at") val updatedAt: String,
    @ColumnInfo(name = "deleted_at") val deletedAt: String?,
)

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = BookProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.NO_ACTION,
            onUpdate = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index(value = ["project_id", "sequence"], unique = true)],
)
data class PageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "project_id") val projectId: String,
    val sequence: Int,
    @ColumnInfo(name = "original_image_path") val originalImagePath: String,
    val width: Int,
    val height: Int,
    val rotation: Int,
    @ColumnInfo(name = "crop_left") val cropLeft: Float,
    @ColumnInfo(name = "crop_top") val cropTop: Float,
    @ColumnInfo(name = "crop_right") val cropRight: Float,
    @ColumnInfo(name = "crop_bottom") val cropBottom: Float,
    @ColumnInfo(name = "captured_at") val capturedAt: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "perceptual_hash") val perceptualHash: String,
    @ColumnInfo(name = "quality_state") val qualityState: String,
    @ColumnInfo(name = "ocr_state") val ocrState: String,
)

@Entity(tableName = "ocr_results")
data class OcrResultEntity(
    @PrimaryKey @ColumnInfo(name = "page_id") val pageId: String,
    @ColumnInfo(name = "full_text") val fullText: String,
    @ColumnInfo(name = "blocks_json") val blocksJson: String,
    @ColumnInfo(name = "edited_text") val editedText: String?,
    @ColumnInfo(name = "engine_version") val engineVersion: String,
    @ColumnInfo(name = "source_image_hash") val sourceImageHash: String,
    @ColumnInfo(name = "processed_at") val processedAt: String,
)

@Dao
interface OcrJobDao {
    @Query(
        """
        UPDATE pages SET ocr_state = 'pending'
        WHERE id = :pageId
          AND quality_state != 'black'
          AND ocr_state IN (:expectedStates)
        """,
    )
    suspend fun markPending(
        pageId: String,
        expectedStates: Set<String>,
    ): Int

    @Query(
        """
        UPDATE pages SET ocr_state = 'pending'
        WHERE project_id = :projectId
          AND quality_state != 'black'
          AND ocr_state IN (:expectedStates)
        """,
    )
    suspend fun markProjectPending(
        projectId: String,
        expectedStates: Set<String>,
    ): Int

    @Query(
        """
        SELECT * FROM pages
        WHERE ocr_state = 'pending' AND quality_state != 'black'
        ORDER BY captured_at, sequence, id LIMIT 1
        """,
    )
    suspend fun findNextPending(): PageEntity?

    @Query(
        """
        UPDATE pages SET ocr_state = 'running'
        WHERE id = :pageId AND ocr_state = 'pending' AND quality_state != 'black'
        """,
    )
    suspend fun claimPending(pageId: String): Int

    @Transaction
    suspend fun claimNextPending(): PageEntity? {
        while (true) {
            val candidate = findNextPending() ?: return null
            if (claimPending(candidate.id) == 1) return candidate.copy(ocrState = OcrState.RUNNING.serializedName)
        }
    }

    @Query("UPDATE pages SET ocr_state = 'pending' WHERE ocr_state = 'running' AND quality_state != 'black'")
    suspend fun recoverInterrupted(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertResult(result: OcrResultEntity)

    @Query(
        """
        UPDATE pages SET ocr_state = :targetState
        WHERE id = :pageId
          AND ocr_state = :expectedState
          AND (:targetState != 'pending' OR quality_state != 'black')
        """,
    )
    suspend fun transition(
        pageId: String,
        expectedState: String,
        targetState: String,
    ): Int

    @Transaction
    suspend fun storeSuccess(
        pageId: String,
        result: OcrResultEntity,
    ): Boolean {
        if (transition(pageId, OcrState.RUNNING.serializedName, OcrState.SUCCEEDED.serializedName) != 1) return false
        upsertResult(result)
        return true
    }
}

@Database(
    entities = [BookProjectEntity::class, PageEntity::class, OcrResultEntity::class, ExportRecordEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class PageBinderDatabase : RoomDatabase() {
    abstract fun bookProjectDao(): BookProjectDao

    abstract fun pageDao(): PageDao

    abstract fun ocrJobDao(): OcrJobDao

    abstract fun ocrResultDao(): OcrResultDao

    abstract fun exportRecordDao(): ExportRecordDao

    companion object {
        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        """
                        CREATE TABLE IF NOT EXISTS `pages_new` (
                            `id` TEXT NOT NULL,
                            `project_id` TEXT NOT NULL,
                            `sequence` INTEGER NOT NULL,
                            `original_image_path` TEXT NOT NULL,
                            `width` INTEGER NOT NULL,
                            `height` INTEGER NOT NULL,
                            `rotation` INTEGER NOT NULL,
                            `crop_left` REAL NOT NULL,
                            `crop_top` REAL NOT NULL,
                            `crop_right` REAL NOT NULL,
                            `crop_bottom` REAL NOT NULL,
                            `captured_at` TEXT NOT NULL,
                            `content_hash` TEXT NOT NULL,
                            `perceptual_hash` TEXT NOT NULL,
                            `quality_state` TEXT NOT NULL,
                            `ocr_state` TEXT NOT NULL,
                            PRIMARY KEY(`id`),
                            FOREIGN KEY(`project_id`) REFERENCES `book_projects`(`id`)
                                ON UPDATE NO ACTION ON DELETE NO ACTION
                        )
                        """.trimIndent(),
                    )
                    database.execSQL(
                        """
                        INSERT INTO `pages_new` (
                            `id`, `project_id`, `sequence`, `original_image_path`, `width`, `height`,
                            `rotation`, `crop_left`, `crop_top`, `crop_right`, `crop_bottom`, `captured_at`,
                            `content_hash`, `perceptual_hash`, `quality_state`, `ocr_state`
                        )
                        SELECT
                            `id`, `project_id`, `sequence`, `original_image_path`, `width`, `height`,
                            `rotation`, `crop_left`, `crop_top`, `crop_right`, `crop_bottom`, `captured_at`,
                            `content_hash`, `perceptual_hash`, `quality_state`, `ocr_state`
                        FROM `pages`
                        """.trimIndent(),
                    )
                    database.execSQL("DROP TABLE `pages`")
                    database.execSQL("ALTER TABLE `pages_new` RENAME TO `pages`")
                    database.execSQL(
                        """
                        CREATE UNIQUE INDEX IF NOT EXISTS `index_pages_project_id_sequence`
                        ON `pages` (`project_id`, `sequence`)
                        """.trimIndent(),
                    )
                }
            }
    }
}

class RoomOcrJobRepository(
    private val dao: OcrJobDao,
) : OcrJobRepository {
    override suspend fun markPending(
        pageId: UUID,
        expectedStates: Set<OcrState>,
    ): Boolean = dao.markPending(pageId.toString(), expectedStates.mapTo(mutableSetOf(), OcrState::serializedName)) == 1

    override suspend fun markProjectPending(
        projectId: UUID,
        expectedStates: Set<OcrState>,
    ): Int =
        dao.markProjectPending(
            projectId.toString(),
            expectedStates.mapTo(mutableSetOf(), OcrState::serializedName),
        )

    override suspend fun claimNextPending(): OcrPage? = dao.claimNextPending()?.toDomain()

    override suspend fun recoverInterrupted(): Int = dao.recoverInterrupted()

    override suspend fun storeSuccess(
        pageId: UUID,
        result: StoredOcrResult,
    ): Boolean = dao.storeSuccess(pageId.toString(), result.toEntity())

    override suspend fun markFailed(pageId: UUID): Boolean =
        dao.transition(pageId.toString(), OcrState.RUNNING.serializedName, OcrState.FAILED.serializedName) == 1

    override suspend fun returnToPending(pageId: UUID): Boolean =
        dao.transition(pageId.toString(), OcrState.RUNNING.serializedName, OcrState.PENDING.serializedName) == 1
}

private fun PageEntity.toDomain() =
    OcrPage(
        id = UUID.fromString(id),
        projectId = UUID.fromString(projectId),
        sequence = sequence,
        originalImagePath = originalImagePath,
        rotation = rotation,
        crop = OcrCrop(cropLeft, cropTop, cropRight, cropBottom),
        capturedAt = Instant.parse(capturedAt),
        ocrState = OcrState.entries.single { it.serializedName == ocrState },
    )

private fun StoredOcrResult.toEntity() =
    OcrResultEntity(
        pageId = pageId.toString(),
        fullText = fullText,
        blocksJson = blocksJson,
        editedText = editedText,
        engineVersion = engineVersion,
        sourceImageHash = sourceImageHash,
        processedAt = processedAt.toString(),
    )
