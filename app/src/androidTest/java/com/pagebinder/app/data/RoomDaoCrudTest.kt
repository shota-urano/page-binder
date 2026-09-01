package com.pagebinder.app.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

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
            assertEquals(project, dao.findById(project.id))

            assertEquals(1, dao.updateMetadata(project.id, "Updated", "Author", "Note", "2026-09-01T00:01:00Z"))
            assertEquals("Updated", dao.findById(project.id)?.title)

            assertEquals(true, dao.deleteWithFileArea(project.id) {})
            assertNull(dao.findById(project.id))
        }

    @Test
    fun pageDaoCreatesReadsUpdatesAndDeletes() =
        runBlocking {
            val dao = database.pageDao()
            val page = pageEntity()

            dao.insert(page)
            assertEquals(page, dao.findById(page.id))

            dao.updateRotation(page.id, 90)
            assertEquals(90, dao.findById(page.id)?.rotation)

            dao.deleteAndCompact(page.projectId, setOf(page.id))
            assertNull(dao.findById(page.id))
        }

    @Test
    fun ocrResultDaoCreatesReadsUpdatesAndDeletes() =
        runBlocking {
            val ocrJobDao = database.ocrJobDao()
            val dao = database.ocrResultDao()
            val result = ocrResultEntity()

            ocrJobDao.upsertResult(result)
            assertEquals(result, dao.findByPageId(result.pageId))

            assertEquals(1, dao.updateEditedText(result.pageId, "Edited text"))
            assertEquals("Edited text", dao.findByPageId(result.pageId)?.editedText)

            assertEquals(1, dao.deleteByPageId(result.pageId))
            assertNull(dao.findByPageId(result.pageId))
        }

    @Test
    fun exportRecordDaoCreatesReadsUpdatesAndDeletes() =
        runBlocking {
            val dao = database.exportRecordDao()
            val record = exportRecordEntity()

            dao.insert(record)
            assertEquals(record, dao.findById(record.id))

            assertEquals(
                1,
                dao.compareAndSet(
                    expectedId = record.id,
                    expectedProjectId = record.projectId,
                    expectedType = record.type,
                    expectedTargetUri = record.targetUri,
                    expectedState = record.state,
                    expectedCreatedAt = record.createdAt,
                    expectedCompletedAt = record.completedAt,
                    expectedErrorCode = record.errorCode,
                    updatedProjectId = record.projectId,
                    updatedType = record.type,
                    updatedTargetUri = "content://provider/document/redacted",
                    updatedState = "running",
                    updatedCreatedAt = record.createdAt,
                    updatedCompletedAt = null,
                    updatedErrorCode = null,
                ),
            )
            assertEquals("running", dao.findById(record.id)?.state)

            assertEquals(1, dao.deleteById(record.id))
            assertNull(dao.findById(record.id))
        }

    private fun bookProjectEntity() =
        BookProjectEntity(
            id = PROJECT_ID,
            title = "Test project",
            author = null,
            note = null,
            createdAt = CREATED_AT,
            updatedAt = CREATED_AT,
            deletedAt = null,
        )

    private fun pageEntity() =
        PageEntity(
            id = PAGE_ID,
            projectId = PROJECT_ID,
            sequence = 1,
            originalImagePath = "projects/$PROJECT_ID/images/$PAGE_ID.webp",
            width = 1080,
            height = 1920,
            rotation = 0,
            cropLeft = 0f,
            cropTop = 0f,
            cropRight = 1f,
            cropBottom = 1f,
            capturedAt = CREATED_AT,
            contentHash = "content-hash",
            perceptualHash = "perceptual-hash",
            qualityState = "normal",
            ocrState = "pending",
        )

    private fun ocrResultEntity() =
        OcrResultEntity(
            pageId = PAGE_ID,
            fullText = "Recognized text",
            blocksJson = "{\"schemaVersion\":1,\"blocks\":[]}",
            editedText = null,
            engineVersion = "test-engine",
            sourceImageHash = "source-hash",
            processedAt = CREATED_AT,
        )

    private fun exportRecordEntity() =
        ExportRecordEntity(
            id = EXPORT_ID,
            projectId = PROJECT_ID,
            type = "markdown",
            targetUri = null,
            state = "queued",
            createdAt = CREATED_AT,
            completedAt = null,
            errorCode = null,
        )

    private companion object {
        const val PROJECT_ID = "10000000-0000-0000-0000-000000000001"
        const val PAGE_ID = "20000000-0000-0000-0000-000000000002"
        const val EXPORT_ID = "30000000-0000-0000-0000-000000000003"
        const val CREATED_AT = "2026-09-01T00:00:00Z"
    }
}
