package com.pagebinder.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagebinder.app.capture.CaptureForegroundService
import com.pagebinder.app.data.createConsentRepository
import com.pagebinder.app.domain.CaptureMode
import com.pagebinder.app.ui.PageBinderApp
import com.pagebinder.app.ui.consent.ConsentViewModel
import com.pagebinder.app.ui.theme.PageBinderTheme
import com.pagebinder.app.ui.captureprep.CaptureMode as UiCaptureMode

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PageBinderTheme {
                PageBinderRoot()
            }
        }
    }
}

@Composable
private fun PageBinderRoot() {
    val context = LocalContext.current
    val consentRepository = remember(context) { createConsentRepository(context) }
    val application = context.applicationContext as PageBinderApplication
    val viewModel: ConsentViewModel = viewModel(factory = ConsentViewModel.factory(consentRepository))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PageBinderApp(
        uiState = uiState,
        onAgree = viewModel::onAgree,
        onDecline = viewModel::onDecline,
        bookProjectRepository = application.bookProjectRepository,
        pageRepository = application.pageRepository,
        pageThumbnailLoader = application.pageThumbnailLoader,
        exportStarter = application.exportStarter,
        enqueueProjectOcr = application.ocrQueue::enqueueProject,
        findInterruptedExports = application::findInterruptedExports,
        resolveInterruptedExport = application::resolveInterruptedExport,
        autoCaptureSettingsRepository = application.autoCaptureSettingsRepository,
        captureFeedbackSettingsRepository = application.captureFeedbackSettingsRepository,
        startCapture = { projectId, request ->
            CaptureForegroundService.start(
                context = context,
                resultCode = request.resultCode,
                resultData = request.permissionData,
                mode =
                    when (request.mode) {
                        UiCaptureMode.MANUAL -> CaptureMode.MANUAL
                        UiCaptureMode.CONTINUOUS -> CaptureMode.CONTINUOUS
                    },
                projectId = projectId,
                autoCaptureSettings = request.autoCaptureSettings,
            )
        },
    )
}
