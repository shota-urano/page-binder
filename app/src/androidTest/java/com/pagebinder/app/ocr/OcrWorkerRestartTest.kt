package com.pagebinder.app.ocr

import android.app.Application
import android.app.Instrumentation
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.pagebinder.app.data.OcrJobDao
import com.pagebinder.app.data.OcrResultEntity
import com.pagebinder.app.data.PageEntity
import com.pagebinder.app.data.RoomOcrJobRepository
import com.pagebinder.app.domain.OcrExecutionPolicy
import com.pagebinder.app.domain.OcrGateway
import com.pagebinder.app.domain.OcrImageSource
import com.pagebinder.app.domain.OcrJobRunner
import com.pagebinder.app.domain.OcrOutput
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OcrWorkerRestartTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val databaseName = "ocr-worker-restart-${UUID.randomUUID()}.db"
    private var reopenedDatabase: TestOcrDatabase? = null

    @After
    fun tearDown() {
        reopenedDatabase?.close()
        targetContext.deleteDatabase(databaseName)
    }

    @Test
    fun workManagerWorkerRecoversJobsAfterApplicationAndDatabaseReopen() =
        runBlocking {
            val firstDatabase = openDatabase()
            firstDatabase.setupDao().insertPages(
                listOf(
                    pageEntity("10000000-0000-0000-0000-000000000001", "running", 1),
                    pageEntity("10000000-0000-0000-0000-000000000002", "pending", 2),
                ),
            )
            firstDatabase.close()

            val database = openDatabase().also { reopenedDatabase = it }
            val runner =
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
            val recreatedApplication =
                Instrumentation
                    .newApplication(RestartedOcrApplication::class.java, targetContext)
                    .let { it as RestartedOcrApplication }
                    .also { it.runner = runner }
            val worker = TestListenableWorkerBuilder<OcrWorker>(recreatedApplication).build()

            assertEquals(ListenableWorker.Result.success(), worker.startWork().get())
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
        id = id,
        projectId = "20000000-0000-0000-0000-000000000001",
        sequence = sequence,
        originalImagePath = "projects/project/images/$id.webp",
        width = 100,
        height = 200,
        rotation = 0,
        cropLeft = 0f,
        cropTop = 0f,
        cropRight = 1f,
        cropBottom = 1f,
        capturedAt = "2026-08-27T00:00:0${sequence}Z",
        contentHash = "content-$sequence",
        perceptualHash = "perceptual-$sequence",
        qualityState = "accepted",
        ocrState = state,
    )
}

internal class RestartedOcrApplication : Application(), OcrWorkerDependencies {
    lateinit var runner: OcrJobRunner

    override val ocrJobRunner: OcrJobRunner
        get() = runner
}

@Dao
internal interface TestOcrSetupDao {
    @Insert
    suspend fun insertPages(pages: List<PageEntity>)

    @Query("SELECT ocr_state FROM pages ORDER BY sequence")
    suspend fun states(): List<String>

    @Query("SELECT COUNT(*) FROM ocr_results")
    suspend fun resultCount(): Int
}

@Database(
    entities = [PageEntity::class, OcrResultEntity::class],
    version = 1,
    exportSchema = false,
)
internal abstract class TestOcrDatabase : RoomDatabase() {
    abstract fun ocrJobDao(): OcrJobDao

    abstract fun setupDao(): TestOcrSetupDao
}
