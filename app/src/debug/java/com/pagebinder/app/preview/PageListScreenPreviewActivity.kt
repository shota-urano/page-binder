package com.pagebinder.app.preview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.ui.pagelist.PageListRoute
import com.pagebinder.app.ui.pagelist.PageListViewModel
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader
import com.pagebinder.app.ui.pagelist.PageThumbnailRequest
import com.pagebinder.app.ui.theme.PageBinderTheme
import java.time.Instant
import java.util.UUID

/**
 * debug ビルド専用のページ一覧プレビュー。
 *
 * 撮影・OCR の配線が入るまでのあいだ、実機で画面を目視・スクリーンショットするための入口。
 * production の APK には含まれない。ここに置く値はプレビュー用の仮置きで、
 * production 側は UiState 経由で実データを描く。
 */
class PageListScreenPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PageBinderTheme {
                val pages = PREVIEW_PAGES
                val viewModel: PageListViewModel =
                    viewModel(
                        factory =
                            PageListViewModel.factory(
                                projectId = PREVIEW_PROJECT_ID,
                                pageRepository = PreviewPageRepository(pages),
                            ),
                    )
                PageListRoute(
                    viewModel = viewModel,
                    thumbnailLoader = PreviewThumbnailLoader(pages),
                    onBack = { finish() },
                    onPageOpened = {},
                    onDeleteSelectedRequested = {},
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                )
            }
        }
    }

    /** 読み出し専用の代役。一覧が使うのは findByProject だけ */
    private class PreviewPageRepository(private val pages: List<Page>) : PageRepository {
        override suspend fun insert(page: Page) = throw UnsupportedOperationException()

        override suspend fun findById(id: UUID): Page? = pages.firstOrNull { it.id == id }

        override suspend fun findByProject(projectId: UUID): List<Page> = pages

        override suspend fun reorder(
            projectId: UUID,
            orderedPageIds: List<UUID>,
        ) = throw UnsupportedOperationException()

        override suspend fun delete(
            projectId: UUID,
            pageIds: Set<UUID>,
        ) = throw UnsupportedOperationException()

        override suspend fun updateRotation(
            pageId: UUID,
            rotation: Int,
        ) = throw UnsupportedOperationException()

        override suspend fun updateCrop(
            pageId: UUID,
            crop: PageCrop,
        ) = throw UnsupportedOperationException()

        override suspend fun undoLastEdit(): Boolean = throw UnsupportedOperationException()
    }

    /**
     * 撮影画像の代わりに、文字の並びを模した無地の図形だけを描く。
     * 実在の書籍の文面は使わない（モックのサンプルデータを持ち込まない）。
     * rotation / crop はプレビューでは常に既定値なので適用しない。
     */
    private class PreviewThumbnailLoader(pages: List<Page>) : PageThumbnailLoader {
        private val blackPageIds =
            pages.filter { it.qualityState == PageQualityState.BLACK }.map(Page::id).toSet()

        override suspend fun load(request: PageThumbnailRequest): ImageBitmap? {
            val width = request.targetWidthPx.coerceAtLeast(MIN_THUMBNAIL_WIDTH_PX)
            val height = width * PREVIEW_ASPECT_DENOMINATOR / PREVIEW_ASPECT_NUMERATOR
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (request.pageId in blackPageIds) {
                canvas.drawColor(Color.BLACK)
                return bitmap.asImageBitmap()
            }
            canvas.drawColor(Color.WHITE)
            val paint = Paint().apply { isAntiAlias = true }
            val margin = width * MARGIN_RATIO
            var y = margin * 2f
            paint.color = HEADING_COLOR
            canvas.drawRoundRect(
                RectF(margin, y, width - margin * 3f, y + width * HEADING_HEIGHT_RATIO),
                CORNER_RADIUS,
                CORNER_RADIUS,
                paint,
            )
            y += width * HEADING_HEIGHT_RATIO + margin * 2f
            paint.color = BODY_COLOR
            val lineHeight = width * LINE_HEIGHT_RATIO
            var index = 0
            while (y + lineHeight < height - margin) {
                val short = index % SHORT_LINE_EVERY == SHORT_LINE_EVERY - 1
                val right = if (short) width * SHORT_LINE_RATIO else width - margin
                canvas.drawRoundRect(
                    RectF(margin, y, right, y + lineHeight),
                    CORNER_RADIUS,
                    CORNER_RADIUS,
                    paint,
                )
                y += lineHeight * LINE_GAP_RATIO
                index++
            }
            return bitmap.asImageBitmap()
        }
    }

    private companion object {
        val PREVIEW_PROJECT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000002")

        /** 各状態がひと通り見えるようにしたプレビュー用ページ */
        val PREVIEW_PAGES: List<Page> = previewPages()

        const val MIN_THUMBNAIL_WIDTH_PX = 120
        const val PREVIEW_ASPECT_NUMERATOR = 9
        const val PREVIEW_ASPECT_DENOMINATOR = 16
        const val MARGIN_RATIO = 0.08f
        const val HEADING_HEIGHT_RATIO = 0.09f
        const val LINE_HEIGHT_RATIO = 0.045f
        const val LINE_GAP_RATIO = 1.9f
        const val SHORT_LINE_RATIO = 0.6f
        const val SHORT_LINE_EVERY = 4
        const val CORNER_RADIUS = 2f
        const val HEADING_COLOR = 0xFF334155.toInt()
        const val BODY_COLOR = 0xFFCBD5E1.toInt()

        private fun previewPages(): List<Page> {
            val states =
                listOf(
                    PageOcrState.SUCCEEDED to PageQualityState.NORMAL,
                    PageOcrState.SUCCEEDED to PageQualityState.NORMAL,
                    PageOcrState.SUCCEEDED to PageQualityState.NORMAL,
                    PageOcrState.SUCCEEDED to PageQualityState.NORMAL,
                    PageOcrState.SUCCEEDED to PageQualityState.NORMAL,
                    PageOcrState.RUNNING to PageQualityState.NORMAL,
                    PageOcrState.SUCCEEDED to PageQualityState.DUPLICATE,
                    PageOcrState.PENDING to PageQualityState.BLACK,
                    PageOcrState.FAILED to PageQualityState.NORMAL,
                    PageOcrState.STALE to PageQualityState.NORMAL,
                    PageOcrState.PENDING to PageQualityState.NORMAL,
                    PageOcrState.SUCCEEDED to PageQualityState.NORMAL,
                )
            return states.mapIndexed { index, (ocrState, qualityState) ->
                val sequence = index + 1
                Page(
                    id = UUID.fromString("60000000-0000-4000-8000-${sequence.toString().padStart(12, '0')}"),
                    projectId = PREVIEW_PROJECT_ID,
                    sequence = sequence,
                    originalImagePath = "preview/$sequence.webp",
                    width = 1080,
                    height = 1920,
                    rotation = 0,
                    crop = PageCrop(),
                    capturedAt = Instant.parse("2026-08-26T00:00:00Z").plusSeconds(sequence.toLong()),
                    contentHash = "preview-content-$sequence",
                    perceptualHash = "preview-perceptual-$sequence",
                    qualityState = qualityState,
                    ocrState = ocrState,
                )
            }
        }
    }
}
