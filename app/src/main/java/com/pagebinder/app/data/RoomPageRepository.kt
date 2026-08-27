package com.pagebinder.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.domain.PageRepositoryException
import com.pagebinder.app.domain.VALID_PAGE_ROTATIONS
import java.time.Instant
import java.util.UUID

@Entity(
    tableName = "pages",
    indices = [Index(value = ["project_id", "sequence"], unique = true)],
)
data class PageEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "project_id")
    val projectId: String,
    val sequence: Int,
    @ColumnInfo(name = "original_image_path")
    val originalImagePath: String,
    val width: Int,
    val height: Int,
    val rotation: Int,
    @ColumnInfo(name = "crop_left")
    val cropLeft: Float,
    @ColumnInfo(name = "crop_top")
    val cropTop: Float,
    @ColumnInfo(name = "crop_right")
    val cropRight: Float,
    @ColumnInfo(name = "crop_bottom")
    val cropBottom: Float,
    @ColumnInfo(name = "captured_at")
    val capturedAt: String,
    @ColumnInfo(name = "content_hash")
    val contentHash: String,
    @ColumnInfo(name = "perceptual_hash")
    val perceptualHash: String,
    @ColumnInfo(name = "quality_state")
    val qualityState: String,
    @ColumnInfo(name = "ocr_state")
    val ocrState: String,
)

@Dao
abstract class PageDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(page: PageEntity)

    @Query("SELECT * FROM pages WHERE id = :id")
    abstract suspend fun findById(id: String): PageEntity?

    @Query("SELECT * FROM pages WHERE project_id = :projectId ORDER BY sequence")
    abstract suspend fun findByProject(projectId: String): List<PageEntity>

    @Query("UPDATE pages SET sequence = :sequence WHERE id = :id")
    protected abstract suspend fun updateSequence(
        id: String,
        sequence: Int,
    ): Int

    @Query("DELETE FROM pages WHERE id IN (:ids)")
    protected abstract suspend fun deleteByIds(ids: List<String>): Int

    @Query("UPDATE pages SET rotation = :rotation, ocr_state = 'stale' WHERE id = :id")
    protected abstract suspend fun updateRotationAndMarkStale(
        id: String,
        rotation: Int,
    ): Int

    @Query(
        """
        UPDATE pages
        SET crop_left = :left,
            crop_top = :top,
            crop_right = :right,
            crop_bottom = :bottom,
            ocr_state = 'stale'
        WHERE id = :id
        """,
    )
    protected abstract suspend fun updateCropAndMarkStale(
        id: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Int

    @Transaction
    open suspend fun reorderProject(
        projectId: String,
        orderedPageIds: List<String>,
    ) {
        val currentIds = findByProject(projectId).map(PageEntity::id)
        if (orderedPageIds.size != currentIds.size || orderedPageIds.toSet() != currentIds.toSet()) {
            throw PageRepositoryException.InvalidProjectOrder()
        }
        rewriteSequences(orderedPageIds)
    }

    @Transaction
    open suspend fun deleteAndCompact(
        projectId: String,
        pageIds: Set<String>,
    ) {
        if (pageIds.isEmpty()) return
        val currentIds = findByProject(projectId).map(PageEntity::id)
        if (!currentIds.containsAll(pageIds)) {
            throw PageRepositoryException.PagesNotInProject()
        }
        check(deleteByIds(pageIds.toList()) == pageIds.size) { "Page delete did not affect every selected row" }
        rewriteSequences(currentIds.filterNot(pageIds::contains))
    }

    @Transaction
    open suspend fun updateRotation(
        pageId: String,
        rotation: Int,
    ) {
        val page = findById(pageId) ?: throw PageRepositoryException.PageNotFound(UUID.fromString(pageId))
        if (page.rotation == rotation) return
        check(updateRotationAndMarkStale(pageId, rotation) == 1) { "Page rotation update did not affect one row" }
    }

    @Transaction
    open suspend fun updateCrop(
        pageId: String,
        crop: PageCrop,
    ) {
        val page = findById(pageId) ?: throw PageRepositoryException.PageNotFound(UUID.fromString(pageId))
        if (
            page.cropLeft == crop.left &&
            page.cropTop == crop.top &&
            page.cropRight == crop.right &&
            page.cropBottom == crop.bottom
        ) {
            return
        }
        check(
            updateCropAndMarkStale(
                id = pageId,
                left = crop.left,
                top = crop.top,
                right = crop.right,
                bottom = crop.bottom,
            ) == 1,
        ) { "Page crop update did not affect one row" }
    }

    private suspend fun rewriteSequences(orderedPageIds: List<String>) {
        // Temporary negative values avoid collisions with the unique (project, sequence) index.
        orderedPageIds.forEachIndexed { index, id ->
            check(updateSequence(id, -(index + 1)) == 1) { "Page sequence update did not affect one row" }
        }
        orderedPageIds.forEachIndexed { index, id ->
            check(updateSequence(id, index + 1) == 1) { "Page sequence update did not affect one row" }
        }
    }
}

class RoomPageRepository(
    private val dao: PageDao,
) : PageRepository {
    override suspend fun insert(page: Page) {
        dao.insert(page.toEntity())
    }

    override suspend fun findById(id: UUID): Page? = dao.findById(id.toString())?.toDomain()

    override suspend fun findByProject(projectId: UUID): List<Page> =
        dao.findByProject(projectId.toString()).map(PageEntity::toDomain)

    override suspend fun reorder(
        projectId: UUID,
        orderedPageIds: List<UUID>,
    ) {
        dao.reorderProject(projectId.toString(), orderedPageIds.map(UUID::toString))
    }

    override suspend fun delete(
        projectId: UUID,
        pageIds: Set<UUID>,
    ) {
        dao.deleteAndCompact(projectId.toString(), pageIds.mapTo(mutableSetOf(), UUID::toString))
    }

    override suspend fun updateRotation(
        pageId: UUID,
        rotation: Int,
    ) {
        require(rotation in VALID_PAGE_ROTATIONS) { "Page rotation must be 0, 90, 180, or 270 degrees" }
        dao.updateRotation(pageId.toString(), rotation)
    }

    override suspend fun updateCrop(
        pageId: UUID,
        crop: PageCrop,
    ) {
        dao.updateCrop(pageId.toString(), crop)
    }
}

private fun Page.toEntity() =
    PageEntity(
        id = id.toString(),
        projectId = projectId.toString(),
        sequence = sequence,
        originalImagePath = originalImagePath,
        width = width,
        height = height,
        rotation = rotation,
        cropLeft = crop.left,
        cropTop = crop.top,
        cropRight = crop.right,
        cropBottom = crop.bottom,
        capturedAt = capturedAt.toString(),
        contentHash = contentHash,
        perceptualHash = perceptualHash,
        qualityState = qualityState.serializedName,
        ocrState = ocrState.serializedName,
    )

private fun PageEntity.toDomain() =
    Page(
        id = UUID.fromString(id),
        projectId = UUID.fromString(projectId),
        sequence = sequence,
        originalImagePath = originalImagePath,
        width = width,
        height = height,
        rotation = rotation,
        crop = PageCrop(cropLeft, cropTop, cropRight, cropBottom),
        capturedAt = Instant.parse(capturedAt),
        contentHash = contentHash,
        perceptualHash = perceptualHash,
        qualityState = PageQualityState.entries.single { it.serializedName == qualityState },
        ocrState = PageOcrState.entries.single { it.serializedName == ocrState },
    )
