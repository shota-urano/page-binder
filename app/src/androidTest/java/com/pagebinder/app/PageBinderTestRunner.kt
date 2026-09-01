package com.pagebinder.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.pagebinder.app.domain.CaptureSessionLifecycle
import com.pagebinder.app.domain.OcrJobRunner
import com.pagebinder.app.domain.OcrQueueScheduler
import java.util.concurrent.atomic.AtomicInteger

class PageBinderTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application {
        TestPageBinderApplication.prepareForProcessStart()
        return super.newApplication(
            classLoader,
            TestPageBinderApplication::class.java.name,
            context,
        )
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
