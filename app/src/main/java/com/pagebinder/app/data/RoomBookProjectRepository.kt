package com.pagebinder.app.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.pagebinder.app.domain.BookProject
import com.pagebinder.app.domain.BookProjectRepository
import com.pagebinder.app.domain.BookProjectRepositoryException
import com.pagebinder.app.domain.BookProjectSort
import com.pagebinder.app.domain.BookProjectSummary
import com.pagebinder.app.storage.ProjectFileStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.text.Normalizer
import java.time.Duration
import java.time.Instant
import java.util.Locale
import java.util.UUID

data class BookProjectAggregateEntity(
    @Embedded val project: BookProjectEntity,
    val pageCount: Int,
    val ocrCompletedCount: Int = 0,
    /** OCRの順番待ち（実行待ち＋実行中）。進捗表示を出すかどうかの判断に使う */
    val awaitingOcrCount: Int = 0,
    val ocrErrorCount: Int = 0,
)

@Dao
abstract class BookProjectDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    protected abstract suspend fun insert(project: BookProjectEntity)

    @Query("SELECT * FROM book_projects WHERE id = :id")
    abstract suspend fun findById(id: String): BookProjectEntity?

    @Query(
        """
        SELECT book_projects.*,
               COALESCE(SUM(CASE WHEN pages.quality_state != 'black' THEN 1 ELSE 0 END), 0) AS pageCount,
               COALESCE(SUM(CASE WHEN pages.ocr_state = 'succeeded' THEN 1 ELSE 0 END), 0) AS ocrCompletedCount,
               COALESCE(SUM(CASE WHEN pages.ocr_state IN ('pending', 'running') AND pages.quality_state != 'black' THEN 1 ELSE 0 END), 0) AS awaitingOcrCount,
               COALESCE(SUM(CASE WHEN pages.ocr_state = 'failed' AND pages.quality_state != 'black' THEN 1 ELSE 0 END), 0) AS ocrErrorCount
        FROM book_projects
        LEFT JOIN pages ON pages.project_id = book_projects.id
        WHERE book_projects.deleted_at IS NULL
        GROUP BY book_projects.id
        """,
    )
    abstract suspend fun listActive(): List<BookProjectAggregateEntity>

    @Query(
        """
        SELECT book_projects.*,
               COALESCE(SUM(CASE WHEN pages.quality_state != 'black' THEN 1 ELSE 0 END), 0) AS pageCount,
               COALESCE(SUM(CASE WHEN pages.ocr_state = 'succeeded' THEN 1 ELSE 0 END), 0) AS ocrCompletedCount,
               COALESCE(SUM(CASE WHEN pages.ocr_state IN ('pending', 'running') AND pages.quality_state != 'black' THEN 1 ELSE 0 END), 0) AS awaitingOcrCount,
               COALESCE(SUM(CASE WHEN pages.ocr_state = 'failed' AND pages.quality_state != 'black' THEN 1 ELSE 0 END), 0) AS ocrErrorCount
        FROM book_projects
        LEFT JOIN pages ON pages.project_id = book_projects.id
        WHERE book_projects.deleted_at IS NOT NULL
        GROUP BY book_projects.id
        ORDER BY book_projects.deleted_at DESC, book_projects.id
        """,
    )
    abstract suspend fun listTrash(): List<BookProjectAggregateEntity>

    @Query(
        """
        SELECT book_projects.*,
               COALESCE(SUM(CASE WHEN pages.quality_state != 'black' THEN 1 ELSE 0 END), 0) AS pageCount,
               COALESCE(SUM(CASE WHEN pages.ocr_state = 'succeeded' THEN 1 ELSE 0 END), 0) AS ocrCompletedCount,
               COALESCE(SUM(CASE WHEN pages.ocr_state IN ('pending', 'running') AND pages.quality_state != 'black' THEN 1 ELSE 0 END), 0) AS awaitingOcrCount,
               COALESCE(SUM(CASE WHEN pages.ocr_state = 'failed' AND pages.quality_state != 'black' THEN 1 ELSE 0 END), 0) AS ocrErrorCount
        FROM book_projects
        LEFT JOIN pages ON pages.project_id = book_projects.id
        WHERE book_projects.id = :id
        GROUP BY book_projects.id
        """,
    )
    abstract suspend fun findSummaryById(id: String): BookProjectAggregateEntity?

    /**
     * [findSummaryById] と同じ集計を購読する。`book_projects` と `pages` を読むので、
     * ページの追加・削除・OCR状態の更新で Room が再クエリして現在値を流し直す。
     */
    @Query(
        """
        SELECT book_projects.*,
               COALESCE(SUM(CASE WHEN pages.quality_state != 'black' THEN 1 ELSE 0 END), 0) AS pageCount,
               COALESCE(SUM(CASE WHEN pages.ocr_state = 'succeeded' THEN 1 ELSE 0 END), 0) AS ocrCompletedCount,
               COALESCE(SUM(CASE WHEN pages.ocr_state IN ('pending', 'running') AND pages.quality_state != 'black' THEN 1 ELSE 0 END), 0) AS awaitingOcrCount,
               COALESCE(SUM(CASE WHEN pages.ocr_state = 'failed' AND pages.quality_state != 'black' THEN 1 ELSE 0 END), 0) AS ocrErrorCount
        FROM book_projects
        LEFT JOIN pages ON pages.project_id = book_projects.id
        WHERE book_projects.id = :id
        GROUP BY book_projects.id
        """,
    )
    abstract fun observeSummaryById(id: String): Flow<BookProjectAggregateEntity?>

    @Query(
        """
        UPDATE book_projects
        SET title = :title, author = :author, note = :note, updated_at = :updatedAt
        WHERE id = :id
        """,
    )
    abstract suspend fun updateMetadata(
        id: String,
        title: String,
        author: String?,
        note: String?,
        updatedAt: String,
    ): Int

    @Query("UPDATE book_projects SET deleted_at = :deletedAt, updated_at = :deletedAt WHERE id = :id")
    abstract suspend fun moveToTrash(
        id: String,
        deletedAt: String,
    ): Int

    @Query("UPDATE book_projects SET deleted_at = NULL, updated_at = :updatedAt WHERE id = :id")
    abstract suspend fun restore(
        id: String,
        updatedAt: String,
    ): Int

    @Query("SELECT id FROM book_projects WHERE deleted_at IS NOT NULL AND deleted_at <= :cutoff ORDER BY deleted_at")
    abstract suspend fun findExpiredTrashIds(cutoff: String): List<String>

    @Query("DELETE FROM ocr_results WHERE page_id IN (SELECT id FROM pages WHERE project_id = :projectId)")
    protected abstract suspend fun deleteOcrResults(projectId: String)

    @Query("DELETE FROM pages WHERE project_id = :projectId")
    protected abstract suspend fun deletePages(projectId: String)

    @Query("DELETE FROM export_records WHERE project_id = :projectId")
    protected abstract suspend fun deleteExportRecords(projectId: String)

    @Query("DELETE FROM book_projects WHERE id = :projectId")
    protected abstract suspend fun deleteProject(projectId: String): Int

    @Transaction
    open suspend fun insertWithFileArea(
        project: BookProjectEntity,
        createFileArea: () -> Unit,
    ) {
        insert(project)
        createFileArea()
    }

    @Transaction
    open suspend fun deleteWithFileArea(
        projectId: String,
        deleteFileArea: () -> Unit,
    ): Boolean {
        if (findById(projectId) == null) return false
        deleteFileArea()
        deleteOcrResults(projectId)
        deletePages(projectId)
        deleteExportRecords(projectId)
        check(deleteProject(projectId) == 1) { "Book project delete did not affect one row" }
        return true
    }
}

