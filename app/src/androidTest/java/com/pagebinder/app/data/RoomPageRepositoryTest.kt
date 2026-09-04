package com.pagebinder.app.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageCropScope
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
        runBlocking {
            database.projectDao().insert(
                BookProjectEntity(
                    id = projectId,
                    title = "Test project",
                    author = null,
                    note = null,
                    createdAt = Instant.parse("2026-08-27T01:02:03Z"),
                    updatedAt = Instant.parse("2026-08-27T01:02:03Z"),
                    deletedAt = null,
                ),
            )
        }
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

    /**
     * pagebinder-3my: 一覧を開いたまま撮影したページが入ること。
     * 一度読みの実装ではここで空のまま止まり、アプリを再起動するまで一覧に出なかった。
     */
    @Test
    fun observedPagesReEmitWhenCaptureInsertsAndDeletes() =
        runBlocking {
            val emissions = Channel<List<Page>>(Channel.UNLIMITED)
            val collectJob = launch { repository.observeByProject(projectId).collect(emissions::send) }
            try {
                assertTrue(emissions.awaitPages(List<Page>::isEmpty).isEmpty())

                insertPages(3)

                val captured = emissions.awaitPages { it.size == 3 }
                assertEquals(listOf(1, 2, 3), captured.map(Page::sequence))

                repository.delete(projectId, setOf(pageIds[0]))

                val remaining = emissions.awaitPages { it.size == 2 }
                assertEquals(listOf(pageIds[1], pageIds[2]), remaining.map(Page::id))
            } finally {
                collectJob.cancel()
            }
        }

    /**
     * pagebinder-dy7: ocr_results は pages への外部キーを持たないので、ページ削除で一緒に消さないと
     * 孤児行が残る。取り消しではページと同じ1操作でOCR結果も戻る。
     */
    @Test
    fun deletingPagesRemovesTheirOcrResultsAndUndoRestoresThem() =
        runBlocking {
            insertPages(3, PageOcrState.SUCCEEDED)
            pageIds.take(3).forEach { database.testOcrResultDao().insert(ocrResult(it)) }

            repository.delete(projectId, setOf(pageIds[0], pageIds[1]))

            assertNull(database.ocrResultDao().findByPageId(pageIds[0].toString()))
            assertNull(database.ocrResultDao().findByPageId(pageIds[1].toString()))
            assertNotNull(database.ocrResultDao().findByPageId(pageIds[2].toString()))

            assertTrue(repository.undoLastEdit())

            assertEquals(3, repository.findByProject(projectId).size)
            assertEquals(
                "text-${pageIds[0]}",
                database.ocrResultDao().findByPageId(pageIds[0].toString())?.fullText,
            )
        }

    private fun ocrResult(pageId: UUID) =
        OcrResultEntity(
            pageId = pageId,
            fullText = "text-$pageId",
            blocksJson = "{}",
            editedText = null,
            engineVersion = "test-1",
            sourceImageHash = "hash-$pageId",
            processedAt = Instant.parse("2026-08-27T01:02:03Z"),
        )

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

private suspend fun ReceiveChannel<List<Page>>.awaitPages(predicate: (List<Page>) -> Boolean): List<Page> =
    withTimeout(OBSERVE_TIMEOUT_MILLIS) {
        var value = receive()
        while (!predicate(value)) value = receive()
        value
    }

private const val OBSERVE_TIMEOUT_MILLIS = 5_000L

@Database(
    entities = [BookProjectEntity::class, PageEntity::class, OcrResultEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(PageBinderTypeConverters::class)
abstract class TestPageDatabase : RoomDatabase() {
    abstract fun projectDao(): TestProjectDao

    abstract fun pageDao(): PageDao

    abstract fun ocrResultDao(): OcrResultDao

    abstract fun testOcrResultDao(): TestOcrResultDao
}

/** 本番の [OcrResultDao] は書き込みを edited_text に限っているので、テストの用意用に別口を置く */
@Dao
interface TestOcrResultDao {
    @Insert
    suspend fun insert(result: OcrResultEntity)
}

@Dao
interface TestProjectDao {
    @Insert
    suspend fun insert(project: BookProjectEntity)
}
