package com.pagebinder.app.data

import com.pagebinder.app.domain.BookProjectRepositoryException
import com.pagebinder.app.domain.BookProjectSort
import com.pagebinder.app.storage.ProjectFileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.UUID

class BookProjectRepositoryTest {
    private val firstId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val secondId = UUID.fromString("10000000-0000-0000-0000-000000000002")
    private val dao = InMemoryBookProjectDao()
    private val fileStore = InMemoryProjectFileStore()
    private var currentTime = Instant.parse("2026-08-30T00:00:00Z")
    private val ids = ArrayDeque(listOf(firstId, secondId))
    private val repository =
        RoomBookProjectRepository(
            dao = dao,
            fileStore = fileStore,
            now = { currentTime },
            newId = { ids.removeFirst() },
        )

    @Test
    fun `create update trash restore and permanent delete preserve lifecycle`() =
        runBlocking {
            val created = repository.create("Initial", "Author", "Note")

            assertEquals(firstId, created.id)
            assertEquals(created, repository.findById(firstId))
            assertTrue(fileStore.contains(firstId))

            currentTime = currentTime.plusSeconds(1)
            val updated = repository.update(firstId, "Updated", null, "Revised")
            assertEquals("Updated", updated.title)
            assertEquals(currentTime, updated.updatedAt)

            currentTime = currentTime.plusSeconds(1)
            val trashed = repository.moveToTrash(firstId)
            assertEquals(currentTime, trashed.deletedAt)
            assertTrue(repository.listActive().isEmpty())
            assertEquals(listOf(firstId), repository.listTrash().map { it.project.id })

            currentTime = currentTime.plusSeconds(1)
            val restored = repository.restore(firstId)
            assertNull(restored.deletedAt)
            assertEquals(listOf(firstId), repository.listActive().map { it.project.id })

            currentTime = currentTime.plusSeconds(1)
            repository.moveToTrash(firstId)
            repository.deletePermanently(firstId)
            assertNull(repository.findById(firstId))
            assertFalse(fileStore.contains(firstId))
        }

    @Test
    fun `permanent delete rejects active project and preserves data`() =
        runBlocking {
            repository.create("Active", null, null)

            assertThrows(BookProjectRepositoryException.ProjectNotInTrash::class.java) {
                runBlocking { repository.deletePermanently(firstId) }
            }

            assertEquals(firstId, repository.findById(firstId)?.id)
            assertTrue(fileStore.contains(firstId))
        }

    @Test
    fun `blank title is rejected before database and file creation`() {
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository.create("   ", null, null) }
        }

        runBlocking {
            assertNull(dao.findById(firstId.toString()))
            assertFalse(fileStore.contains(firstId))
        }
    }

    @Test
    fun `search normalizes case and character width and excludes note and trash`() =
        runBlocking {
            repository.create("ＡＢＣ Guide", "Ｔｅｓｔ Author", "hidden-keyword")
            currentTime = currentTime.plusSeconds(1)
            repository.create("Other", "Someone", "ABC")

            assertEquals(listOf(firstId), repository.searchActive("abc").map { it.project.id })
            assertEquals(listOf(firstId), repository.searchActive("test author").map { it.project.id })
            assertTrue(repository.searchActive("hidden-keyword").isEmpty())

            repository.moveToTrash(firstId)
            assertTrue(repository.searchActive("ＡＢＣ").isEmpty())
        }

    @Test
    fun `listing applies requested order and aggregates page count and storage`() =
        runBlocking {
            repository.create("Zulu", null, null)
            currentTime = currentTime.plusSeconds(1)
            repository.create("Ａlpha", null, null)
            dao.setPageCount(firstId, 3)
            fileStore.setSize(firstId, 1_024L)

            val byUpdated = repository.listActive()
            assertEquals(listOf(secondId, firstId), byUpdated.map { it.project.id })

            val byCreated = repository.listActive(BookProjectSort.CREATED_AT)
            assertEquals(listOf(secondId, firstId), byCreated.map { it.project.id })

            val byTitle = repository.listActive(BookProjectSort.TITLE)
            assertEquals(listOf(secondId, firstId), byTitle.map { it.project.id })
            assertEquals(3, byTitle.single { it.project.id == firstId }.pageCount)
            assertEquals(1_024L, byTitle.single { it.project.id == firstId }.storageBytes)
        }

    @Test
    fun `file area creation failure rolls back inserted database record`() {
        fileStore.failNextCreate = true

        assertThrows(BookProjectRepositoryException.FileAreaFailure::class.java) {
            runBlocking { repository.create("Cannot create", null, null) }
        }

        runBlocking {
            assertNull(dao.findById(firstId.toString()))
            assertFalse(fileStore.contains(firstId))
        }
    }

    @Test
    fun `purge removes only trash older than thirty days`() =
        runBlocking {
            repository.create("Expired", null, null)
            repository.moveToTrash(firstId)
            currentTime = currentTime.plusSeconds(1)
            repository.create("Recent", null, null)
            currentTime = currentTime.plusSeconds(29L * 24 * 60 * 60)
            repository.moveToTrash(secondId)
            currentTime = Instant.parse("2026-09-30T00:00:00Z")

            assertEquals(1, repository.purgeExpiredTrash())
            assertNull(repository.findById(firstId))
            assertEquals(secondId, repository.findById(secondId)?.id)
        }
}

