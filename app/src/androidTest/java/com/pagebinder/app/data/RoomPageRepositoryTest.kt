package com.pagebinder.app.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageCropScope
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomPageRepositoryTest {
    private val projectId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val pageIds =
        (1..4).map { index ->
            UUID.fromString("20000000-0000-0000-0000-${index.toString().padStart(12, '0')}")
        }
    private lateinit var database: TestPageDatabase
    private lateinit var repository: RoomPageRepository

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                TestPageDatabase::class.java,
            ).build()
        repository = RoomPageRepository(database.pageDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun reorderReassignsContiguousSequences() =
        runBlocking {
            insertPages(4)

            repository.reorder(projectId, listOf(pageIds[2], pageIds[0], pageIds[3], pageIds[1]))

            val pages = repository.findByProject(projectId)
            assertEquals(listOf(1, 2, 3, 4), pages.map(Page::sequence))
            assertEquals(listOf(pageIds[2], pageIds[0], pageIds[3], pageIds[1]), pages.map(Page::id))
        }

    @Test
    fun multipleDeleteCompactsRemainingSequences() =
        runBlocking {
            insertPages(4)

            repository.delete(projectId, setOf(pageIds[0], pageIds[2]))

            val pages = repository.findByProject(projectId)
            assertEquals(listOf(pageIds[1], pageIds[3]), pages.map(Page::id))
            assertEquals(listOf(1, 2), pages.map(Page::sequence))
        }

    @Test
    fun rotationChangeMarksOcrStaleWithoutChangingImagePath() =
        runBlocking {
            insertPages(1, PageOcrState.SUCCEEDED)
            val originalPath = repository.findById(pageIds[0])?.originalImagePath

            repository.updateRotation(pageIds[0], 90)

            val updated = requireNotNull(repository.findById(pageIds[0]))
            assertEquals(90, updated.rotation)
            assertEquals(PageOcrState.STALE, updated.ocrState)
            assertEquals(originalPath, updated.originalImagePath)
        }

    @Test
    fun cropChangeMarksOcrStaleWithoutChangingImagePath() =
        runBlocking {
            insertPages(1, PageOcrState.FAILED)
            val crop = PageCrop(left = 0.1f, top = 0.2f, right = 0.9f, bottom = 0.8f)
            val originalPath = repository.findById(pageIds[0])?.originalImagePath

            repository.updateCrop(pageIds[0], crop)

            val updated = requireNotNull(repository.findById(pageIds[0]))
            assertEquals(crop, updated.crop)
            assertEquals(PageOcrState.STALE, updated.ocrState)
            assertEquals(originalPath, updated.originalImagePath)
        }

    @Test
    fun undoRestoresStateBeforeReorder() =
        runBlocking {
            insertPages(4)
            val before = repository.findByProject(projectId)

            repository.reorder(projectId, listOf(pageIds[2], pageIds[0], pageIds[3], pageIds[1]))

            assertTrue(repository.undoLastEdit())
            assertEquals(before, repository.findByProject(projectId))
            assertFalse(repository.undoLastEdit())
        }

    @Test
    fun undoRestoresDeletedPagesCompletely() =
        runBlocking {
            insertPages(4, PageOcrState.SUCCEEDED)
            repository.updateRotation(pageIds[2], 90)
            val before = repository.findByProject(projectId)

            repository.delete(projectId, setOf(pageIds[0], pageIds[2]))

            assertTrue(repository.undoLastEdit())
            assertEquals(before, repository.findByProject(projectId))
        }

    @Test
    fun undoRestoresRotationAndOcrState() =
        runBlocking {
            insertPages(1, PageOcrState.SUCCEEDED)
            val before = requireNotNull(repository.findById(pageIds[0]))

            repository.updateRotation(pageIds[0], 90)

            assertTrue(repository.undoLastEdit())
            assertEquals(before, repository.findById(pageIds[0]))
        }

    @Test
    fun undoRestoresCropAndOcrState() =
        runBlocking {
            insertPages(1, PageOcrState.FAILED)
            val before = requireNotNull(repository.findById(pageIds[0]))

            repository.updateCrop(pageIds[0], PageCrop(0.1f, 0.2f, 0.9f, 0.8f))

            assertTrue(repository.undoLastEdit())
            assertEquals(before, repository.findById(pageIds[0]))
        }

    @Test
    fun pageEditStoresRotationAndCropAsOneUndoableOperation() =
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

            assertTrue(repository.undoLastEdit())
            assertEquals(before, repository.findById(pageIds[0]))
            assertFalse(repository.undoLastEdit())
        }

    @Test
    fun projectWideCropAppliesToEveryPageAsOneUndoableOperation() =
        runBlocking {
            insertPages(4, PageOcrState.SUCCEEDED)
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

            assertEquals(4, applied)
            val pages = repository.findByProject(projectId)
            assertTrue(pages.all { it.crop == crop && it.ocrState == PageOcrState.STALE })
            assertEquals(90, pages.single { it.id == pageIds[0] }.rotation)

            // 書籍全体への適用でも取り消しは1操作（docs/specs/08-page-editing.md §3.4）
            assertTrue(repository.undoLastEdit())
            assertEquals(before, repository.findByProject(projectId))
            assertFalse(repository.undoLastEdit())
        }

    @Test
    fun pageEditRollsBackWhenTargetPageIsMissing() =
        runBlocking {
            insertPages(2, PageOcrState.SUCCEEDED)
            val before = repository.findByProject(projectId)

            // 存在しないページを指した保存では、1行も書き換わらない
            runCatching {
                repository.updatePageEdit(
                    pageId = UUID.fromString("20000000-0000-0000-0000-000000000099"),
                    rotation = 90,
                    crop = PageCrop(0.1f, 0.1f, 0.9f, 0.9f),
                    cropScope = PageCropScope.PROJECT,
                )
            }

            assertEquals(before, repository.findByProject(projectId))
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

@Database(
    entities = [PageEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TestPageDatabase : RoomDatabase() {
    abstract fun pageDao(): PageDao
}
