package com.pagebinder.app.domain

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
) {
    init {
        require(pageCount >= 0) { "Page count cannot be negative" }
        require(storageBytes >= 0) { "Storage size cannot be negative" }
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
