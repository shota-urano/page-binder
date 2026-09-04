package com.pagebinder.app.domain

import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.util.UUID

data class BookProject(
    val id: UUID,
    val title: String,
    val author: String?,
    val note: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
) {
    init {
        require(title.isNotBlank() && title.length <= 200) { "Book title must contain 1 to 200 characters" }
        require(author == null || author.length <= 200) { "Book author must contain at most 200 characters" }
        require(note == null || note.length <= 2_000) { "Book note must contain at most 2,000 characters" }
    }
}

data class BookProjectSummary(
    val project: BookProject,
    val pageCount: Int,
    val storageBytes: Long,
    val ocrCompletedCount: Int = 0,
    /**
     * OCRの順番待ち（実行待ち＋実行中）のページ数。
     * 0 でなければ「予約したがまだ終わっていない」状態で、書籍詳細はこの間だけ進捗を出す。
     */
    val awaitingOcrCount: Int = 0,
    val ocrErrorCount: Int = 0,
) {
    init {
        require(pageCount >= 0) { "Page count cannot be negative" }
        require(storageBytes >= 0) { "Storage size cannot be negative" }
        require(ocrCompletedCount >= 0) { "OCR completed count cannot be negative" }
        require(awaitingOcrCount >= 0) { "Awaiting OCR count cannot be negative" }
        require(ocrErrorCount >= 0) { "OCR error count cannot be negative" }
    }
}

enum class BookProjectSort {
    UPDATED_AT,
    CREATED_AT,
    TITLE,
}

interface BookProjectRepository {
    suspend fun create(
        title: String,
        author: String? = null,
        note: String? = null,
    ): BookProject

    suspend fun findById(id: UUID): BookProject?

    suspend fun findSummaryById(id: UUID): BookProjectSummary?

    /**
     * 書籍詳細の統計（ページ数・OCR完了数・エラー数・使用容量、docs/specs/03-book-project.md §3.4）を購読する。
     *
     * ページの追加・削除・OCR状態の変化のたびに現在値を流すので、撮影オーバーレイの裏で書籍詳細が
     * 前面に残ったままでも統計が更新される。書籍が存在しない場合は null を流す。
     */
    fun observeSummaryById(id: UUID): Flow<BookProjectSummary?>

    suspend fun update(
        id: UUID,
        title: String,
        author: String?,
        note: String?,
    ): BookProject

    suspend fun listActive(sort: BookProjectSort = BookProjectSort.UPDATED_AT): List<BookProjectSummary>

    suspend fun searchActive(
        query: String,
        sort: BookProjectSort = BookProjectSort.UPDATED_AT,
    ): List<BookProjectSummary>

    suspend fun listTrash(): List<BookProjectSummary>

    suspend fun moveToTrash(id: UUID): BookProject

    suspend fun restore(id: UUID): BookProject

    suspend fun deletePermanently(id: UUID)

    suspend fun purgeExpiredTrash(): Int
}

sealed class BookProjectRepositoryException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    class ProjectNotFound(id: UUID) : BookProjectRepositoryException("Book project not found: $id")

    class ProjectNotInTrash(id: UUID) : BookProjectRepositoryException("Book project is not in trash: $id")

    class FileAreaFailure(cause: Throwable) :
        BookProjectRepositoryException("Book project file operation failed", cause)

    class PersistenceFailure(cause: Throwable) :
        BookProjectRepositoryException("Book project persistence failed", cause)
}
