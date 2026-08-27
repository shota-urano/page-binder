package com.pagebinder.app

import android.app.Application
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner
import com.pagebinder.app.domain.OcrJobRunner

class PageBinderTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        classLoader: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application =
        super.newApplication(
            classLoader,
            TestPageBinderApplication::class.java.name,
            context,
        )
}

class TestPageBinderApplication : PageBinderApplication() {
    override val ocrJobRunner: OcrJobRunner
        get() = testOcrJobRunner ?: super.ocrJobRunner

    companion object {
        @Volatile
        var testOcrJobRunner: OcrJobRunner? = null
    }
}
