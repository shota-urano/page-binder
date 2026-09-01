package com.pagebinder.app.ocr

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.pagebinder.app.TestPageBinderApplication
import com.pagebinder.app.data.BookProjectEntity
import com.pagebinder.app.data.OcrJobDao
import com.pagebinder.app.data.OcrResultEntity
import com.pagebinder.app.data.PageBinderTypeConverters
import com.pagebinder.app.data.PageEntity
import com.pagebinder.app.data.RoomOcrJobRepository
import com.pagebinder.app.domain.OcrExecutionPolicy
import com.pagebinder.app.domain.OcrGateway
import com.pagebinder.app.domain.OcrImageSource
import com.pagebinder.app.domain.OcrJobRunner
import com.pagebinder.app.domain.OcrOutput
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OcrWorkerRestartTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext
    private val databaseName = "ocr-worker-restart-${UUID.randomUUID()}.db"
    private var reopenedDatabase: TestOcrDatabase? = null

    @Before
    fun setUpWorkManager() {
        val executor = SynchronousExecutor()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            targetContext,
            Configuration.Builder()
                .setExecutor(executor)
                .setTaskExecutor(executor)
                .build(),
        )
    }

    @After
    fun tearDown() {
        TestPageBinderApplication.testOcrJobRunner = null
        reopenedDatabase?.close()
        targetContext.deleteDatabase(databaseName)
    }

    @Test
    fun applicationOnCreateWakesTheOcrQueue() {
        assertEquals(1, TestPageBinderApplication.processStartWakeCalls())
    }

    @Test
    fun queueWakeReschedulesAndRecoversJobsAfterDatabaseReopen() =
        runBlocking {
            val firstDatabase = openDatabase()
            firstDatabase.setupDao().insertProject(
                BookProjectEntity(
                    id = UUID.fromString(PROJECT_ID),
                    title = "Test project",
                    author = null,
                    note = null,
                    createdAt = Instant.parse("2026-08-27T00:00:00Z"),
                    updatedAt = Instant.parse("2026-08-27T00:00:00Z"),
                    deletedAt = null,
                ),
            )
            firstDatabase.setupDao().insertPages(
                listOf(
                    pageEntity("10000000-0000-0000-0000-000000000001", "running", 1),
                    pageEntity("10000000-0000-0000-0000-000000000002", "pending", 2),
                ),
            )
            firstDatabase.close()

            val database = openDatabase().also { reopenedDatabase = it }
            TestPageBinderApplication.testOcrJobRunner =
                OcrJobRunner(
                    repository = RoomOcrJobRepository(database.ocrJobDao()),
                    gateway =
                        OcrGateway {
                            OcrOutput(
                                fullText = "recognized",
                                blocksJson = "{\"blocks\":[]}",
                                engineVersion = "test-engine",
                                sourceImageHash = "hash",
                            )
                        },
                    imageSourceFactory = { OcrImageSource { ByteArrayInputStream(byteArrayOf(1)) } },
                    executionPolicy = OcrExecutionPolicy { true },
                    now = { Instant.parse("2026-08-27T01:00:00Z") },
                )

            WorkManagerOcrQueueScheduler(targetContext).wake()

            val workManager = WorkManager.getInstance(targetContext)
            val startupWork = workManager.getWorkInfosForUniqueWork(OcrWorker.UNIQUE_WORK_NAME).get().single()
            WorkManagerTestInitHelper.getTestDriver(targetContext)!!.setAllConstraintsMet(startupWork.id)

            val completedWork =
                withTimeout(10_000L) {
                    var workInfo = requireNotNull(workManager.getWorkInfoById(startupWork.id).get())
                    while (!workInfo.state.isFinished) {
                        delay(10L)
                        workInfo = requireNotNull(workManager.getWorkInfoById(startupWork.id).get())
                    }
                    workInfo
                }
            assertEquals(WorkInfo.State.SUCCEEDED, completedWork.state)
            assertEquals(listOf("succeeded", "succeeded"), database.setupDao().states())
            assertEquals(2, database.setupDao().resultCount())
        }

    private fun openDatabase(): TestOcrDatabase =
        Room.databaseBuilder(targetContext, TestOcrDatabase::class.java, databaseName).build()

    private fun pageEntity(
        id: String,
        state: String,
        sequence: Int,
    ) = PageEntity(
        id = UUID.fromString(id),
        projectId = UUID.fromString(PROJECT_ID),
        sequence = sequence,
        originalImagePath = "projects/project/images/$id.webp",
        width = 100,
        height = 200,
        rotation = 0,
        cropLeft = 0f,
        cropTop = 0f,
        cropRight = 1f,
        cropBottom = 1f,
        capturedAt = Instant.parse("2026-08-27T00:00:0${sequence}Z"),
        contentHash = "content-$sequence",
        perceptualHash = "perceptual-$sequence",
        qualityState = PageQualityState.NORMAL,
        ocrState = PageOcrState.entries.single { it.serializedName == state },
    )
}

@Dao
internal interface TestOcrSetupDao {
    @Insert
    suspend fun insertProject(project: BookProjectEntity)

    @Insert
    suspend fun insertPages(pages: List<PageEntity>)

    @Query("SELECT ocr_state FROM pages ORDER BY sequence")
    suspend fun states(): List<String>

    @Query("SELECT COUNT(*) FROM ocr_results")
    suspend fun resultCount(): Int
}

@Database(
    entities = [BookProjectEntity::class, PageEntity::class, OcrResultEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(PageBinderTypeConverters::class)
internal abstract class TestOcrDatabase : RoomDatabase() {
    abstract fun ocrJobDao(): OcrJobDao

    abstract fun setupDao(): TestOcrSetupDao
}

private const val PROJECT_ID = "20000000-0000-0000-0000-000000000001"
