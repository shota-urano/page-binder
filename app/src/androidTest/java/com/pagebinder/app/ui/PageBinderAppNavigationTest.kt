package com.pagebinder.app.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.R
import com.pagebinder.app.domain.AutoCaptureSettings
import com.pagebinder.app.domain.AutoCaptureSettingsRepository
import com.pagebinder.app.domain.BookProject
import com.pagebinder.app.domain.BookProjectRepository
import com.pagebinder.app.domain.BookProjectSort
import com.pagebinder.app.domain.BookProjectSummary
import com.pagebinder.app.domain.CaptureFeedbackSettings
import com.pagebinder.app.domain.CaptureFeedbackSettingsRepository
import com.pagebinder.app.domain.ExportStarter
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageCropScope
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.ui.consent.ConsentGate
import com.pagebinder.app.ui.consent.ConsentUiState
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader
import com.pagebinder.app.ui.pagelist.pageListCellTestTag
import com.pagebinder.app.ui.theme.PageBinderTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/**
 * 書籍詳細からの本番導線（撮影・ページ一覧・書き出し・ページ編集）が実際に画面へ着くことを、
 * 利用者操作の側から確認する（pagebinder-3us.6 の受け入れ基準「無反応の操作がない」）。
 *
 * 本番の [PageBinderApp] をそのまま組み立て、リポジトリだけを差し替える。
 */
