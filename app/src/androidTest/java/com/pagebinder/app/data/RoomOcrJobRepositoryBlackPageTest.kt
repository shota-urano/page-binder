package com.pagebinder.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.domain.OcrState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomOcrJobRepositoryBlackPageTest {
    private lateinit var database: PageBinderDatabase
    private lateinit var repository: RoomOcrJobRepository
    private val projectId = UUID.fromString("10000000-0000-0000-0000-000000000001")

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                PageBinderDatabase::class.java,
            ).build()
        runBlocking {
            database.bookProjectDao().insertWithFileArea(
                BookProjectEntity(
                    id = projectId.toString(),
                    title = "Test project",
                    author = null,
                    note = null,
                    createdAt = Instant.EPOCH.toString(),
                    updatedAt = Instant.EPOCH.toString(),
                    deletedAt = null,
                ),
            ) {}
        }
        repository = RoomOcrJobRepository(database.ocrJobDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun bulkEnqueueNeverChangesBlackCaptureToPending() =
        runBlocking {
            database.pageDao().insert(page("20000000-0000-0000-0000-000000000001", "black", "failed"))
            database.pageDao().insert(page("20000000-0000-0000-0000-000000000002", "normal", "failed"))

            assertEquals(1, repository.markProjectPending(projectId, setOf(OcrState.FAILED)))
            assertEquals("failed", database.pageDao().findById("20000000-0000-0000-0000-000000000001")?.ocrState)
            assertEquals("pending", database.pageDao().findById("20000000-0000-0000-0000-000000000002")?.ocrState)
        }

    @Test
    fun singleEnqueueNeverChangesBlackCaptureToPending() =
        runBlocking {
            val id = "20000000-0000-0000-0000-000000000001"
            database.pageDao().insert(page(id, "black", "failed"))

            assertEquals(false, repository.markPending(UUID.fromString(id), setOf(OcrState.FAILED)))
            assertEquals("failed", database.pageDao().findById(id)?.ocrState)
        }

    @Test
    fun existingPendingBlackCaptureIsNeverClaimedByWorker() =
        runBlocking {
            database.pageDao().insert(page("20000000-0000-0000-0000-000000000001", "black", "pending"))

            assertEquals(null, repository.claimNextPending())
        }

    @Test
    fun recoveryAndRetryNeverRequeueBlackCapture() =
        runBlocking {
            val id = "20000000-0000-0000-0000-000000000001"
            database.pageDao().insert(page(id, "black", "running"))

            assertEquals(0, repository.recoverInterrupted())
            assertEquals("running", database.pageDao().findById(id)?.ocrState)
            assertEquals(false, repository.returnToPending(UUID.fromString(id)))
            assertEquals("running", database.pageDao().findById(id)?.ocrState)
        }

    private fun page(
        id: String,
        qualityState: String,
        ocrState: String,
    ) = PageEntity(
        id = id,
        projectId = projectId.toString(),
        sequence = if (qualityState == "black") 1 else 2,
        originalImagePath = "projects/$projectId/images/$id.webp",
        width = 1,
        height = 1,
        rotation = 0,
        cropLeft = 0f,
        cropTop = 0f,
        cropRight = 1f,
        cropBottom = 1f,
        capturedAt = Instant.EPOCH.toString(),
        contentHash = id,
        perceptualHash = "0000000000000000",
        qualityState = qualityState,
        ocrState = ocrState,
    )
}
