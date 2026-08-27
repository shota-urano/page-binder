package com.pagebinder.app

import android.app.Application
import androidx.room.Room
import com.pagebinder.app.data.PageBinderDatabase
import com.pagebinder.app.data.RoomOcrJobRepository
import com.pagebinder.app.domain.CaptureSessionLifecycle
import com.pagebinder.app.domain.OcrImageSource
import com.pagebinder.app.domain.OcrJobRunner
import com.pagebinder.app.domain.OcrQueue
import com.pagebinder.app.ocr.AndroidOcrExecutionPolicy
import com.pagebinder.app.ocr.MlKitOcrGateway
import com.pagebinder.app.ocr.OcrWorkerDependencies
import com.pagebinder.app.ocr.WorkManagerOcrQueueScheduler
import com.pagebinder.app.ocr.safeOcrImageFile

class PageBinderApplication : Application(), OcrWorkerDependencies {
    private val database by lazy {
        Room.databaseBuilder(this, PageBinderDatabase::class.java, DATABASE_NAME).build()
    }
    private val repository by lazy { RoomOcrJobRepository(database.ocrJobDao()) }
    val ocrQueueScheduler by lazy { WorkManagerOcrQueueScheduler(this) }
    val ocrQueue by lazy { OcrQueue(repository, ocrQueueScheduler) }
    val captureSessionLifecycle: CaptureSessionLifecycle by lazy { ocrQueueScheduler }

    override val ocrJobRunner: OcrJobRunner by lazy {
        OcrJobRunner(
            repository = repository,
            gateway = MlKitOcrGateway(),
            imageSourceFactory = { relativePath ->
                val file = safeOcrImageFile(filesDir, relativePath)
                OcrImageSource(file::inputStream)
            },
            executionPolicy = AndroidOcrExecutionPolicy(this),
        )
    }

    override fun onCreate() {
        super.onCreate()
        ocrQueueScheduler.wake()
    }

    private companion object {
        const val DATABASE_NAME = "pagebinder.db"
    }
}