private class InMemoryBookProjectDao : BookProjectDao() {
    private val projects = linkedMapOf<String, BookProjectEntity>()
    private val pageCounts = mutableMapOf<String, Int>()

    fun setPageCount(
        projectId: UUID,
        count: Int,
    ) {
        pageCounts[projectId.toString()] = count
    }

    override suspend fun insertWithFileArea(
        project: BookProjectEntity,
        createFileArea: () -> Unit,
    ) {
        val snapshot = projects.toMap()
        runCatching { super.insertWithFileArea(project, createFileArea) }
            .onFailure {
                projects.clear()
                projects.putAll(snapshot)
            }.getOrThrow()
    }

    override suspend fun insert(project: BookProjectEntity) {
        check(projects.putIfAbsent(project.id.toString(), project) == null)
    }

    override suspend fun findById(id: String): BookProjectEntity? = projects[id]

    override suspend fun listActive(): List<BookProjectAggregateEntity> =
        projects.values
            .filter { it.deletedAt == null }
            .map { BookProjectAggregateEntity(it, pageCounts[it.id.toString()] ?: 0) }

    override suspend fun listTrash(): List<BookProjectAggregateEntity> =
        projects.values
            .filter { it.deletedAt != null }
            .sortedByDescending { it.deletedAt }
            .map { BookProjectAggregateEntity(it, pageCounts[it.id.toString()] ?: 0) }

    override suspend fun findSummaryById(id: String): BookProjectAggregateEntity? =
        projects[id]?.let { BookProjectAggregateEntity(it, pageCounts[id] ?: 0) }

    override fun observeSummaryById(id: String): Flow<BookProjectAggregateEntity?> = flow { emit(findSummaryById(id)) }

    override suspend fun updateMetadata(
        id: String,
        title: String,
        author: String?,
        note: String?,
        updatedAt: String,
    ): Int = update(id) { copy(title = title, author = author, note = note, updatedAt = Instant.parse(updatedAt)) }

    override suspend fun moveToTrash(
        id: String,
        deletedAt: String,
    ): Int = update(id) { copy(updatedAt = Instant.parse(deletedAt), deletedAt = Instant.parse(deletedAt)) }

    override suspend fun restore(
        id: String,
        updatedAt: String,
    ): Int = update(id) { copy(updatedAt = Instant.parse(updatedAt), deletedAt = null) }

    override suspend fun findExpiredTrashIds(cutoff: String): List<String> =
        projects.values
            .filter { it.deletedAt != null && it.deletedAt <= Instant.parse(cutoff) }
            .sortedBy { it.deletedAt }
            .map { it.id.toString() }

    override suspend fun deleteOcrResults(projectId: String) = Unit

    override suspend fun deletePages(projectId: String) {
        pageCounts.remove(projectId)
    }

    override suspend fun deleteExportRecords(projectId: String) = Unit

    override suspend fun deleteProject(projectId: String): Int = if (projects.remove(projectId) != null) 1 else 0

    private fun update(
        id: String,
        transform: BookProjectEntity.() -> BookProjectEntity,
    ): Int {
        val current = projects[id] ?: return 0
        projects[id] = current.transform()
        return 1
    }
}

private class InMemoryProjectFileStore : ProjectFileStore {
    private val projects = mutableSetOf<UUID>()
    private val sizes = mutableMapOf<UUID, Long>()
    var failNextCreate = false

    override fun create(projectId: UUID) {
        if (failNextCreate) {
            failNextCreate = false
            throw IOException("Simulated file area creation failure")
        }
        check(projects.add(projectId))
    }

    override fun delete(projectId: UUID) {
        projects.remove(projectId)
        sizes.remove(projectId)
    }

    override fun sizeBytes(projectId: UUID): Long = sizes[projectId] ?: 0L

    fun contains(projectId: UUID): Boolean = projectId in projects

    fun setSize(
        projectId: UUID,
        bytes: Long,
    ) {
        sizes[projectId] = bytes
    }
}
