package com.pagebinder.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageCropScope
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.domain.PageRepositoryException
import com.pagebinder.app.domain.VALID_PAGE_ROTATIONS
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

@Dao
abstract class PageDao {
    private var lastUndoAction: PageUndoAction? = null

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insert(page: PageEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertAll(pages: List<PageEntity>)

    @Query("SELECT * FROM pages WHERE id = :id")
    abstract suspend fun findById(id: String): PageEntity?

    @Query("SELECT * FROM pages WHERE project_id = :projectId ORDER BY sequence")
    abstract suspend fun findByProject(projectId: String): List<PageEntity>

    /**
     * [findByProject] と同じ並びを購読する。`pages` を読むので、撮影による追加・削除・編集で
     * Room が再クエリして現在値を流し直す（一覧を開いたままでも撮影結果が入る）。
     */
    @Query("SELECT * FROM pages WHERE project_id = :projectId ORDER BY sequence")
    abstract fun observeByProject(projectId: String): Flow<List<PageEntity>>

    @Query("UPDATE pages SET sequence = :sequence WHERE id = :id")
    protected abstract suspend fun updateSequence(
        id: String,
        sequence: Int,
    ): Int

    @Query("DELETE FROM pages WHERE id IN (:ids)")
    protected abstract suspend fun deleteByIds(ids: List<String>): Int

    /**
     * 削除するページのOCR結果。`ocr_results` は `pages` への外部キーを持たないので、
     * ページを消すときに一緒に消さないと孤児行が残る（pagebinder-dy7）。
     * 取り消しで戻せるよう、消す前にここで控える。
     */
    @Query("SELECT * FROM ocr_results WHERE page_id IN (:pageIds)")
    protected abstract suspend fun findOcrResults(pageIds: List<String>): List<OcrResultEntity>

    @Query("DELETE FROM ocr_results WHERE page_id IN (:pageIds)")
    protected abstract suspend fun deleteOcrResults(pageIds: List<String>): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insertOcrResults(results: List<OcrResultEntity>)

    @Transaction
    open suspend fun rollbackCaptureInsert(
        projectId: String,
        pageId: String,
    ) {
        val page = findById(pageId) ?: return
        check(page.projectId.toString() == projectId) { "Captured page does not belong to project" }
        check(deleteByIds(listOf(pageId)) == 1) { "Capture rollback did not delete the inserted page" }
    }

    @Query("UPDATE pages SET quality_state = :qualityState WHERE id = :id")
    protected abstract suspend fun updateQualityState(
        id: String,
        qualityState: String,
    ): Int

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

    @Query("UPDATE pages SET rotation = :rotation, ocr_state = :ocrState WHERE id = :id")
    protected abstract suspend fun restoreRotation(
        id: String,
        rotation: Int,
        ocrState: String,
    ): Int

    @Query(
        """
        UPDATE pages
        SET crop_left = :left,
            crop_top = :top,
            crop_right = :right,
            crop_bottom = :bottom,
            ocr_state = :ocrState
        WHERE id = :id
        """,
    )
    protected abstract suspend fun restoreCrop(
        id: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        ocrState: String,
    ): Int

    @Transaction
    open suspend fun reorderProject(
        projectId: String,
        orderedPageIds: List<String>,
    ) {
        val currentIds = findByProject(projectId).map { it.id.toString() }
        if (orderedPageIds.size != currentIds.size || orderedPageIds.toSet() != currentIds.toSet()) {
            throw PageRepositoryException.InvalidProjectOrder()
        }
        if (orderedPageIds == currentIds) return
        rewriteSequences(orderedPageIds)
        lastUndoAction = PageUndoAction.Reorder(currentIds)
    }

    @Transaction
    open suspend fun deleteAndCompact(
        projectId: String,
        pageIds: Set<String>,
    ) {
        deleteAndResolveDuplicates(projectId, pageIds, emptySet())
    }

    /**
     * Deletes [pageIds] and clears the duplicate warning of [resolvedDuplicatePageIds] in one
     * transaction, so a failure on any row rolls back the others and a single undo entry covers
     * both parts (docs/specs/08-page-editing.md §3.2 FR-EDT-006・§3.4).
     */
    @Transaction
    open suspend fun deleteAndResolveDuplicates(
        projectId: String,
        pageIds: Set<String>,
        resolvedDuplicatePageIds: Set<String>,
    ) {
        if (pageIds.isEmpty() && resolvedDuplicatePageIds.isEmpty()) return
        val currentPages = findByProject(projectId)
        val currentIds = currentPages.map { it.id.toString() }
        if (!currentIds.containsAll(pageIds) || !currentIds.containsAll(resolvedDuplicatePageIds)) {
            throw PageRepositoryException.PagesNotInProject()
        }
        val deletedPages = currentPages.filter { it.id.toString() in pageIds }
        // 既に重複警告が付いているページだけを戻す対象として控える（付いていなければ書き換えない）
        val resolvedPages =
            currentPages.filter {
                it.id.toString() in resolvedDuplicatePageIds && it.qualityState == PageQualityState.DUPLICATE
            }
        // ページと同じトランザクションでOCR結果も消す。片方だけ残ると孤児行になる（pagebinder-dy7）
        val deletedOcrResults = if (pageIds.isEmpty()) emptyList() else findOcrResults(pageIds.toList())
        if (pageIds.isNotEmpty()) {
            deleteOcrResults(pageIds.toList())
            check(deleteByIds(pageIds.toList()) == pageIds.size) { "Page delete did not affect every selected row" }
            rewriteSequences(currentIds.filterNot(pageIds::contains))
        }
        resolvedPages.forEach { page ->
            check(updateQualityState(page.id.toString(), NORMAL_QUALITY_STATE) == 1) {
                "Page quality state update did not affect one row"
            }
        }
        lastUndoAction =
            PageUndoAction.Delete(
                projectId = projectId,
                orderedPageIds = currentIds,
                deletedPages = deletedPages,
                deletedOcrResults = deletedOcrResults,
                resolvedQualityStates = resolvedPages.map(PageEntity::toQualityUndoAction),
            )
    }

    @Transaction
    open suspend fun updateRotation(
        pageId: String,
        rotation: Int,
    ) {
        val page = findById(pageId) ?: throw PageRepositoryException.PageNotFound(UUID.fromString(pageId))
        if (page.rotation == rotation) return
        check(updateRotationAndMarkStale(pageId, rotation) == 1) { "Page rotation update did not affect one row" }
        lastUndoAction = PageUndoAction.Rotation(pageId, page.rotation, page.ocrState.serializedName)
    }

    @Transaction
    open suspend fun updateCrop(
        pageId: String,
        crop: PageCrop,
    ) {
        val page = findById(pageId) ?: throw PageRepositoryException.PageNotFound(UUID.fromString(pageId))
        if (page.hasCrop(crop)) return
        check(
            updateCropAndMarkStale(
                id = pageId,
                left = crop.left,
                top = crop.top,
                right = crop.right,
                bottom = crop.bottom,
            ) == 1,
        ) { "Page crop update did not affect one row" }
        lastUndoAction = page.toCropUndoAction()
    }

    /**
     * Stores rotation and crop as one edit. The crop reaches either the opened page alone or every
     * page of its project, and the whole write runs in a single transaction, so a failure on any
     * target rolls back the others. One undo entry covers every row the edit touched.
     */
    @Transaction
    open suspend fun updatePageEdit(
        pageId: String,
        rotation: Int,
        crop: PageCrop,
        projectWideCrop: Boolean,
    ): Int {
        val page = findById(pageId) ?: throw PageRepositoryException.PageNotFound(UUID.fromString(pageId))
        val cropTargets = if (projectWideCrop) findByProject(page.projectId.toString()) else listOf(page)
        val rotationChanged = page.rotation != rotation
        val cropChanges = cropTargets.filterNot { it.hasCrop(crop) }
        if (rotationChanged) {
            check(updateRotationAndMarkStale(pageId, rotation) == 1) { "Page rotation update did not affect one row" }
        }
        cropChanges.forEach { target ->
            check(
                updateCropAndMarkStale(
                    id = target.id.toString(),
                    left = crop.left,
                    top = crop.top,
                    right = crop.right,
                    bottom = crop.bottom,
                ) == 1,
            ) { "Page crop update did not affect one row" }
        }
        if (rotationChanged || cropChanges.isNotEmpty()) {
            lastUndoAction =
                PageUndoAction.Edit(
                    rotation =
                        PageUndoAction
                            .Rotation(pageId, page.rotation, page.ocrState.serializedName)
                            .takeIf { rotationChanged },
                    crops = cropChanges.map(PageEntity::toCropUndoAction),
                )
        }
        return cropTargets.size
    }

    @Transaction
    open suspend fun undoLastEdit(): Boolean {
        val action = lastUndoAction ?: return false
        when (action) {
            is PageUndoAction.Reorder -> rewriteSequences(action.orderedPageIds)
            is PageUndoAction.Delete -> {
                // 削除と一緒に消した重複警告も同じ1操作として戻す（docs/specs/08-page-editing.md §3.4）
                action.resolvedQualityStates.forEach { restore(it) }
                val remainingIds = findByProject(action.projectId).map { it.id.toString() }
                stageSequences(remainingIds)
                insertAll(action.deletedPages)
                // ページと一緒に消したOCR結果も同じ1操作で戻す（消したページの本文が失われないため）
                insertOcrResults(action.deletedOcrResults)
                assignFinalSequences(action.orderedPageIds)
            }
            is PageUndoAction.Rotation -> restore(action)
            is PageUndoAction.Crop -> restore(action)
            // 1回の編集で触れた全ページを1操作として戻す（docs/specs/08-page-editing.md §3.4）
            is PageUndoAction.Edit -> {
                action.crops.forEach { restore(it) }
                action.rotation?.let { restore(it) }
            }
        }
        lastUndoAction = null
        return true
    }

    private suspend fun restore(action: PageUndoAction.Quality) {
        check(updateQualityState(action.pageId, action.qualityState) == 1) {
            "Page quality state restore did not affect one row"
        }
    }

    private suspend fun restore(action: PageUndoAction.Rotation) {
        check(restoreRotation(action.pageId, action.rotation, action.ocrState) == 1) {
            "Page rotation restore did not affect one row"
        }
    }

    private suspend fun restore(action: PageUndoAction.Crop) {
        check(
            restoreCrop(
                id = action.pageId,
                left = action.left,
                top = action.top,
                right = action.right,
                bottom = action.bottom,
                ocrState = action.ocrState,
            ) == 1,
        ) { "Page crop restore did not affect one row" }
    }

    private suspend fun rewriteSequences(orderedPageIds: List<String>) {
        // Temporary negative values avoid collisions with the unique (project, sequence) index.
        stageSequences(orderedPageIds)
        assignFinalSequences(orderedPageIds)
    }

    private suspend fun assignFinalSequences(orderedPageIds: List<String>) {
        orderedPageIds.forEachIndexed { index, id ->
            check(updateSequence(id, index + 1) == 1) { "Page sequence update did not affect one row" }
        }
    }

    private suspend fun stageSequences(orderedPageIds: List<String>) {
        orderedPageIds.forEachIndexed { index, id ->
            check(updateSequence(id, -(index + 1)) == 1) { "Page sequence update did not affect one row" }
        }
    }
}

class RoomPageRepository(
    private val dao: PageDao,
) : PageRepository {
    override suspend fun insert(page: Page) {
        dao.insert(page.toEntity())
    }

    override suspend fun rollbackCaptureInsert(
        projectId: UUID,
        pageId: UUID,
    ) {
        dao.rollbackCaptureInsert(projectId.toString(), pageId.toString())
    }

    override suspend fun findById(id: UUID): Page? = dao.findById(id.toString())?.toDomain()

    override suspend fun findByProject(projectId: UUID): List<Page> =
        dao.findByProject(projectId.toString()).map(PageEntity::toDomain)

    override fun observeByProject(projectId: UUID): Flow<List<Page>> =
        dao.observeByProject(projectId.toString()).map { pages -> pages.map(PageEntity::toDomain) }

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

    override suspend fun deleteResolvingDuplicates(
        projectId: UUID,
        pageIds: Set<UUID>,
        resolvedDuplicatePageIds: Set<UUID>,
    ) {
        require(pageIds.none(resolvedDuplicatePageIds::contains)) {
            "A page cannot be deleted and kept by the same edit"
        }
        dao.deleteAndResolveDuplicates(
            projectId = projectId.toString(),
            pageIds = pageIds.mapTo(mutableSetOf(), UUID::toString),
            resolvedDuplicatePageIds = resolvedDuplicatePageIds.mapTo(mutableSetOf(), UUID::toString),
        )
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

    override suspend fun updatePageEdit(
        pageId: UUID,
        rotation: Int,
        crop: PageCrop,
        cropScope: PageCropScope,
    ): Int {
        require(rotation in VALID_PAGE_ROTATIONS) { "Page rotation must be 0, 90, 180, or 270 degrees" }
        return dao.updatePageEdit(
            pageId = pageId.toString(),
            rotation = rotation,
            crop = crop,
            projectWideCrop = cropScope == PageCropScope.PROJECT,
        )
    }

    override suspend fun undoLastEdit(): Boolean = dao.undoLastEdit()
}

private sealed interface PageUndoAction {
    data class Reorder(
        val orderedPageIds: List<String>,
    ) : PageUndoAction

    data class Delete(
        val projectId: String,
        val orderedPageIds: List<String>,
        val deletedPages: List<PageEntity>,
        /** 削除と同時に消したOCR結果。取り消しで一緒に戻す */
        val deletedOcrResults: List<OcrResultEntity> = emptyList(),
        /** 削除と同時に消した重複警告。取り消しで一緒に戻す */
        val resolvedQualityStates: List<Quality> = emptyList(),
    ) : PageUndoAction

    /**
     * 1ページ分の品質判定の控え。単体で取り消し操作にはならず、
     * [Delete] の一部として戻る（重複の解消は削除と同じ1操作）。
     */
    data class Quality(
        val pageId: String,
        val qualityState: String,
    )

    data class Rotation(
        val pageId: String,
        val rotation: Int,
        val ocrState: String,
    ) : PageUndoAction

    data class Crop(
        val pageId: String,
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val ocrState: String,
    ) : PageUndoAction

    /**
     * One rotation + crop edit, however many pages its crop reached. Book-wide crop application
     * (FR-IMG-005/006) stays a single undoable operation this way.
     */
    data class Edit(
        val rotation: Rotation?,
        val crops: List<Crop>,
    ) : PageUndoAction
}

/** 判定値は Domain の [PageQualityState] が正本。data 層でも文字列を直書きしない */
private val NORMAL_QUALITY_STATE = PageQualityState.NORMAL.serializedName

private fun PageEntity.toQualityUndoAction() =
    PageUndoAction.Quality(pageId = id.toString(), qualityState = qualityState.serializedName)

private fun PageEntity.hasCrop(crop: PageCrop): Boolean =
    cropLeft == crop.left && cropTop == crop.top && cropRight == crop.right && cropBottom == crop.bottom

private fun PageEntity.toCropUndoAction() =
    PageUndoAction.Crop(
        pageId = id.toString(),
        left = cropLeft,
        top = cropTop,
        right = cropRight,
        bottom = cropBottom,
        ocrState = ocrState.serializedName,
    )
