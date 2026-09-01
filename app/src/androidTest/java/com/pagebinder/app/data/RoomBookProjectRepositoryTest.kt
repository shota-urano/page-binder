package com.pagebinder.app.data

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.domain.BookProjectRepositoryException
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.storage.ProjectFileStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomBookProjectRepositoryTest {
    private val projectId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private lateinit var database: TestBookProjectDatabase

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                TestBookProjectDatabase::class.java,
            ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun fileAreaFailureRollsBackRoomInsert() {
        val repository =
            RoomBookProjectRepository(
                dao = database.bookProjectDao(),
                fileStore = FailingProjectFileStore,
                now = { Instant.parse("2026-08-30T00:00:00Z") },
                newId = { projectId },
            )

        assertThrows(BookProjectRepositoryException.FileAreaFailure::class.java) {
            runBlocking { repository.create("Rollback", null, null) }
        }

        runBlocking {
            assertNull(database.bookProjectDao().findById(projectId.toString()))
        }
    }

    @Test
    fun pageCountIsAggregatedByRoomQuery() =
        runBlocking {
            val fileStore = EmptyProjectFileStore()
            val repository =
                RoomBookProjectRepository(
                    dao = database.bookProjectDao(),
                    fileStore = fileStore,
                    now = { Instant.parse("2026-08-30T00:00:00Z") },
                    newId = { projectId },
                )
            repository.create("Counted", null, null)
            database.pageDao().insert(testPage(projectId, 1))
            database.pageDao().insert(testPage(projectId, 2))

            assertEquals(2, repository.listActive().single().pageCount)
        }

    @Test
    fun ocrCountsAreAggregatedByRoomQuery() =
        runBlocking {
            val repository =
                RoomBookProjectRepository(
                    dao = database.bookProjectDao(),
                    fileStore = EmptyProjectFileStore(),
                    now = { Instant.parse("2026-08-30T00:00:00Z") },
                    newId = { projectId },
                )
            repository.create("OCR counts", null, null)
            database.pageDao().insert(testPage(projectId, 1, "succeeded"))
            database.pageDao().insert(testPage(projectId, 2, "succeeded"))
            database.pageDao().insert(testPage(projectId, 3, "failed"))
            database.pageDao().insert(testPage(projectId, 4, "pending"))

            val summary = requireNotNull(repository.findSummaryById(projectId))

            assertEquals(4, summary.pageCount)
            assertEquals(2, summary.ocrCompletedCount)
            assertEquals(1, summary.ocrErrorCount)
        }

    @Test
    fun blackCaptureDoesNotIncreaseOcrErrorCount() =
        runBlocking {
            val repository =
                RoomBookProjectRepository(
                    dao = database.bookProjectDao(),
                    fileStore = EmptyProjectFileStore(),
                    now = { Instant.parse("2026-08-30T00:00:00Z") },
                    newId = { projectId },
                )
            repository.create("Black", null, null)
            database.pageDao().insert(testPage(projectId, 1, ocrState = "failed", qualityState = "black"))

            assertEquals(0, requireNotNull(repository.findSummaryById(projectId)).ocrErrorCount)
        }
}

private object FailingProjectFileStore : ProjectFileStore {
    override fun create(projectId: UUID) {
        throw IOException("Simulated failure")
    }

    override fun delete(projectId: UUID) = Unit

    override fun sizeBytes(projectId: UUID): Long = 0L
}

private class EmptyProjectFileStore : ProjectFileStore {
    override fun create(projectId: UUID) = Unit

    override fun delete(projectId: UUID) = Unit

    override fun sizeBytes(projectId: UUID): Long = 0L
}

private fun testPage(
    projectId: UUID,
    sequence: Int,
    ocrState: String = "pending",
    qualityState: String = "normal",
) = PageEntity(
    id = UUID.fromString("20000000-0000-0000-0000-${sequence.toString().padStart(12, '0')}"),
    projectId = projectId,
    sequence = sequence,
    originalImagePath = "projects/$projectId/images/$sequence.webp",
    width = 100,
    height = 200,
    rotation = 0,
    cropLeft = 0f,
    cropTop = 0f,
    cropRight = 1f,
    cropBottom = 1f,
    capturedAt = Instant.parse("2026-08-30T00:00:00Z"),
    contentHash = "content-$sequence",
    perceptualHash = "perceptual-$sequence",
    qualityState = PageQualityState.entries.single { it.serializedName == qualityState },
    ocrState = PageOcrState.entries.single { it.serializedName == ocrState },
)

@Database(
    entities = [
        BookProjectEntity::class,
        PageEntity::class,
        OcrResultEntity::class,
        ExportRecordEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(PageBinderTypeConverters::class)
abstract class TestBookProjectDatabase : RoomDatabase() {
    abstract fun bookProjectDao(): BookProjectDao

    abstract fun pageDao(): PageDao
}
