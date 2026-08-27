package com.pagebinder.app.preview

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagebinder.app.domain.ExportOptions
import com.pagebinder.app.domain.ExportProgressEvent
import com.pagebinder.app.domain.ExportProgressPhase
import com.pagebinder.app.domain.ExportProjectSummary
import com.pagebinder.app.domain.ExportStarter
import com.pagebinder.app.ui.export.ExportRoute
import com.pagebinder.app.ui.export.ExportViewModel
import com.pagebinder.app.ui.theme.PageBinderTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID

/**
 * debug ビルド専用の書き出し画面プレビュー。
 *
 * 書籍詳細画面（docs/design/03-book-detail.md）と Export Engine の配線が入るまでのあいだ、
 * 実機で画面を目視・スクリーンショットするための入口。production の APK には含まれない。
 * ここに置く値はプレビュー用の仮置きであり、production 側は UiState 経由で実データを描く。
 */
class ExportScreenPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PageBinderTheme {
                val viewModel: ExportViewModel =
                    viewModel(
                        factory = ExportViewModel.factory(PREVIEW_PROJECT, PreviewExportStarter()),
                    )
                ExportRoute(
                    viewModel = viewModel,
                    onBack = { finish() },
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                )
            }
        }
    }

    /** 保存先が決まったあとの進捗表示を確認するための代役。実処理はしない */
    private class PreviewExportStarter : ExportStarter {
        override fun startExport(options: ExportOptions): Flow<ExportProgressEvent> =
            flow {
                emit(ExportProgressEvent.Progress(ExportProgressPhase.QUEUED, 0, PREVIEW_UNITS))
                repeat(PREVIEW_UNITS) { index ->
                    delay(PREVIEW_STEP_MILLIS)
                    emit(
                        ExportProgressEvent.Progress(
                            ExportProgressPhase.GENERATING,
                            index + 1,
                            PREVIEW_UNITS,
                        ),
                    )
                }
                delay(PREVIEW_STEP_MILLIS)
                emit(ExportProgressEvent.Progress(ExportProgressPhase.WRITING, 0, 1))
                delay(PREVIEW_STEP_MILLIS)
                emit(ExportProgressEvent.Succeeded)
            }
    }

    private companion object {
        val PREVIEW_PROJECT =
            ExportProjectSummary(
                projectId = UUID.fromString("00000000-0000-4000-8000-000000000001"),
                title = "プレビュー用の書籍",
                pageCount = 128,
                ocrIncompletePageCount = 3,
            )
        const val PREVIEW_UNITS = 8
        const val PREVIEW_STEP_MILLIS = 400L
    }
}