@RunWith(AndroidJUnit4::class)
class PageBinderAppNavigationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val projectId = UUID.fromString("50000000-0000-0000-0000-000000000001")
    private val pageId = UUID.fromString("50000000-0000-0000-0000-000000000101")

    @Test
    fun `書籍詳細の手動撮影から撮影準備画面へ遷移し戻れる`() {
        showApp(pageCount = 2)
        openBookDetail()

        composeTestRule
            .onNodeWithText(string(R.string.book_detail_manual_capture))
            .assertIsEnabled()
            .performClick()

        awaitText(R.string.capture_prep_title)
        // 保存先カードに書籍が出ており、手動なので連続撮影の設定行は出ない
        composeTestRule.onNodeWithText(string(R.string.capture_prep_destination)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.capture_prep_minimum_interval)).assertDoesNotExist()

        composeTestRule.onNodeWithContentDescription(string(R.string.capture_prep_back)).performClick()

        awaitText(R.string.book_detail_manual_capture)
    }

    @Test
    fun `書籍詳細の連続撮影から連続モードの撮影準備画面へ遷移する`() {
        showApp(pageCount = 2)
        openBookDetail()

        composeTestRule
            .onNodeWithText(string(R.string.book_detail_continuous_capture))
            .assertIsEnabled()
            .performClick()

        awaitText(R.string.capture_prep_title)
        // 連続を選んで来たことが画面に反映されている（連続撮影の設定行が出る）
        composeTestRule
            .onNodeWithText(string(R.string.capture_prep_minimum_interval))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `書籍詳細の書き出しから書き出し画面へ遷移し戻れる`() {
        showApp(pageCount = 2)
        openBookDetail()

        composeTestRule.onNodeWithText(string(R.string.book_detail_export)).performScrollTo().performClick()

        // 書き出し画面の中身（出力形式カード）まで出ていること
        awaitText(R.string.export_format_title)
        composeTestRule.onNodeWithText(string(R.string.export_format_searchable_pdf)).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.export_back)).performClick()

        awaitText(R.string.book_detail_manual_capture)
    }

    @Test
    fun `ページが無い書籍では書き出しが無効で理由が出る`() {
        showApp(pageCount = 0)
        openBookDetail()

        composeTestRule
            .onNodeWithText(string(R.string.book_detail_export_unavailable))
            .performScrollTo()
            .assertIsDisplayed()

        composeTestRule
            .onNodeWithText(string(R.string.book_detail_export))
            .performScrollTo()
            .assertIsNotEnabled()

        composeTestRule.onNodeWithText(string(R.string.book_detail_export)).performClick()

        // 無効な導線を押しても書き出し画面へは進まない
        composeTestRule.onNodeWithText(string(R.string.export_format_title)).assertDoesNotExist()

        // 無効化は書き出しだけ。撮影はページ0件でも遷移先が使えるので有効なまま
        composeTestRule
            .onNodeWithText(string(R.string.book_detail_manual_capture))
            .performScrollTo()
            .assertIsEnabled()
        composeTestRule
            .onNodeWithText(string(R.string.book_detail_continuous_capture))
            .assertIsEnabled()
    }

    @Test
    fun `ページ一覧の導線は常に押せて一覧画面へ着く`() {
        showApp(pageCount = 0)
        openBookDetail()

        composeTestRule
            .onNodeWithText(string(R.string.book_detail_pages))
            .performScrollTo()
            .performClick()

        awaitText(R.string.page_list_title)
    }

    @Test
    fun `ページ一覧からページ編集へ遷移し閉じると一覧へ戻る`() {
        showApp(pageCount = 1)
        openBookDetail()

        composeTestRule.onNodeWithText(string(R.string.book_detail_pages)).performScrollTo().performClick()
        awaitText(R.string.page_list_title)

        composeTestRule.onNodeWithTag(pageListCellTestTag(1)).performClick()

        awaitText(R.string.page_edit_non_destructive)
        composeTestRule
            .onNodeWithText(context.getString(R.string.page_edit_title, 1))
            .assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.page_edit_close)).performClick()

        awaitText(R.string.page_list_title)
    }

    private fun openBookDetail() {
        awaitText(R.string.home_search_hint)
        composeTestRule.waitUntil("ホームに書籍が出ない", TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(BOOK_TITLE).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithText(BOOK_TITLE).performClick()
        awaitText(R.string.book_detail_manual_capture)
    }

    private fun showApp(pageCount: Int) {
        val pages =
            (1..pageCount).map { sequence ->
                page(if (sequence == 1) pageId else UUID.randomUUID(), sequence)
            }
        composeTestRule.setContent {
            PageBinderTheme {
                PageBinderApp(
                    uiState = ConsentUiState(gate = ConsentGate.Unlocked),
                    onAgree = {},
                    onDecline = {},
                    bookProjectRepository = FakeBookProjectRepository(summary(pageCount)),
                    pageRepository = FakePageRepository(pages),
                    // サムネイルを必ず返す。失敗プレースホルダの再試行ボタンがセル中央を占めると、
                    // セルのタップ（ページ編集への導線）を確認できない
                    pageThumbnailLoader = PageThumbnailLoader { ImageBitmap(THUMBNAIL_SIZE_PX, THUMBNAIL_SIZE_PX) },
                    // 書き出しの実処理は ProjectExportStarterTest 側で見る。ここは導線だけ
                    exportStarter = ExportStarter { emptyFlow() },
                    enqueueProjectOcr = { 0 },
                    autoCaptureSettingsRepository = FakeAutoCaptureSettingsRepository(),
                    captureFeedbackSettingsRepository = FakeCaptureFeedbackSettingsRepository(),
                    startCapture = { _, _ -> },
                )
            }
        }
    }

    private fun summary(pageCount: Int) =
        BookProjectSummary(
            project =
                BookProject(
                    id = projectId,
                    title = BOOK_TITLE,
                    author = null,
                    note = null,
                    createdAt = CREATED_AT,
                    updatedAt = CREATED_AT,
                    deletedAt = null,
                ),
            pageCount = pageCount,
            storageBytes = 1_024,
            ocrCompletedCount = 0,
            ocrErrorCount = 0,
        )

    private fun page(
        id: UUID,
        sequence: Int,
    ) = Page(
        id = id,
        projectId = projectId,
        sequence = sequence,
        originalImagePath = "projects/$projectId/images/$id.webp",
        width = 100,
        height = 200,
        rotation = 0,
        crop = PageCrop(),
        capturedAt = CREATED_AT,
        contentHash = "hash-$sequence",
        perceptualHash = "phash-$sequence",
        qualityState = PageQualityState.NORMAL,
        ocrState = PageOcrState.PENDING,
    )

    private fun string(resId: Int): String = context.getString(resId)

    private fun awaitText(resId: Int) {
        val text = string(resId)
        composeTestRule.waitUntil("画面に「$text」が出ない", TIMEOUT_MILLIS) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private class FakeAutoCaptureSettingsRepository : AutoCaptureSettingsRepository {
        override suspend fun read(): AutoCaptureSettings = AutoCaptureSettings()

        override suspend fun save(settings: AutoCaptureSettings) = Unit
    }

    private class FakeCaptureFeedbackSettingsRepository : CaptureFeedbackSettingsRepository {
        override suspend fun read(): CaptureFeedbackSettings = CaptureFeedbackSettings()

        override suspend fun save(settings: CaptureFeedbackSettings) = Unit
    }

    private class FakePageRepository(
        private val pages: List<Page>,
    ) : PageRepository {
        override suspend fun insert(page: Page) = error("Not used by navigation")

        override suspend fun findById(id: UUID): Page? = pages.firstOrNull { it.id == id }

        override suspend fun findByProject(projectId: UUID): List<Page> = pages

        override suspend fun reorder(
            projectId: UUID,
            orderedPageIds: List<UUID>,
        ) = error("Not used by navigation")

        override suspend fun delete(
            projectId: UUID,
            pageIds: Set<UUID>,
        ) = error("Not used by navigation")

        override suspend fun deleteResolvingDuplicates(
            projectId: UUID,
            pageIds: Set<UUID>,
            resolvedDuplicatePageIds: Set<UUID>,
        ) = error("Not used by navigation")

        override suspend fun updateRotation(
            pageId: UUID,
            rotation: Int,
        ) = error("Not used by navigation")

        override suspend fun updateCrop(
            pageId: UUID,
            crop: PageCrop,
        ) = error("Not used by navigation")

        override suspend fun updatePageEdit(
            pageId: UUID,
            rotation: Int,
            crop: PageCrop,
            cropScope: PageCropScope,
        ): Int = error("Not used by navigation")

        override suspend fun undoLastEdit(): Boolean = error("Not used by navigation")
    }

    private class FakeBookProjectRepository(
        private val summary: BookProjectSummary,
    ) : BookProjectRepository {
        override suspend fun create(
            title: String,
            author: String?,
            note: String?,
        ): BookProject = error("Not used by navigation")

        override suspend fun findById(id: UUID): BookProject? = summary.project.takeIf { it.id == id }

        override suspend fun findSummaryById(id: UUID): BookProjectSummary? = summary.takeIf { it.project.id == id }

        override fun observeSummaryById(id: UUID): Flow<BookProjectSummary?> =
            flowOf(summary.takeIf { it.project.id == id })

        override suspend fun update(
            id: UUID,
            title: String,
            author: String?,
            note: String?,
        ): BookProject = error("Not used by navigation")

        override suspend fun listActive(sort: BookProjectSort): List<BookProjectSummary> = listOf(summary)

        override suspend fun searchActive(
            query: String,
            sort: BookProjectSort,
        ): List<BookProjectSummary> = listOf(summary)

        override suspend fun listTrash(): List<BookProjectSummary> = emptyList()

        override suspend fun moveToTrash(id: UUID): BookProject = error("Not used by navigation")

        override suspend fun restore(id: UUID): BookProject = error("Not used by navigation")

        override suspend fun deletePermanently(id: UUID) = error("Not used by navigation")

        override suspend fun purgeExpiredTrash(): Int = 0
    }

    private companion object {
        const val BOOK_TITLE = "導線検証プロジェクト"
        const val TIMEOUT_MILLIS = 10_000L
        const val THUMBNAIL_SIZE_PX = 8
        val CREATED_AT: Instant = Instant.parse("2026-09-02T00:00:00Z")
    }
}
