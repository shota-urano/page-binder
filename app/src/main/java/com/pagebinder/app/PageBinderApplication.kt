package com.pagebinder.app

import android.app.Application
import com.pagebinder.app.capture.AndroidCaptureFeedbackGateway
import com.pagebinder.app.capture.AndroidCaptureGateway
import com.pagebinder.app.capture.CapturePageController
import com.pagebinder.app.capture.CaptureStatusNotifier
import com.pagebinder.app.capture.CaptureStatusPresenter
import com.pagebinder.app.data.createAutoCaptureSettingsRepository
import com.pagebinder.app.data.createCaptureFeedbackSettingsRepository
import com.pagebinder.app.domain.BookProjectRepository
import com.pagebinder.app.domain.CaptureFeedbackController
import com.pagebinder.app.domain.CaptureOnePage
import com.pagebinder.app.domain.CaptureOverlayGateway
import com.pagebinder.app.domain.CaptureSessionCoordinator
import com.pagebinder.app.domain.CaptureSessionLifecycle
import com.pagebinder.app.domain.CaptureStopReason
import com.pagebinder.app.domain.ExportRecordRepository
import com.pagebinder.app.domain.ExportStarter
import com.pagebinder.app.domain.InterruptedExport
import com.pagebinder.app.domain.OcrImageSource
import com.pagebinder.app.domain.OcrJobRepository
import com.pagebinder.app.domain.OcrJobRunner
import com.pagebinder.app.domain.OcrQueue
import com.pagebinder.app.domain.OcrQueueScheduler
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.export.ExportRecordCoordinator
import com.pagebinder.app.export.InterruptedExportDetector
import com.pagebinder.app.image.FilePageThumbnailLoader
import com.pagebinder.app.ocr.AndroidOcrExecutionPolicy
import com.pagebinder.app.ocr.MlKitOcrGateway
import com.pagebinder.app.ocr.OcrWorkerDependencies
import com.pagebinder.app.ocr.WorkManagerOcrQueueScheduler
import com.pagebinder.app.storage.FileImageStore
import com.pagebinder.app.ui.overlay.CaptureOverlayController
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltAndroidApp
open class PageBinderApplication : Application(), OcrWorkerDependencies {
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Inject lateinit var repository: OcrJobRepository

    @Inject lateinit var bookProjectRepository: BookProjectRepository

    @Inject lateinit var pageRepository: PageRepository

    @Inject lateinit var imageStore: FileImageStore

    /** 書き出し画面へ渡す本番の書き出し起動口（docs/specs/11-export.md §3.2） */
    @Inject lateinit var exportStarter: ExportStarter

    @Inject lateinit var exportRecordRepository: ExportRecordRepository

    private val interruptedExportDetector by lazy { InterruptedExportDetector(exportRecordRepository) }
    private val exportRecordCoordinator by lazy { ExportRecordCoordinator(exportRecordRepository) }

    val pageThumbnailLoader by lazy { FilePageThumbnailLoader(imageStore, pageRepository) }
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
            imageStore = imageStore,
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
                val file = imageStore.resolve(relativePath)
                OcrImageSource(file::inputStream)
            },
            executionPolicy = AndroidOcrExecutionPolicy(this),
        )
    }

    /**
     * 前回のプロセスが残した未完了の書き出しを、書籍プロジェクト単位で古い順に返す
     * （docs/specs/11-export.md §3.2 末尾「アプリ強制終了後、未完了の書き出しを検出して再試行できる」）。
     *
     * 書籍詳細画面を開くたびに呼ばれる。提示は毎回この検出結果に従うので、レコードが
     * 残っている限り提示も残る。保存URIは画面へ渡さない（AGENTS.md ルール6）。
     */
    suspend fun findInterruptedExports(projectId: UUID): List<InterruptedExport> =
        interruptedExportDetector
            .detect()
            .filter { it.projectId == projectId }
            .map { InterruptedExport(recordId = it.recordId, type = it.type) }

    /**
     * 再試行の書き出しが成功したので、取り残されていたレコードを終端させる（同 §3.2 手順6）。
     * これで次に書籍詳細を開いたときの検出から外れ、提示が消える。
     */
    suspend fun resolveInterruptedExport(recordId: UUID) {
        exportRecordCoordinator.markInterrupted(recordId)
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
