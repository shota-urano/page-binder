package com.pagebinder.app.preview

import android.content.Context
import android.content.SharedPreferences
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
import androidx.room.Room
import com.pagebinder.app.data.PageBinderDatabase
import com.pagebinder.app.data.RoomPageRepository
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.ui.duplicatereview.DuplicateReviewRoute
import com.pagebinder.app.ui.duplicatereview.DuplicateReviewViewModel
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader
import com.pagebinder.app.ui.pagelist.PageThumbnailRequest
import com.pagebinder.app.ui.theme.PageBinderTheme
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID

/**
 * debug ビルド専用の重複候補比較・黒画面候補一覧プレビュー。
 *
 * 重複・黒画面の検出そのものは別実装（docs/specs/07-image-quality.md §3.2・§3.3）なので、
 * プレビューでは判定済みの qualityState を持つページを置いて、確認・操作のUIだけを実機で見る。
 * production の APK には含まれない。ページ画像だけは仮置きだが、削除と取り消しは production と
 * 同じ data 層（Room）を通す。
 */
class DuplicateReviewScreenPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pageRepository = DuplicateReviewPreviewStore.repository(applicationContext, PREVIEW_PAGES)
        val thumbnailLoader = PreviewCandidateThumbnailLoader(PREVIEW_PAGES)
        setContent {
            PageBinderTheme {
                val viewModel: DuplicateReviewViewModel =
                    viewModel(
                        factory =
                            DuplicateReviewViewModel.factory(
                                projectId = PREVIEW_PROJECT_ID,
                                pageRepository = pageRepository,
                            ),
                    )
                DuplicateReviewRoute(
                    viewModel = viewModel,
                    thumbnailLoader = thumbnailLoader,
                    onBack = { finish() },
                    modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                )
            }
        }
    }

    /**
     * 撮影画像の代わりに、紙面の文字の並びを模した無地の図形だけを描く。
     * 実在の書籍の文面は使わない（モックのサンプルデータを持ち込まない）。
     * 黒画面と判定されたページは黒く塗る。
     */
    private class PreviewCandidateThumbnailLoader(pages: List<Page>) : PageThumbnailLoader {
        private val blackPageIds =
            pages.filter { it.qualityState == PageQualityState.BLACK }.map(Page::id).toSet()

        override suspend fun load(request: PageThumbnailRequest): ImageBitmap {
            val width = request.targetWidthPx.coerceAtLeast(MIN_THUMBNAIL_WIDTH_PX)
            val height = width * PREVIEW_ASPECT_DENOMINATOR / PREVIEW_ASPECT_NUMERATOR
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            if (request.pageId in blackPageIds) {
                canvas.drawColor(Color.BLACK)
                return bitmap.asImageBitmap()
            }
            drawPage(canvas, width, height)
            return bitmap.asImageBitmap()
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
    }

    private companion object {
        val PREVIEW_PROJECT_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000004")

        const val PREVIEW_PAGE_COUNT = 23

        /** 重複の印が付くページ（直前ページとの近似重複。docs/specs/07-image-quality.md §3.3） */
        val DUPLICATE_SEQUENCES = setOf(8)

        /** 黒画面と判定されたページ */
        val BLACK_SEQUENCES = setOf(15, 23)

        val PREVIEW_PAGES: List<Page> =
            (1..PREVIEW_PAGE_COUNT).map { sequence ->
                Page(
                    id = UUID.fromString("80000000-0000-4000-8000-${sequence.toString().padStart(12, '0')}"),
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
                    qualityState =
                        when (sequence) {
                            in DUPLICATE_SEQUENCES -> PageQualityState.DUPLICATE
                            in BLACK_SEQUENCES -> PageQualityState.BLACK
                            else -> PageQualityState.NORMAL
                        },
                    ocrState = PageOcrState.SUCCEEDED,
                )
            }

        const val MIN_THUMBNAIL_WIDTH_PX = 240
        const val PREVIEW_ASPECT_NUMERATOR = 9
        const val PREVIEW_ASPECT_DENOMINATOR = 16
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
 * production と同じ [RoomPageRepository] を通すので、削除と直前1操作の取り消しは Activity を
 * 閉じても残る。DB ファイルは production の `pagebinder.db` とも他のプレビューとも分けてあり、
 * 実データには触れない。
 */
private object DuplicateReviewPreviewStore {
    private const val DATABASE_NAME = "pagebinder-duplicate-review-preview.db"
    private const val PREFERENCES_NAME = "duplicate-review-preview"
    private const val SEEDED_KEY = "seeded"

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
        return SeedingPageRepository(
            delegate = RoomPageRepository(database.pageDao()),
            preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
            seedPages = seedPages,
        )
    }

    /**
     * 空の DB へ最初の1回だけプレビュー用ページを入れる。
     * 済みの印は SharedPreferences に置く（消した結果を未 seed と取り違えて書き戻さないため）。
     */
    private class SeedingPageRepository(
        private val delegate: PageRepository,
        private val preferences: SharedPreferences,
        private val seedPages: List<Page>,
    ) : PageRepository by delegate {
        private val seedLock = Mutex()

        override suspend fun findByProject(projectId: UUID): List<Page> {
            seedOnce()
            return delegate.findByProject(projectId)
        }

        private suspend fun seedOnce() {
            if (preferences.getBoolean(SEEDED_KEY, false)) return
            seedLock.withLock {
                if (preferences.getBoolean(SEEDED_KEY, false)) return
                seedPages.forEach { delegate.insert(it) }
                preferences.edit().putBoolean(SEEDED_KEY, true).apply()
            }
        }
    }
}
