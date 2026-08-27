package com.pagebinder.app.data

import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PageRepositoryEditingTest {
    private val projectId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val pageIds =
        (1..4).map { index ->
            UUID.fromString("20000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
        }
    private val repository = RoomPageRepository(InMemoryPageDao())

    @Test
    fun `reorder assigns contiguous sequences in requested order`() =
        runBlocking {
            insertPages(4)

            repository.reorder(projectId, listOf(pageIds[2], pageIds[0], pageIds[3], pageIds[1]))

            val pages = repository.findByProject(projectId)
            assertEquals(listOf(1, 2, 3, 4), pages.map(Page::sequence))
            assertEquals(listOf(pageIds[2], pageIds[0], pageIds[3], pageIds[1]), pages.map(Page::id))
        }

    @Test
    fun `multiple delete compacts remaining sequences`() =
        runBlocking {
            insertPages(4)

            repository.delete(projectId, setOf(pageIds[0], pageIds[2]))

            val pages = repository.findByProject(projectId)
            assertEquals(listOf(pageIds[1], pageIds[3]), pages.map(Page::id))
            assertEquals(listOf(1, 2), pages.map(Page::sequence))
        }

    @Test
    fun `rotation and crop changes mark OCR stale without changing source path`() =
        runBlocking {
            insertPages(2, PageOcrState.SUCCEEDED)
            val firstPath = requireNotNull(repository.findById(pageIds[0])).originalImagePath
            val secondPath = requireNotNull(repository.findById(pageIds[1])).originalImagePath
            val crop = PageCrop(left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.8f)

            repository.updateRotation(pageIds[0], 90)
            repository.updateCrop(pageIds[1], crop)

            val rotated = requireNotNull(repository.findById(pageIds[0]))
            val cropped = requireNotNull(repository.findById(pageIds[1]))
            assertEquals(90, rotated.rotation)
            assertEquals(PageOcrState.STALE, rotated.ocrState)
            assertEquals(firstPath, rotated.originalImagePath)
            assertEquals(crop, cropped.crop)
            assertEquals(PageOcrState.STALE, cropped.ocrState)
            assertEquals(secondPath, cropped.originalImagePath)
        }

    private suspend fun insertPages(
        count: Int,
        ocrState: PageOcrState = PageOcrState.PENDING,
    ) {
        repeat(count) { index -> repository.insert(page(index, ocrState)) }
    }

    private fun page(
        index: Int,
        ocrState: PageOcrState,
    ) = Page(
        id = pageIds[index],
        projectId = projectId,
        sequence = index + 1,
        originalImagePath = "projects/$projectId/images/${pageIds[index]}.webp",
        width = 1080,
        height = 1920,
        rotation = 0,
        crop = PageCrop(),
        capturedAt = Instant.parse("2026-08-27T01:02:03Z").plusSeconds(index.toLong()),
        contentHash = "content-$index",
        perceptualHash = "perceptual-$index",
        qualityState = PageQualityState.NORMAL,
        ocrState = ocrState,
    )
}

private class InMemoryPageDao : PageDao() {
    private val pages = mutableMapOf<String, PageEntity>()

    override suspend fun insert(page: PageEntity) {
        check(pages.putIfAbsent(page.id, page) == null)
    }

    override suspend fun findById(id: String): PageEntity? = pages[id]

    override suspend fun findByProject(projectId: String): List<PageEntity> =
        pages.values.filter { it.projectId == projectId }.sortedBy(PageEntity::sequence)

    override suspend fun updateSequence(
        id: String,
        sequence: Int,
    ): Int = update(id) { copy(sequence = sequence) }

    override suspend fun deleteByIds(ids: List<String>): Int = ids.count { pages.remove(it) != null }

    override suspend fun updateRotationAndMarkStale(
        id: String,
        rotation: Int,
    ): Int = update(id) { copy(rotation = rotation, ocrState = PageOcrState.STALE.serializedName) }

    override suspend fun updateCropAndMarkStale(
        id: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): Int =
        update(id) {
            copy(
                cropLeft = left,
                cropTop = top,
                cropRight = right,
                cropBottom = bottom,
                ocrState = PageOcrState.STALE.serializedName,
            )
        }

    private fun update(
        id: String,
        transform: PageEntity.() -> PageEntity,
    ): Int {
        val existing = pages[id] ?: return 0
        pages[id] = existing.transform()
        return 1
    }
}
