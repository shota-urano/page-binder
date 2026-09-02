package com.pagebinder.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.pagebinder.app.domain.CaptureSessionLifecycle
import com.pagebinder.app.domain.OcrJobRunner
import com.pagebinder.app.domain.OcrQueueScheduler
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class PageBinderTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        context?.let(::deleteStateLeftOnDevice)
        TestPageBinderApplication.prepareForProcessStart()
        return super.newApplication(
            classLoader,
            TestPageBinderApplication::class.java.name,
            context,
        )
    }

    /**
     * 前回このアプリが端末へ残した本番の保存状態を、Application と ContentProvider
     * （WorkManager の InitializationProvider）が作られる前に消す。
     *
     * connectedAndroidTest はスイートの後にアンインストールするが、その間に `make run`（手動UI検証）を
     * 挟むと同意履歴・書籍DB・ページ画像・WorkManager の予約が端末へ残り、テスト用の再インストールでは
     * 消えない。MainActivity の起動経路はこれらをそのまま読むため、消さないと「同じテストが端末状態次第で
     * 別の画面・別の処理を通る」状態になる（pagebinder-ons）。
     *
     * ここで作るのは全テスト共通の出発点＝「初回インストール直後と同じ」だけ。
     * テスト固有の前提（同意済みかどうか等）は各テストが自分で用意する。
     */
    private fun deleteStateLeftOnDevice(context: Context) {
        val dataDirectory = File(context.applicationInfo.dataDir)
        listOf(
            // Room（pagebinder.db）
            File(dataDirectory, "databases"),
            // WorkManager（androidx.work.workdb — 前回の実行が予約した OCR ジョブが残る）
            File(dataDirectory, "no_backup"),
            // DataStore（consent / capture_settings）
            File(context.filesDir, "datastore"),
            // 撮影済みページ画像（storage/ProjectFileStore.kt の projects/）
            File(context.filesDir, "projects"),
        ).forEach(File::deleteRecursively)
    }
}

class TestPageBinderApplication : PageBinderApplication() {
    override val ocrJobRunner: OcrJobRunner
        get() = testOcrJobRunner ?: super.ocrJobRunner

    override fun createOcrQueueScheduler(): OcrQueueScheduler = startupScheduler

    companion object {
        private val startupWakeCalls = AtomicInteger()
        private val startupScheduler =
            object : OcrQueueScheduler, CaptureSessionLifecycle {
                override fun wake() {
                    startupWakeCalls.incrementAndGet()
                }

                override fun onSessionActive() = Unit

                override fun onSessionIdle() = Unit
            }

        @Volatile
        var testOcrJobRunner: OcrJobRunner? = null

        fun prepareForProcessStart() {
            startupWakeCalls.set(0)
        }

        fun processStartWakeCalls(): Int = startupWakeCalls.get()
    }
}
