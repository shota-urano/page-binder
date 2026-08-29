package com.pagebinder.app.data

import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageCropScope
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PageRepositoryEditingTest {
    private val projectId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val pageIds =
        (1..4).map { index ->
            UUID.fromString("20000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
        }
    private val dao = InMemoryPageDao()
    private val repository = RoomPageRepository(dao)

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

    @Test
    fun `undo restores order before reorder`() =
        runBlocking {
            insertPages(4)
            val before = repository.findByProject(projectId)

            repository.reorder(projectId, listOf(pageIds[2], pageIds[0], pageIds[3], pageIds[1]))

            assertTrue(repository.undoLastEdit())
            assertEquals(before, repository.findByProject(projectId))
            assertFalse(repository.undoLastEdit())
        }

    @Test
    fun `undo restores every deleted page attribute and sequence`() =
        runBlocking {
            insertPages(4, PageOcrState.SUCCEEDED)
            repository.updateRotation(pageIds[2], 90)
            val before = repository.findByProject(projectId)

            repository.delete(projectId, setOf(pageIds[0], pageIds[2]))

            assertTrue(repository.undoLastEdit())
            assertEquals(before, repository.findByProject(projectId))
        }

    @Test
    fun `undo restores rotation and OCR state`() =
        runBlocking {
            insertPages(1, PageOcrState.SUCCEEDED)
            val before = requireNotNull(repository.findById(pageIds[0]))

            repository.updateRotation(pageIds[0], 90)

            assertTrue(repository.undoLastEdit())
            assertEquals(before, repository.findById(pageIds[0]))
        }

    @Test
    fun `undo restores crop and OCR state`() =
        runBlocking {
            insertPages(1, PageOcrState.FAILED)
            val before = requireNotNull(repository.findById(pageIds[0]))

            repository.updateCrop(pageIds[0], PageCrop(0.1f, 0.2f, 0.9f, 0.8f))

            assertTrue(repository.undoLastEdit())
            assertEquals(before, repository.findById(pageIds[0]))
        }

    @Test
    fun `new edit replaces previous undo history`() =
        runBlocking {
            insertPages(2, PageOcrState.SUCCEEDED)

            repository.updateRotation(pageIds[0], 90)
            repository.updateCrop(pageIds[1], PageCrop(0.1f, 0.2f, 0.9f, 0.8f))

            assertTrue(repository.undoLastEdit())
            assertEquals(90, repository.findById(pageIds[0])?.rotation)
            assertEquals(PageCrop(), repository.findById(pageIds[1])?.crop)
            assertFalse(repository.undoLastEdit())
        }

    @Test
    fun `page edit stores rotation and crop as one undoable operation`() =
        runBlocking {
            insertPages(2, PageOcrState.SUCCEEDED)
            val before = repository.findById(pageIds[0])
            val crop = PageCrop(0.1f, 0.2f, 0.9f, 0.8f)

            val applied = repository.updatePageEdit(pageIds[0], rotation = 90, crop = crop)

            assertEquals(1, applied)
            val edited = requireNotNull(repository.findById(pageIds[0]))
            assertEquals(90, edited.rotation)
            assertEquals(crop, edited.crop)
            assertEquals(PageOcrState.STALE, edited.ocrState)

            // 回転と切り取りは1操作。取り消しで両方が同時に戻る
            assertTrue(repository.undoLastEdit())
            assertEquals(before, repository.findById(pageIds[0]))
            assertFalse(repository.undoLastEdit())
        }

    @Test
    fun `project wide crop applies to every page as one undoable operation`() =
        runBlocking {
            insertPages(3, PageOcrState.SUCCEEDED)
            repository.updateRotation(pageIds[1], 180)
            val before = repository.findByProject(projectId)
            val crop = PageCrop(0.05f, 0.05f, 0.95f, 0.95f)

            val applied =
                repository.updatePageEdit(
                    pageId = pageIds[0],
                    rotation = 90,
                    crop = crop,
                    cropScope = PageCropScope.PROJECT,
                )

            assertEquals(3, applied)
            val pages = repository.findByProject(projectId)
            assertTrue(pages.all { it.crop == crop && it.ocrState == PageOcrState.STALE })
            assertEquals(90, pages.single { it.id == pageIds[0] }.rotation)
            // 一括適用でも取り消しは1件。全ページが元の crop と OCR 状態へ戻る
            assertTrue(repository.undoLastEdit())
            assertEquals(before, repository.findByProject(projectId))
            assertFalse(repository.undoLastEdit())
        }

    @Test
    fun `project wide crop leaves no page changed when one page fails`() =
        runBlocking {
            insertPages(3, PageOcrState.SUCCEEDED)
            val before = repository.findByProject(projectId)
            dao.failCropUpdatesFor(pageIds[2].toString())

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    repository.updatePageEdit(
                        pageId = pageIds[0],
                        rotation = 90,
                        crop = PageCrop(0.05f, 0.05f, 0.95f, 0.95f),
                        cropScope = PageCropScope.PROJECT,
                    )
                }
            }

            // 途中で失敗したら1ページも書き換わらず、取り消し履歴も増えない
            assertEquals(before, repository.findByProject(projectId))
            assertFalse(repository.undoLastEdit())
        }

    @Test
    fun `page edit leaves rotation unchanged when its crop fails`() =
        runBlocking {
            insertPages(1, PageOcrState.SUCCEEDED)
            val before = repository.findById(pageIds[0])
            dao.failCropUpdatesFor(pageIds[0].toString())

            assertThrows(IllegalStateException::class.java) {
                runBlocking {
                    repository.updatePageEdit(
                        pageId = pageIds[0],
                        rotation = 90,
                        crop = PageCrop(0.1f, 0.2f, 0.9f, 0.8f),
                    )
                }
            }

            // 回転だけが保存に残ると、破棄して閉じても向きが戻らなくなる
            assertEquals(before, repository.findById(pageIds[0]))
            assertFalse(repository.undoLastEdit())
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
    private val failingCropIds = mutableSetOf<String>()

    /** 指定ページの切り取り更新を「1行も更新できなかった」状態にする（書き込み途中の失敗を作る） */
    fun failCropUpdatesFor(id: String) {
        failingCropIds += id
    }

    /**
     * production の [PageDao.updatePageEdit] には `@Transaction` が付いていて、途中で落ちれば
     * それまでの書き込みごと巻き戻る。代役でもその約束を写しておく
     * （巻き戻らない代役だと、原子性のテストが代役の都合で通ってしまう）。
     */
    override suspend fun updatePageEdit(
        pageId: String,
        rotation: Int,
        crop: PageCrop,
        projectWideCrop: Boolean,
    ): Int {
        val snapshot = pages.toMap()
        return runCatching { super.updatePageEdit(pageId, rotation, crop, projectWideCrop) }
            .onFailure {
                pages.clear()
                pages.putAll(snapshot)
            }.getOrThrow()
    }

    override suspend fun insert(page: PageEntity) {
        check(pages.putIfAbsent(page.id, page) == null)
    }

    override suspend fun insertAll(pages: List<PageEntity>) {
        pages.forEach { insert(it) }
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
        if (id in failingCropIds) {
            0
        } else {
            update(id) {
                copy(
                    cropLeft = left,
                    cropTop = top,
                    cropRight = right,
                    cropBottom = bottom,
                    ocrState = PageOcrState.STALE.serializedName,
                )
            }
        }

    override suspend fun restoreRotation(
        id: String,
        rotation: Int,
        ocrState: String,
    ): Int = update(id) { copy(rotation = rotation, ocrState = ocrState) }

    override suspend fun restoreCrop(
        id: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        ocrState: String,
    ): Int =
        update(id) {
            copy(
                cropLeft = left,
                cropTop = top,
                cropRight = right,
                cropBottom = bottom,
                ocrState = ocrState,
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
