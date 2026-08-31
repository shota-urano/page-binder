package com.pagebinder.app

import android.app.Application
import androidx.room.Room
import com.pagebinder.app.capture.AndroidCaptureGateway
import com.pagebinder.app.data.PageBinderDatabase
import com.pagebinder.app.data.RoomBookProjectRepository
import com.pagebinder.app.data.RoomOcrJobRepository
import com.pagebinder.app.data.RoomPageRepository
import com.pagebinder.app.domain.BookProjectRepository
import com.pagebinder.app.domain.CaptureOverlayState
import com.pagebinder.app.domain.CaptureSessionCoordinator
import com.pagebinder.app.domain.CaptureSessionLifecycle
import com.pagebinder.app.domain.OcrImageSource
import com.pagebinder.app.domain.OcrJobRunner
import com.pagebinder.app.domain.OcrQueue
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.image.FilePageThumbnailLoader
import com.pagebinder.app.ocr.AndroidOcrExecutionPolicy
import com.pagebinder.app.ocr.MlKitOcrGateway
import com.pagebinder.app.ocr.OcrWorkerDependencies
import com.pagebinder.app.ocr.WorkManagerOcrQueueScheduler
import com.pagebinder.app.ocr.safeOcrImageFile
import com.pagebinder.app.storage.FileProjectFileStore
import com.pagebinder.app.ui.overlay.CaptureOverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

open class PageBinderApplication : Application(), OcrWorkerDependencies {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val database by lazy {
        Room.databaseBuilder(this, PageBinderDatabase::class.java, DATABASE_NAME).build()
    }
    private val repository by lazy { RoomOcrJobRepository(database.ocrJobDao()) }
    val bookProjectRepository: BookProjectRepository by lazy {
        RoomBookProjectRepository(database.bookProjectDao(), FileProjectFileStore(filesDir))
    }
    val pageRepository: PageRepository by lazy { RoomPageRepository(database.pageDao()) }
    val pageThumbnailLoader by lazy { FilePageThumbnailLoader(filesDir, pageRepository) }
    val ocrQueueScheduler by lazy { WorkManagerOcrQueueScheduler(this) }
    val ocrQueue by lazy { OcrQueue(repository, ocrQueueScheduler) }
    val captureSessionLifecycle: CaptureSessionLifecycle by lazy { ocrQueueScheduler }
    val captureGateway by lazy { AndroidCaptureGateway(this) }
    val captureOverlayController: CaptureOverlayController by lazy {
        CaptureOverlayController(
            context = this,
            onCapture = {
                // The page-save flow belongs to docs/specs/05-manual-capture.md and is not started here.
            },
            onPauseChanged = { paused ->
                captureOverlayController.update(
                    if (paused) CaptureOverlayState.CONTINUOUS_PAUSED else CaptureOverlayState.CONTINUOUS_ACTIVE,
                )
            },
            onStop = {
                applicationScope.launch { captureSessionCoordinator.stop() }
            },
        )
    }
    val captureSessionCoordinator: CaptureSessionCoordinator by lazy {
        CaptureSessionCoordinator(
            captureGateway = captureGateway,
            captureSessionLifecycle = captureSessionLifecycle,
            overlayGateway = captureOverlayController,
            eventScope = applicationScope,
        )
    }

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
