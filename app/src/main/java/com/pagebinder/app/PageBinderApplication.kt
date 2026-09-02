package com.pagebinder.app

import android.app.Application
import androidx.room.Room
import com.pagebinder.app.capture.AndroidCaptureFeedbackGateway
import com.pagebinder.app.capture.AndroidCaptureGateway
import com.pagebinder.app.capture.CapturePageController
import com.pagebinder.app.capture.CaptureStatusNotifier
import com.pagebinder.app.capture.CaptureStatusPresenter
import com.pagebinder.app.data.PageBinderDatabase
import com.pagebinder.app.data.RoomBookProjectRepository
import com.pagebinder.app.data.RoomOcrJobRepository
import com.pagebinder.app.data.RoomPageRepository
import com.pagebinder.app.data.createAutoCaptureSettingsRepository
import com.pagebinder.app.data.createCaptureFeedbackSettingsRepository
import com.pagebinder.app.domain.BookProjectRepository
import com.pagebinder.app.domain.CaptureFeedbackController
import com.pagebinder.app.domain.CaptureOnePage
import com.pagebinder.app.domain.CaptureOverlayGateway
import com.pagebinder.app.domain.CaptureSessionCoordinator
import com.pagebinder.app.domain.CaptureSessionLifecycle
import com.pagebinder.app.domain.CaptureStopReason
import com.pagebinder.app.domain.OcrImageSource
import com.pagebinder.app.domain.OcrJobRunner
import com.pagebinder.app.domain.OcrQueue
import com.pagebinder.app.domain.OcrQueueScheduler
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.image.FileCaptureImageStore
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
        Room
            .databaseBuilder(this, PageBinderDatabase::class.java, DATABASE_NAME)
            .addMigrations(PageBinderDatabase.MIGRATION_1_2)
            .build()
    }
    private val repository by lazy { RoomOcrJobRepository(database.ocrJobDao()) }
    val bookProjectRepository: BookProjectRepository by lazy {
        RoomBookProjectRepository(database.bookProjectDao(), FileProjectFileStore(filesDir))
    }
    val pageRepository: PageRepository by lazy { RoomPageRepository(database.pageDao()) }
    val pageThumbnailLoader by lazy { FilePageThumbnailLoader(filesDir, pageRepository) }
    val ocrQueueScheduler by lazy { createOcrQueueScheduler() }
    private val ocrQueueSessionLifecycle: CaptureSessionLifecycle by lazy {
        ocrQueueScheduler as CaptureSessionLifecycle
    }
    val ocrQueue by lazy { OcrQueue(repository, ocrQueueScheduler) }
    val captureSessionLifecycle: CaptureSessionLifecycle by lazy {
        object : CaptureSessionLifecycle {
            override fun onSessionActive() {
                ocrQueueSessionLifecycle.onSessionActive()
            }

            override fun onSessionIdle() {
                // This is invoked by the coordinator for every stop reason, including an OS
                // MediaProjection callback, independently of the service StateFlow observer.
                capturePageController.clear()
                ocrQueueSessionLifecycle.onSessionIdle()
            }
        }
    }
    val captureGateway by lazy { AndroidCaptureGateway(this) }
    val captureFeedbackSettingsRepository by lazy { createCaptureFeedbackSettingsRepository(this) }
    val autoCaptureSettingsRepository by lazy { createAutoCaptureSettingsRepository(this) }
    private val captureFeedbackController by lazy {
        CaptureFeedbackController(captureFeedbackSettingsRepository, AndroidCaptureFeedbackGateway(this))
    }
    private val captureOnePage by lazy {
        CaptureOnePage(
            captureGateway = captureGateway,
            overlayGateway = captureStatusPresenter,
            imageStore = FileCaptureImageStore(filesDir),
            pageRepository = pageRepository,
            ocrQueue = ocrQueue,
        )
    }
    val capturePageController by lazy {
        CapturePageController(
            scope = applicationScope,
            captureOnePage = captureOnePage,
            feedback = captureFeedbackController,
            captureGateway = captureGateway,
            overlayGateway = captureStatusPresenter,
            settingsRepository = autoCaptureSettingsRepository,
            lastSavedFingerprint = { projectId ->
                pageRepository
                    .findByProject(projectId)
                    .lastOrNull { page -> page.qualityState != com.pagebinder.app.domain.PageQualityState.BLACK }
                    ?.perceptualHash
            },
            stopSession = { captureSessionCoordinator.stop(CaptureStopReason.EXPLICIT) },
            onAutoStopped = { reason -> captureStatusNotifier.postAutoStopped(reason) },
        )
    }
    private val captureOverlayController: CaptureOverlayController by lazy {
        CaptureOverlayController(
            context = this,
            onCapture = {
                capturePageController.capture()
            },
            onPauseChanged = { paused ->
                capturePageController.setPaused(paused)
            },
            onStop = {
                applicationScope.launch {
                    captureSessionCoordinator.stop()
                    capturePageController.clear()
                }
            },
        )
    }

    /** 撮影状態はオーバーレイと常駐通知の両方に出す（docs/specs/06-auto-capture.md §3.4 / FR-AUTO-005） */
    val captureStatusNotifier: CaptureStatusNotifier by lazy { CaptureStatusNotifier(this) }
    private val captureStatusPresenter: CaptureOverlayGateway by lazy {
        CaptureStatusPresenter(captureOverlayController, captureStatusNotifier::post)
    }
    val captureSessionCoordinator: CaptureSessionCoordinator by lazy {
        CaptureSessionCoordinator(
            captureGateway = captureGateway,
            captureSessionLifecycle = captureSessionLifecycle,
            overlayGateway = captureStatusPresenter,
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

    /** Allows the instrumentation application to observe the process-start wake without WorkManager. */
    protected open fun createOcrQueueScheduler(): OcrQueueScheduler = WorkManagerOcrQueueScheduler(this)

    private companion object {
        const val DATABASE_NAME = "pagebinder.db"
    }
}
