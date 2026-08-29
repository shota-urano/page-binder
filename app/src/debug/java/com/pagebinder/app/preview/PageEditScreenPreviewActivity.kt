package com.pagebinder.app.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
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
import androidx.room.Room
import com.pagebinder.app.data.PageBinderDatabase
import com.pagebinder.app.data.RoomPageRepository
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.ui.pageedit.PageEditRoute
import com.pagebinder.app.ui.pageedit.PageEditViewModel
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader
import com.pagebinder.app.ui.pagelist.PageThumbnailRequest
import com.pagebinder.app.ui.theme.PageBinderTheme
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/**
 * debug ビルド専用の回転・切り取り編集画面プレビュー。
 *
 * 撮影・OCR の配線が入るまでのあいだ、実機で画面を目視・スクリーンショットするための入口。
 * production の APK には含まれない。ページ画像だけはプレビュー用の仮置きだが、
 * 回転・切り取りの読み書きは production と同じ data 層（Room）を通す。
 */
class PageEditScreenPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pageRepository = PageEditPreviewStore.repository(applicationContext, PREVIEW_PAGES)
        setContent {
            PageBinderTheme {
                val viewModel: PageEditViewModel =
                    viewModel(
                        factory =
                            PageEditViewModel.factory(
                                pageId = PREVIEW_PAGE_ID,
                                pageRepository = pageRepository,
                            ),
                    )
                PageEditRoute(
                    viewModel = viewModel,
                    imageLoader = PreviewPageImageLoader(),
                    onClose = { finish() },
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                )
            }
        }
    }

    /**
     * 撮影画像の代わりに、紙面の文字の並びを模した無地の図形だけを描く。
     * 実在の書籍の文面は使わない（モックのサンプルデータを持ち込まない）。
     *
     * 非破壊の rotation は production の派生画像生成と同じように適用する
     * （元画像に相当するビットマップは作り直さず、回した写しを返す）。
     */
    private class PreviewPageImageLoader : PageThumbnailLoader {
        override suspend fun load(request: PageThumbnailRequest): ImageBitmap {
            val width = request.targetWidthPx.coerceAtLeast(MIN_IMAGE_WIDTH_PX)
            val height = width * PREVIEW_ASPECT_DENOMINATOR / PREVIEW_ASPECT_NUMERATOR
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            drawPage(Canvas(bitmap), width, height)
            return rotated(bitmap, request.rotation).asImageBitmap()
        }

        private fun drawPage(
            canvas: Canvas,
            width: Int,
            height: Int,
        ) {
            canvas.drawColor(PAPER_COLOR)
            val paint = Paint().apply { isAntiAlias = true }
            val margin = width * MARGIN_RATIO
            var y = margin * 2f
            paint.color = HEADING_COLOR
            canvas.drawRoundRect(
                RectF(margin, y, width * HEADING_WIDTH_RATIO, y + width * HEADING_HEIGHT_RATIO),
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
                canvas.drawRoundRect(RectF(margin, y, right, y + lineHeight), CORNER_RADIUS, CORNER_RADIUS, paint)
                y += lineHeight * LINE_GAP_RATIO
                index++
            }
        }

        private fun rotated(
            bitmap: Bitmap,
            rotation: Int,
        ): Bitmap {
            if (rotation == 0) return bitmap
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }
    }

    private companion object {
        val PREVIEW_PROJECT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000003")
        val PREVIEW_PAGE_ID: UUID = UUID.fromString("70000000-0000-4000-8000-000000000012")

        /** 一括適用の対象件数が確認ダイアログに出るように、同じ書籍のページを複数入れておく */
        val PREVIEW_PAGES: List<Page> =
            (12..15).map { sequence ->
                Page(
                    id =
                        if (sequence == 12) {
                            PREVIEW_PAGE_ID
                        } else {
                            UUID.fromString("70000000-0000-4000-8000-${sequence.toString().padStart(12, '0')}")
                        },
                    projectId = PREVIEW_PROJECT_ID,
                    sequence = sequence,
                    originalImagePath = "preview/$sequence.webp",
                    width = 1080,
                    height = 1920,
                    rotation = 0,
                    // 開くページだけ少し内側に切り取っておく（枠と枠外の減光が見える状態から始める）
                    crop = if (sequence == 12) PageCrop(0.05f, 0.04f, 0.95f, 0.96f) else PageCrop(),
                    capturedAt = Instant.parse("2026-08-26T00:00:00Z").plusSeconds(sequence.toLong()),
                    contentHash = "preview-content-$sequence",
                    perceptualHash = "preview-perceptual-$sequence",
                    qualityState = PageQualityState.NORMAL,
                    ocrState = PageOcrState.SUCCEEDED,
                )
            }

        const val MIN_IMAGE_WIDTH_PX = 240
        const val PREVIEW_ASPECT_NUMERATOR = 9
        const val PREVIEW_ASPECT_DENOMINATOR = 16

        /** プレビューの紙面は白地（docs/design/mockups/08-page-edit.png のサンプル画像に合わせる） */
        const val PAPER_COLOR = Color.WHITE
        const val HEADING_COLOR = 0xFF334155.toInt()
        const val BODY_COLOR = 0xFFB6AC99.toInt()
        const val MARGIN_RATIO = 0.08f
        const val HEADING_WIDTH_RATIO = 0.6f
        const val HEADING_HEIGHT_RATIO = 0.05f
        const val LINE_HEIGHT_RATIO = 0.03f
        const val LINE_GAP_RATIO = 2.2f
        const val SHORT_LINE_RATIO = 0.62f
        const val SHORT_LINE_EVERY = 5
        const val CORNER_RADIUS = 2f
    }
}

/**
 * プレビュー用のページ保存先。
 *
 * production と同じ [RoomPageRepository] を通すので、保存した回転・切り取りは Activity を
 * 閉じても残る（プレビューでも非破壊属性の永続化を実機で確かめられる）。
 * DB ファイルは production の `pagebinder.db` とも他のプレビューとも分けてあり、実データには触れない。
 */
private object PageEditPreviewStore {
    private const val DATABASE_NAME = "pagebinder-page-edit-preview.db"

    @Volatile
    private var instance: PageRepository? = null

    fun repository(
        context: Context,
        seedPages: List<Page>,
    ): PageRepository =
        instance ?: synchronized(this) {
            instance ?: create(context, seedPages).also { instance = it }
        }

    private fun create(
        context: Context,
        seedPages: List<Page>,
    ): PageRepository {
        val database = Room.databaseBuilder(context, PageBinderDatabase::class.java, DATABASE_NAME).build()
        return SeedingPageRepository(RoomPageRepository(database.pageDao()), seedPages)
    }

    /** 空の DB へ最初の1回だけプレビュー用ページを入れる。既にあれば何もしない */
    private class SeedingPageRepository(
        private val delegate: PageRepository,
        private val seedPages: List<Page>,
    ) : PageRepository by delegate {
        private val seedLock = Mutex()

        override suspend fun findById(id: UUID): Page? {
            seedOnce()
            return delegate.findById(id)
        }

        override suspend fun findByProject(projectId: UUID): List<Page> {
            seedOnce()
            return delegate.findByProject(projectId)
        }

        private suspend fun seedOnce() {
            seedLock.withLock {
                val projectId = seedPages.firstOrNull()?.projectId ?: return
                if (delegate.findByProject(projectId).isNotEmpty()) return
                seedPages.forEach { delegate.insert(it) }
            }
        }
    }
}
