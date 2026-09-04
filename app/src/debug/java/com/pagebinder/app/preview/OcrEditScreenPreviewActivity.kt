package com.pagebinder.app.preview

import android.graphics.Bitmap
import android.graphics.Canvas
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
import com.pagebinder.app.domain.OcrJobRepository
import com.pagebinder.app.domain.OcrPage
import com.pagebinder.app.domain.OcrQueue
import com.pagebinder.app.domain.OcrResultRepository
import com.pagebinder.app.domain.OcrState
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageCropScope
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.domain.StoredOcrResult
import com.pagebinder.app.ui.ocredit.OcrEditRoute
import com.pagebinder.app.ui.ocredit.OcrEditViewModel
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader
import com.pagebinder.app.ui.pagelist.PageThumbnailRequest
import com.pagebinder.app.ui.theme.PageBinderTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.util.UUID

/**
 * debug ビルド専用のOCR編集画面プレビュー。
 *
 * 撮影・OCR の配線が入るまでのあいだ、実機で画面を目視・スクリーンショットするための入口。
 * production の APK には含まれない。ここに置く値はプレビュー用の仮置きで、
 * production 側は UiState 経由で実データを描く。
 */
class OcrEditScreenPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PageBinderTheme {
                val viewModel: OcrEditViewModel =
                    viewModel(
                        factory =
                            OcrEditViewModel.factory(
                                pageId = PREVIEW_PAGE_ID,
                                pageRepository = PreviewPageRepository(PREVIEW_PAGE),
                                ocrResultRepository = PreviewOcrResultRepository(PREVIEW_RESULT),
                                ocrQueue = OcrQueue(PreviewOcrJobRepository()) { },
                            ),
                    )
                OcrEditRoute(
                    viewModel = viewModel,
                    imageLoader = PreviewPageImageLoader(),
                    onBack = { finish() },
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                )
            }
        }
    }

    /** 読み出し専用の代役。この画面が使うのは findById だけ */
    private class PreviewPageRepository(private val page: Page) : PageRepository {
        override suspend fun insert(page: Page) = throw UnsupportedOperationException()

        override suspend fun findById(id: UUID): Page? = page.takeIf { it.id == id }

        override suspend fun findByProject(projectId: UUID): List<Page> = listOf(page)

        override fun observeByProject(projectId: UUID): Flow<List<Page>> = flowOf(listOf(page))

        override suspend fun reorder(
            projectId: UUID,
            orderedPageIds: List<UUID>,
        ) = throw UnsupportedOperationException()

        override suspend fun delete(
            projectId: UUID,
            pageIds: Set<UUID>,
        ) = throw UnsupportedOperationException()

        override suspend fun deleteResolvingDuplicates(
            projectId: UUID,
            pageIds: Set<UUID>,
            resolvedDuplicatePageIds: Set<UUID>,
        ) = throw UnsupportedOperationException()

        override suspend fun updateRotation(
            pageId: UUID,
            rotation: Int,
        ) = throw UnsupportedOperationException()

        override suspend fun updateCrop(
            pageId: UUID,
            crop: PageCrop,
        ) = throw UnsupportedOperationException()

        override suspend fun updatePageEdit(
            pageId: UUID,
            rotation: Int,
            crop: PageCrop,
            cropScope: PageCropScope,
        ): Int = throw UnsupportedOperationException()

        override suspend fun undoLastEdit(): Boolean = throw UnsupportedOperationException()
    }

    /** 保存はメモリ上だけ。editedText しか差し替えないのは production の口と同じ */
    private class PreviewOcrResultRepository(private var stored: StoredOcrResult?) : OcrResultRepository {
        override suspend fun findByPageId(pageId: UUID): StoredOcrResult? = stored?.takeIf { it.pageId == pageId }

        override suspend fun saveEditedText(
            pageId: UUID,
            editedText: String,
        ): Boolean = updateEditedText(pageId, editedText)

        override suspend fun clearEditedText(pageId: UUID): Boolean = updateEditedText(pageId, null)

        private fun updateEditedText(
            pageId: UUID,
            editedText: String?,
        ): Boolean {
            val current = stored?.takeIf { it.pageId == pageId } ?: return false
            stored = current.copy(editedText = editedText)
            return true
        }
    }

    /** 再実行を受けても何もしない代役 */
    private class PreviewOcrJobRepository : OcrJobRepository {
        override suspend fun markPending(
            pageId: UUID,
            expectedStates: Set<OcrState>,
        ): Boolean = true

        override suspend fun markProjectPending(
            projectId: UUID,
            expectedStates: Set<OcrState>,
        ): Int = 0

        override suspend fun countAwaitingOcr(projectId: UUID): Int = 0

        override suspend fun claimNextPending(): OcrPage? = null

        override suspend fun recoverInterrupted(): Int = 0

        override suspend fun storeSuccess(
            pageId: UUID,
            result: StoredOcrResult,
        ): Boolean = false

        override suspend fun markFailed(pageId: UUID): Boolean = false

        override suspend fun returnToPending(pageId: UUID): Boolean = false
    }

    /**
     * 撮影画像の代わりに、紙面の文字の並びを模した無地の図形だけを描く。
     * 実在の書籍の文面は使わない（モックのサンプルデータを持ち込まない）。
     */
    private class PreviewPageImageLoader : PageThumbnailLoader {
        override suspend fun load(request: PageThumbnailRequest): ImageBitmap {
            val width = request.targetWidthPx.coerceAtLeast(MIN_IMAGE_WIDTH_PX)
            val height = width * PREVIEW_ASPECT_DENOMINATOR / PREVIEW_ASPECT_NUMERATOR
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            canvas.drawColor(PAPER_COLOR)
            val paint = Paint().apply { isAntiAlias = true }
            val margin = width * MARGIN_RATIO
            var y = margin
            paint.color = HEADING_COLOR
            canvas.drawRoundRect(
                RectF(margin, y, width * HEADING_WIDTH_RATIO, y + width * HEADING_HEIGHT_RATIO),
                CORNER_RADIUS,
                CORNER_RADIUS,
                paint,
            )
            y += width * HEADING_HEIGHT_RATIO + margin
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
        val PREVIEW_PAGE_ID: UUID = UUID.fromString("60000000-0000-4000-8000-000000000012")

        /** モックと同じ「修正済み」の状態を出す。本文はプレビュー用のダミー */
        const val PREVIEW_FULL_TEXT =
            "これはプレビュー用のダミーテキストです。\n" +
                "OCR編集画面の分割表示・ページ内検索・手動修正の見え方を確認するために置いています。\n" +
                "実際の画面では、端末内で認識したOCRの結果がここに入ります。\n" +
                "認識できなかった箇所は、この領域を直接編集して直せます。\n" +
                "修正した内容は元のOCR結果とは別に保存されます。"

        const val PREVIEW_EDITED_TEXT =
            "これはプレビュー用のダミーテキストです。\n" +
                "OCR編集画面の分割表示・ページ内検索・手動修正の見え方を確認するために置いています。\n" +
                "実際の画面では、端末内で認識したOCRの結果がここに入ります。\n" +
                "認識できなかった箇所は、この領域を直接編集して直せます。\n" +
                "修正した内容は元のOCR結果とは別に保存されます（この行が修正済みの例です）。"

        val PREVIEW_PAGE =
            Page(
                id = PREVIEW_PAGE_ID,
                projectId = PREVIEW_PROJECT_ID,
                sequence = 12,
                originalImagePath = "preview/12.webp",
                width = 1080,
                height = 1920,
                rotation = 0,
                crop = PageCrop(),
                capturedAt = Instant.parse("2026-08-26T00:00:00Z"),
                contentHash = "preview-content-12",
                perceptualHash = "preview-perceptual-12",
                qualityState = PageQualityState.NORMAL,
                ocrState = PageOcrState.SUCCEEDED,
            )

        val PREVIEW_RESULT =
            StoredOcrResult(
                pageId = PREVIEW_PAGE_ID,
                fullText = PREVIEW_FULL_TEXT,
                blocksJson = """{"blocks":[]}""",
                editedText = PREVIEW_EDITED_TEXT,
                engineVersion = "preview",
                sourceImageHash = "preview-source-hash",
                processedAt = Instant.parse("2026-08-26T00:01:00Z"),
            )

        const val MIN_IMAGE_WIDTH_PX = 240
        const val PREVIEW_ASPECT_NUMERATOR = 9
        const val PREVIEW_ASPECT_DENOMINATOR = 16

        /** モックのページ画像は薄いセピア地（docs/design/mockups/10-ocr-edit.png のサンプル画像） */
        const val PAPER_COLOR = 0xFFEFE9DC.toInt()
        const val HEADING_COLOR = 0xFF6B6152.toInt()
        const val BODY_COLOR = 0xFFB6AC99.toInt()
        const val MARGIN_RATIO = 0.08f
        const val HEADING_WIDTH_RATIO = 0.6f
        const val HEADING_HEIGHT_RATIO = 0.035f
        const val LINE_HEIGHT_RATIO = 0.03f
        const val LINE_GAP_RATIO = 2.2f
        const val SHORT_LINE_RATIO = 0.62f
        const val SHORT_LINE_EVERY = 5
        const val CORNER_RADIUS = 2f
    }
}
