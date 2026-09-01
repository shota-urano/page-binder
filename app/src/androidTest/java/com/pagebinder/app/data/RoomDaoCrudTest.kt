package com.pagebinder.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.domain.ExportState
import com.pagebinder.app.domain.ExportType
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RoomDaoCrudTest {
    private lateinit var database: PageBinderDatabase

    @Before
    fun setUp() {
        database =
            Room.inMemoryDatabaseBuilder(
                InstrumentationRegistry.getInstrumentation().targetContext,
                PageBinderDatabase::class.java,
            ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun bookProjectDaoCreatesReadsUpdatesAndDeletes() =
        runBlocking {
            val dao = database.bookProjectDao()
            val project = bookProjectEntity()

            dao.insertWithFileArea(project) {}
            assertEquals(project, dao.findById(project.id.toString()))

            assertEquals(
                1,
                dao.updateMetadata(project.id.toString(), "Updated", "Author", "Note", "2026-09-01T00:01:00Z"),
            )
            assertEquals("Updated", dao.findById(project.id.toString())?.title)

            assertEquals(true, dao.deleteWithFileArea(project.id.toString()) {})
            assertNull(dao.findById(project.id.toString()))
        }

    @Test
    fun pageDaoCreatesReadsUpdatesAndDeletes() =
        runBlocking {
            val dao = database.pageDao()
            val page = pageEntity()

            database.bookProjectDao().insertWithFileArea(bookProjectEntity()) {}
            dao.insert(page)
            assertEquals(page, dao.findById(page.id.toString()))

            dao.updateRotation(page.id.toString(), 90)
            assertEquals(90, dao.findById(page.id.toString())?.rotation)

            dao.deleteAndCompact(page.projectId.toString(), setOf(page.id.toString()))
            assertNull(dao.findById(page.id.toString()))
        }

    @Test
    fun pageDaoRejectsPageWhoseProjectDoesNotExist() {
        assertThrows(SQLiteConstraintException::class.java) {
            runBlocking {
                database.pageDao().insert(pageEntity(projectId = UUID.fromString(UNKNOWN_PROJECT_ID)))
            }
        }
    }

    @Test
    fun ocrResultDaoCreatesReadsUpdatesAndDeletes() =
        runBlocking {
            val ocrJobDao = database.ocrJobDao()
            val dao = database.ocrResultDao()
            val result = ocrResultEntity()

            ocrJobDao.upsertResult(result)
            assertEquals(result, dao.findByPageId(result.pageId.toString()))

            assertEquals(1, dao.updateEditedText(result.pageId.toString(), "Edited text"))
            assertEquals("Edited text", dao.findByPageId(result.pageId.toString())?.editedText)

            assertEquals(1, dao.deleteByPageId(result.pageId.toString()))
            assertNull(dao.findByPageId(result.pageId.toString()))
        }

    @Test
    fun exportRecordDaoCreatesReadsUpdatesAndDeletes() =
        runBlocking {
            val dao = database.exportRecordDao()
            val record = exportRecordEntity()

            dao.insert(record)
            assertEquals(record, dao.findById(record.id.toString()))

            assertEquals(
                1,
                dao.compareAndSet(
                    expectedId = record.id.toString(),
                    expectedProjectId = record.projectId.toString(),
                    expectedType = record.type.serializedName,
                    expectedTargetUri = record.targetUri,
                    expectedState = record.state.serializedName,
                    expectedCreatedAt = record.createdAt.toString(),
                    expectedCompletedAt = record.completedAt?.toString(),
                    expectedErrorCode = record.errorCode,
                    updatedProjectId = record.projectId.toString(),
                    updatedType = record.type.serializedName,
                    updatedTargetUri = "content://provider/document/redacted",
                    updatedState = "running",
                    updatedCreatedAt = record.createdAt.toString(),
                    updatedCompletedAt = null,
                    updatedErrorCode = null,
                ),
            )
            assertEquals(ExportState.RUNNING, dao.findById(record.id.toString())?.state)

            assertEquals(1, dao.deleteById(record.id.toString()))
            assertNull(dao.findById(record.id.toString()))
        }

    private fun bookProjectEntity() =
        BookProjectEntity(
            id = UUID.fromString(PROJECT_ID),
            title = "Test project",
            author = null,
            note = null,
            createdAt = Instant.parse(CREATED_AT),
            updatedAt = Instant.parse(CREATED_AT),
            deletedAt = null,
        )

    private fun pageEntity(projectId: UUID = UUID.fromString(PROJECT_ID)) =
        PageEntity(
            id = UUID.fromString(PAGE_ID),
            projectId = projectId,
            sequence = 1,
            originalImagePath = "projects/$projectId/images/$PAGE_ID.webp",
            width = 1080,
            height = 1920,
            rotation = 0,
            cropLeft = 0f,
            cropTop = 0f,
            cropRight = 1f,
            cropBottom = 1f,
            capturedAt = Instant.parse(CREATED_AT),
            contentHash = "content-hash",
            perceptualHash = "perceptual-hash",
            qualityState = PageQualityState.NORMAL,
            ocrState = PageOcrState.PENDING,
        )

    private fun ocrResultEntity() =
        OcrResultEntity(
            pageId = UUID.fromString(PAGE_ID),
            fullText = "Recognized text",
            blocksJson = "{\"schemaVersion\":1,\"blocks\":[]}",
            editedText = null,
            engineVersion = "test-engine",
            sourceImageHash = "source-hash",
            processedAt = Instant.parse(CREATED_AT),
        )

    private fun exportRecordEntity() =
        ExportRecordEntity(
            id = UUID.fromString(EXPORT_ID),
            projectId = UUID.fromString(PROJECT_ID),
            type = ExportType.MARKDOWN,
            targetUri = null,
            state = ExportState.QUEUED,
            createdAt = Instant.parse(CREATED_AT),
            completedAt = null,
            errorCode = null,
        )

    private companion object {
        const val PROJECT_ID = "10000000-0000-0000-0000-000000000001"
        const val PAGE_ID = "20000000-0000-0000-0000-000000000002"
        const val EXPORT_ID = "30000000-0000-0000-0000-000000000003"
        const val UNKNOWN_PROJECT_ID = "40000000-0000-0000-0000-000000000004"
        const val CREATED_AT = "2026-09-01T00:00:00Z"
    }
}