class RoomBookProjectRepository(
    private val dao: BookProjectDao,
    private val fileStore: ProjectFileStore,
    private val now: () -> Instant = Instant::now,
    private val newId: () -> UUID = UUID::randomUUID,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : BookProjectRepository {
    override suspend fun create(
        title: String,
        author: String?,
        note: String?,
    ): BookProject {
        val timestamp = now()
        val project =
            BookProject(
                id = newId(),
                title = title,
                author = author,
                note = note,
                createdAt = timestamp,
                updatedAt = timestamp,
                deletedAt = null,
            )
        var fileAreaCreated = false
        var fileAreaCreationFailed = false
        try {
            dao.insertWithFileArea(project.toEntity()) {
                try {
                    fileStore.create(project.id)
                    fileAreaCreated = true
                } catch (failure: Exception) {
                    fileAreaCreationFailed = true
                    throw failure
                }
            }
        } catch (failure: Exception) {
            if (fileAreaCreated) runCatching { fileStore.delete(project.id) }
            if (failure is BookProjectRepositoryException) throw failure
            if (fileAreaCreationFailed) {
                throw BookProjectRepositoryException.FileAreaFailure(failure)
            }
            throw BookProjectRepositoryException.PersistenceFailure(failure)
        }
        return project
    }

    override suspend fun findById(id: UUID): BookProject? = dao.findById(id.toString())?.toDomain()

    override suspend fun findSummaryById(id: UUID): BookProjectSummary? =
        dao.findSummaryById(id.toString())?.toSummary()

    // 使用容量はファイル領域の実測（[ProjectFileStore.sizeBytes]）なので、集計と同じ購読の中で
    // 数え直す。ページ画像は DB 登録より先に書かれるため、再クエリ時点の容量は常に最新になる。
    override fun observeSummaryById(id: UUID): Flow<BookProjectSummary?> =
        dao
            .observeSummaryById(id.toString())
            .map { it?.toSummary() }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    override suspend fun update(
        id: UUID,
        title: String,
        author: String?,
        note: String?,
    ): BookProject {
        val current = findRequired(id)
        val updated =
            current.copy(
                title = title,
                author = author,
                note = note,
                updatedAt = nextTimestamp(current.updatedAt),
            )
        check(
            dao.updateMetadata(
                id = id.toString(),
                title = updated.title,
                author = updated.author,
                note = updated.note,
                updatedAt = updated.updatedAt.toString(),
            ) == 1,
        ) { "Book project update did not affect one row" }
        return updated
    }

    override suspend fun listActive(sort: BookProjectSort): List<BookProjectSummary> =
        dao.listActive().map { it.toSummary() }.sortedWith(sort.comparator())

    override suspend fun searchActive(
        query: String,
        sort: BookProjectSort,
    ): List<BookProjectSummary> {
        val normalizedQuery = query.normalizedForSearch()
        return listActive(sort).filter { summary ->
            normalizedQuery.isEmpty() ||
                summary.project.title.normalizedForSearch().contains(normalizedQuery) ||
                summary.project.author?.normalizedForSearch()?.contains(normalizedQuery) == true
        }
    }

    override suspend fun listTrash(): List<BookProjectSummary> = dao.listTrash().map { it.toSummary() }

    override suspend fun moveToTrash(id: UUID): BookProject {
        val current = findRequired(id)
        if (current.deletedAt != null) return current
        val timestamp = nextTimestamp(current.updatedAt)
        check(dao.moveToTrash(id.toString(), timestamp.toString()) == 1) {
            "Book project trash update did not affect one row"
        }
        return current.copy(updatedAt = timestamp, deletedAt = timestamp)
    }

    override suspend fun restore(id: UUID): BookProject {
        val current = findRequired(id)
        if (current.deletedAt == null) return current
        val timestamp = nextTimestamp(current.updatedAt)
        check(dao.restore(id.toString(), timestamp.toString()) == 1) {
            "Book project restore did not affect one row"
        }
        return current.copy(updatedAt = timestamp, deletedAt = null)
    }

    override suspend fun deletePermanently(id: UUID) {
        if (findRequired(id).deletedAt == null) {
            throw BookProjectRepositoryException.ProjectNotInTrash(id)
        }
        try {
            if (!dao.deleteWithFileArea(id.toString()) { fileStore.delete(id) }) {
                throw BookProjectRepositoryException.ProjectNotFound(id)
            }
        } catch (failure: BookProjectRepositoryException) {
            throw failure
        } catch (failure: Exception) {
            throw BookProjectRepositoryException.FileAreaFailure(failure)
        }
    }

    override suspend fun purgeExpiredTrash(): Int {
        val cutoff = now().minus(TRASH_RETENTION)
        val expiredIds = dao.findExpiredTrashIds(cutoff.toString())
        expiredIds.forEach { deletePermanently(UUID.fromString(it)) }
        return expiredIds.size
    }

    private suspend fun findRequired(id: UUID): BookProject =
        findById(id) ?: throw BookProjectRepositoryException.ProjectNotFound(id)

    private fun nextTimestamp(previous: Instant): Instant {
        val current = now()
        return if (current > previous) current else previous.plusNanos(1)
    }

    private fun BookProjectAggregateEntity.toSummary() =
        BookProjectSummary(
            project = project.toDomain(),
            pageCount = pageCount,
            storageBytes = fileStore.sizeBytes(project.id),
            ocrCompletedCount = ocrCompletedCount,
            awaitingOcrCount = awaitingOcrCount,
            ocrErrorCount = ocrErrorCount,
        )

    private companion object {
        val TRASH_RETENTION: Duration = Duration.ofDays(30)
    }
}

private fun BookProjectSort.comparator(): Comparator<BookProjectSummary> =
    when (this) {
        BookProjectSort.UPDATED_AT ->
            compareByDescending<BookProjectSummary> { it.project.updatedAt }.thenBy { it.project.id }
        BookProjectSort.CREATED_AT ->
            compareByDescending<BookProjectSummary> { it.project.createdAt }.thenBy { it.project.id }
        BookProjectSort.TITLE ->
            compareBy<BookProjectSummary> { it.project.title.normalizedForSearch() }.thenBy { it.project.id }
    }

private fun String.normalizedForSearch(): String =
    Normalizer
        .normalize(this, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .trim()
