package com.pagebinder.app.ui.pagelist

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.R
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.ui.theme.PageBinderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/**
 * ページ一覧画面の受け入れ基準を、利用者操作の側から確認する（docs/specs/08-page-editing.md §3.1）。
 *
 * production の [PageListScreen] と [PageListViewModel] をそのまま組み合わせ、
 * 画面のタップが production の状態をどう動かすかだけを見る。
 */
@RunWith(AndroidJUnit4::class)
class PageListScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val projectId = UUID.fromString("30000000-0000-0000-0000-000000000001")
    private var openedPageIds = mutableListOf<UUID>()
    private var deleteRequestCount = 0

    @Test
    fun `表示切替でグリッドとリストが入れ替わる`() {
        val viewModel = showScreen(samplePages())
        composeTestRule.onNodeWithTag(PAGE_LIST_GRID_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PAGE_LIST_ROWS_TEST_TAG).assertDoesNotExist()

        composeTestRule
            .onNodeWithContentDescription(string(R.string.page_list_view_mode_list))
            .performClick()

        assertEquals(PageListViewMode.LIST, viewModel.uiState.value.viewMode)
        composeTestRule.onNodeWithTag(PAGE_LIST_ROWS_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PAGE_LIST_GRID_TEST_TAG).assertDoesNotExist()

        composeTestRule
            .onNodeWithContentDescription(string(R.string.page_list_view_mode_grid))
            .performClick()

        assertEquals(PageListViewMode.GRID, viewModel.uiState.value.viewMode)
        composeTestRule.onNodeWithTag(PAGE_LIST_GRID_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PAGE_LIST_ROWS_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `警告のあるページはグリッドでもOCR状態と警告の両方を出す`() {
        // docs/specs/08-page-editing.md §3.1: 各ページに OCR状態と重複・黒画面警告を表示する。
        // 警告が付いたページでも OCR状態が消えてはいけない
        showScreen(
            listOf(
                page(1, ocrState = PageOcrState.SUCCEEDED, qualityState = PageQualityState.DUPLICATE),
                page(2, ocrState = PageOcrState.PENDING, qualityState = PageQualityState.BLACK),
            ),
        )

        composeTestRule.onNodeWithText(string(R.string.page_list_warning_duplicate)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.page_list_ocr_succeeded)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.page_list_warning_black)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.page_list_ocr_pending)).assertIsDisplayed()
    }

    @Test
    fun `警告のあるページはリストでもOCR状態と警告の両方を出す`() {
        val viewModel =
            showScreen(
                listOf(
                    page(1, ocrState = PageOcrState.STALE, qualityState = PageQualityState.DUPLICATE),
                ),
            )

        composeTestRule
            .onNodeWithContentDescription(string(R.string.page_list_view_mode_list))
            .performClick()

        assertEquals(PageListViewMode.LIST, viewModel.uiState.value.viewMode)
        composeTestRule.onNodeWithText(string(R.string.page_list_warning_duplicate)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.page_list_ocr_stale)).assertIsDisplayed()
    }

    @Test
    fun `長押しとタップで複数選択でき件数が出る`() {
        val viewModel = showScreen(samplePages())

        composeTestRule.onNodeWithText("1").performTouchInput { longClick() }
        composeTestRule.onNodeWithText(string(R.string.page_list_selection_count, 1)).assertIsDisplayed()

        composeTestRule.onNodeWithText("2").performClick()

        composeTestRule.onNodeWithText(string(R.string.page_list_selection_count, 2)).assertIsDisplayed()
        assertEquals(2, viewModel.uiState.value.selectedCount)
        // 選択モード中のタップは選択の切り替えで、編集画面へは進まない
        assertTrue(openedPageIds.isEmpty())
    }

    @Test
    fun `選択解除で通常のアプリバーへ戻る`() {
        val viewModel = showScreen(samplePages())
        composeTestRule.onNodeWithText("1").performTouchInput { longClick() }

        composeTestRule
            .onNodeWithContentDescription(string(R.string.page_list_selection_clear))
            .performClick()

        assertFalse(viewModel.uiState.value.selectionMode)
        composeTestRule.onNodeWithText(string(R.string.page_list_title)).assertIsDisplayed()
    }

    @Test
    fun `選択中のごみ箱は削除確認の要求を出すだけで削除しない`() {
        showScreen(samplePages())
        composeTestRule.onNodeWithText("1").performTouchInput { longClick() }

        composeTestRule
            .onNodeWithContentDescription(string(R.string.page_list_selection_delete))
            .performClick()

        assertEquals(1, deleteRequestCount)
    }

    @Test
    fun `通常時のタップは編集画面へ進む`() {
        val pages = samplePages()
        showScreen(pages)

        composeTestRule.onNodeWithText("3").performClick()

        assertEquals(listOf(pages[2].id), openedPageIds)
    }

    @Test
    fun `警告フィルタで重複候補だけに絞れる`() {
        val viewModel = showScreen(samplePages())

        selectFilter(R.string.page_list_filter_duplicate)

        assertEquals(PageListFilter.DUPLICATE, viewModel.uiState.value.filter)
        composeTestRule.onNodeWithText("4").assertIsDisplayed()
        composeTestRule.onNodeWithText("1").assertDoesNotExist()
        composeTestRule.onNodeWithText("5").assertDoesNotExist()
    }

    @Test
    fun `警告フィルタで黒画面候補だけに絞れる`() {
        showScreen(samplePages())

        selectFilter(R.string.page_list_filter_black)

        composeTestRule.onNodeWithText(string(R.string.page_list_warning_black)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.page_list_warning_duplicate)).assertDoesNotExist()
    }

    @Test
    fun `フィルタで0件になると案内を出す`() {
        showScreen(listOf(page(1)))

        selectFilter(R.string.page_list_filter_duplicate)

        composeTestRule.onNodeWithText(string(R.string.page_list_filter_empty)).assertIsDisplayed()
    }

    @Test
    fun `フィルタを変えると選択が解除される`() {
        val viewModel = showScreen(samplePages())
        composeTestRule.onNodeWithText("1").performTouchInput { longClick() }

        selectFilter(R.string.page_list_filter_ocr_incomplete)

        assertFalse(viewModel.uiState.value.selectionMode)
        composeTestRule.onNodeWithText(string(R.string.page_list_title)).assertIsDisplayed()
    }

    @Test
    fun `500ページでも末尾までスクロールできる`() {
        showScreen((1..LARGE_PAGE_COUNT).map { page(it) })

        composeTestRule
            .onNodeWithTag(PAGE_LIST_GRID_TEST_TAG)
            .performScrollToIndex(LARGE_PAGE_COUNT - 1)

        composeTestRule.onNodeWithText(LARGE_PAGE_COUNT.toString()).assertIsDisplayed()
    }

    @Test
    fun `サムネイルを作れないときは再試行できるプレースホルダを出す`() {
        showScreen(listOf(page(1)), thumbnailLoader = { null })

        composeTestRule
            .onNodeWithContentDescription(string(R.string.page_list_thumbnail_retry))
            .assertIsDisplayed()
            .performClick()
    }

    @Test
    fun `ページが無い書籍では案内を出す`() {
        showScreen(emptyList())

        composeTestRule.onNodeWithText(string(R.string.page_list_empty)).assertIsDisplayed()
    }

    private fun selectFilter(labelRes: Int) {
        composeTestRule.onNodeWithText(string(R.string.page_list_filter_all)).performClick()
        composeTestRule.onNodeWithText(string(labelRes)).performClick()
    }

    private fun showScreen(
        pages: List<Page>,
        thumbnailLoader: PageThumbnailLoader = PageThumbnailLoader { ImageBitmap(THUMBNAIL_PIXELS, THUMBNAIL_PIXELS) },
    ): PageListViewModel {
        openedPageIds = mutableListOf()
        deleteRequestCount = 0
        val viewModel = PageListViewModel(projectId, FakePageRepository(pages))
        composeTestRule.setContent {
            PageBinderTheme {
                val uiState by viewModel.uiState.collectAsState()
                PageListScreen(
                    uiState = uiState,
                    thumbnailLoader = thumbnailLoader,
                    actions = actionsOf(viewModel),
                )
            }
        }
        return viewModel
    }

    private fun actionsOf(viewModel: PageListViewModel) =
        PageListScreenActions(
            onBack = {},
            onViewModeChange = viewModel::onViewModeChange,
            onFilterChange = viewModel::onFilterChange,
            onPageOpened = { openedPageIds += it },
            onPageLongPressed = viewModel::onPageLongPressed,
            onSelectionToggled = viewModel::onSelectionToggled,
            onSelectionCleared = viewModel::onSelectionCleared,
            onDeleteSelectedRequested = { deleteRequestCount++ },
            onReload = viewModel::load,
        )

    private fun string(
        resId: Int,
        vararg formatArgs: Any,
    ): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(resId, *formatArgs)

    /** 1=完了/2=待機/3=失敗/4=重複/5=黒画面 */
    private fun samplePages(): List<Page> =
        listOf(
            page(1, ocrState = PageOcrState.SUCCEEDED),
            page(2, ocrState = PageOcrState.PENDING),
            page(3, ocrState = PageOcrState.FAILED),
            page(4, ocrState = PageOcrState.SUCCEEDED, qualityState = PageQualityState.DUPLICATE),
            page(5, ocrState = PageOcrState.SUCCEEDED, qualityState = PageQualityState.BLACK),
        )

    private fun page(
        sequence: Int,
        ocrState: PageOcrState = PageOcrState.SUCCEEDED,
        qualityState: PageQualityState = PageQualityState.NORMAL,
    ): Page =
        Page(
            id = UUID.fromString("50000000-0000-0000-0000-${sequence.toString().padStart(12, '0')}"),
            projectId = projectId,
            sequence = sequence,
            originalImagePath = "pages/$sequence.webp",
            width = 1080,
            height = 1920,
            rotation = 0,
            crop = PageCrop(),
            capturedAt = Instant.parse("2026-08-26T00:00:00Z").plusSeconds(sequence.toLong()),
            contentHash = "content-$sequence",
            perceptualHash = "perceptual-$sequence",
            qualityState = qualityState,
            ocrState = ocrState,
        )

    private class FakePageRepository(private val pages: List<Page>) : PageRepository {
        override suspend fun insert(page: Page) = throw UnsupportedOperationException()

        override suspend fun findById(id: UUID): Page? = pages.firstOrNull { it.id == id }

        override suspend fun findByProject(projectId: UUID): List<Page> = pages.filter { it.projectId == projectId }

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

    private companion object {
        /** requirements §16.1 の最低基準 */
        const val LARGE_PAGE_COUNT = 500
        const val THUMBNAIL_PIXELS = 8
    }
}
