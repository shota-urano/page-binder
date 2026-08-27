package com.pagebinder.app.ui.pagelist

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.down
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
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
    private lateinit var repository: FakePageRepository

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
    fun `グリッドは各セルの中にOCR状態と警告を併記する`() {
        // docs/specs/08-page-editing.md §3.1: 各ページに OCR状態と重複・黒画面警告を表示する。
        // 警告が付いたページでも OCR状態が消えてはいけないし、警告が別ページへ漏れてもいけない
        showScreen(badgeSamplePages())

        assertPageBadges(
            tag = pageListCellTestTag(1),
            ocrLabelRes = R.string.page_list_ocr_succeeded,
            warningLabelRes = R.string.page_list_warning_duplicate,
        )
        assertPageBadges(
            tag = pageListCellTestTag(2),
            ocrLabelRes = R.string.page_list_ocr_pending,
            warningLabelRes = R.string.page_list_warning_black,
        )
        assertPageBadges(
            tag = pageListCellTestTag(3),
            ocrLabelRes = R.string.page_list_ocr_failed,
            warningLabelRes = null,
        )
    }

    @Test
    fun `リストは各行の中にOCR状態と警告を併記する`() {
        val viewModel = showScreen(badgeSamplePages())

        composeTestRule
            .onNodeWithContentDescription(string(R.string.page_list_view_mode_list))
            .performClick()

        assertEquals(PageListViewMode.LIST, viewModel.uiState.value.viewMode)
        assertPageBadges(
            tag = pageListRowTestTag(1),
            ocrLabelRes = R.string.page_list_ocr_succeeded,
            warningLabelRes = R.string.page_list_warning_duplicate,
        )
        assertPageBadges(
            tag = pageListRowTestTag(2),
            ocrLabelRes = R.string.page_list_ocr_pending,
            warningLabelRes = R.string.page_list_warning_black,
        )
        assertPageBadges(
            tag = pageListRowTestTag(3),
            ocrLabelRes = R.string.page_list_ocr_failed,
            warningLabelRes = null,
        )
    }

    /**
     * セル/行1件のスコープで、期待した OCR状態バッジと警告バッジだけが出ていることを見る。
     * 画面全体から文言を探すと、どのページにどのバッジが付いたのかを区別できない。
     */
    private fun assertPageBadges(
        tag: String,
        ocrLabelRes: Int,
        warningLabelRes: Int?,
    ) {
        val cell = composeTestRule.onNodeWithTag(tag)
        cell.assertIsDisplayed()
        val expected = listOfNotNull(ocrLabelRes, warningLabelRes)
        expected.forEach { labelRes ->
            cell.assert(showsBadgeText(string(labelRes)))
        }
        (OCR_LABEL_RES + WARNING_LABEL_RES)
            .filterNot { it in expected }
            .forEach { labelRes ->
                cell.assert(showsBadgeText(string(labelRes)).not())
            }
    }

    /**
     * セル/行がそのバッジ文言を出しているか。
     * セル/行は clickable なので子の文言が自身へ統合される（Text=[1, 重複, 完了]）が、
     * 統合が変わっても壊れないように子孫側も見る。
     */
    private fun showsBadgeText(label: String): SemanticsMatcher = hasText(label) or hasAnyDescendant(hasText(label))

    /** 1=完了+重複/2=待機+黒画面/3=失敗（警告なし） */
    private fun badgeSamplePages(): List<Page> =
        listOf(
            page(1, ocrState = PageOcrState.SUCCEEDED, qualityState = PageQualityState.DUPLICATE),
            page(2, ocrState = PageOcrState.PENDING, qualityState = PageQualityState.BLACK),
            page(3, ocrState = PageOcrState.FAILED),
        )

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
    fun `選択中のごみ箱は件数を出す確認ダイアログを開くだけで削除しない`() {
        // docs/specs/08-page-editing.md §6: 削除確認で件数を必ず表示。確認なしの複数削除を行わない
        showScreen(samplePages())
        composeTestRule.onNodeWithText("1").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("2").performClick()

        composeTestRule
            .onNodeWithContentDescription(string(R.string.page_list_selection_delete))
            .performClick()

        assertEquals(1, deleteRequestCount)
        composeTestRule.onNodeWithText(string(R.string.page_list_delete_dialog_title)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.page_list_delete_dialog_message, 2))
            .assertIsDisplayed()
        assertTrue(repository.deleteCalls.isEmpty())
    }

    @Test
    fun `確認をキャンセルするとダイアログが閉じて選択が残る`() {
        val viewModel = showScreen(samplePages())
        composeTestRule.onNodeWithText("1").performTouchInput { longClick() }
        openDeleteDialog()

        composeTestRule.onNodeWithText(string(R.string.page_list_delete_dialog_cancel)).performClick()

        composeTestRule.onNodeWithText(string(R.string.page_list_delete_dialog_title)).assertDoesNotExist()
        assertTrue(repository.deleteCalls.isEmpty())
        assertEquals(1, viewModel.uiState.value.selectedCount)
    }

    @Test
    fun `確認ダイアログで削除するとページが消えて取り消しを案内する`() {
        val pages = samplePages()
        val viewModel = showScreen(pages)
        composeTestRule.onNodeWithText("1").performTouchInput { longClick() }
        openDeleteDialog()

        composeTestRule.onNodeWithText(string(R.string.page_list_delete_dialog_confirm)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(setOf(pages[0].id)), repository.deleteCalls)
        assertEquals(4, viewModel.uiState.value.pages.size)
        composeTestRule.onNodeWithTag(PAGE_LIST_MESSAGE_BAR_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.page_list_undo_delete, 1)).assertIsDisplayed()
    }

    @Test
    fun `削除の取り消しでページが戻る`() {
        val pages = samplePages()
        val viewModel = showScreen(pages)
        composeTestRule.onNodeWithText("1").performTouchInput { longClick() }
        openDeleteDialog()
        composeTestRule.onNodeWithText(string(R.string.page_list_delete_dialog_confirm)).performClick()

        composeTestRule.onNodeWithText(string(R.string.page_list_undo_action)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(
            pages.map(Page::id),
            viewModel.uiState.value.pages.map(PageListItemUiState::pageId),
        )
        composeTestRule.onNodeWithTag(PAGE_LIST_MESSAGE_BAR_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `リスト表示ではつまみのドラッグでページを入れ替えられる`() {
        // docs/specs/08-page-editing.md §3.2 FR-EDT-002: ドラッグ操作で sequence を振り直す
        val pages = samplePages()
        val viewModel = showScreen(pages)
        composeTestRule
            .onNodeWithContentDescription(string(R.string.page_list_view_mode_list))
            .performClick()

        val rowHeight = composeTestRule.onNodeWithTag(pageListRowTestTag(1)).fetchSemanticsNode().size.height
        composeTestRule.onNodeWithTag(pageReorderHandleTestTag(1), useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(0f, TOUCH_SLOP_PX))
            moveBy(Offset(0f, rowHeight.toFloat()))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(
            listOf(pages[1].id, pages[0].id, pages[2].id, pages[3].id, pages[4].id),
            viewModel.uiState.value.pages.map(PageListItemUiState::pageId),
        )
        assertEquals(1, repository.reorderCalls.size)
    }

    @Test
    fun `グリッド表示ではつまみのドラッグでページを入れ替えられる`() {
        val pages = samplePages()
        val viewModel = showScreen(pages)

        val cellWidth = composeTestRule.onNodeWithTag(pageListCellTestTag(1)).fetchSemanticsNode().size.width
        composeTestRule.onNodeWithTag(pageReorderHandleTestTag(1), useUnmergedTree = true).performTouchInput {
            down(center)
            moveBy(Offset(TOUCH_SLOP_PX, 0f))
            moveBy(Offset(cellWidth.toFloat(), 0f))
            up()
        }
        composeTestRule.waitForIdle()

        assertEquals(
            listOf(pages[1].id, pages[0].id, pages[2].id, pages[3].id, pages[4].id),
            viewModel.uiState.value.pages.map(PageListItemUiState::pageId),
        )
        assertEquals(1, repository.reorderCalls.size)
    }

    @Test
    fun `絞り込み中はつまみを出さない`() {
        // 一部のページしか見えていない状態では書籍全体の順序を確定できない
        showScreen(samplePages())
        composeTestRule.onNodeWithTag(pageReorderHandleTestTag(1), useUnmergedTree = true).assertExists()

        selectFilter(R.string.page_list_filter_ocr_incomplete)

        composeTestRule.onNodeWithTag(pageReorderHandleTestTag(2), useUnmergedTree = true).assertDoesNotExist()
        composeTestRule.onNodeWithTag(pageReorderHandleTestTag(3), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `選択モード中はつまみを出さない`() {
        // アプリバーが削除の文脈に変わるので、同じ画面で並べ替えを受けない
        showScreen(samplePages())

        composeTestRule.onNodeWithText("1").performTouchInput { longClick() }

        composeTestRule.onNodeWithTag(pageReorderHandleTestTag(1), useUnmergedTree = true).assertDoesNotExist()
    }

    private fun openDeleteDialog() {
        composeTestRule
            .onNodeWithContentDescription(string(R.string.page_list_selection_delete))
            .performClick()
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
        repository = FakePageRepository(pages)
        val viewModel = PageListViewModel(projectId, repository)
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
            onDeleteSelectedRequested = {
                deleteRequestCount++
                viewModel.onDeleteSelectedRequested()
            },
            onDeleteConfirmed = viewModel::onDeleteConfirmed,
            onDeleteDismissed = viewModel::onDeleteDismissed,
            onPageMoved = viewModel::onPageMoved,
            onReorderFinished = viewModel::onReorderFinished,
            onUndoRequested = viewModel::onUndoRequested,
            onMessageDismissed = viewModel::onMessageDismissed,
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

    /** 画面から出る編集（並べ替え・削除・取り消し）をメモリ上で実際に反映する代役 */
    private class FakePageRepository(initialPages: List<Page>) : PageRepository {
        private var pages: List<Page> = initialPages
        private var undoSnapshot: List<Page>? = null

        val reorderCalls = mutableListOf<List<UUID>>()
        val deleteCalls = mutableListOf<Set<UUID>>()

        override suspend fun insert(page: Page) = throw UnsupportedOperationException()

        override suspend fun findById(id: UUID): Page? = pages.firstOrNull { it.id == id }

        override suspend fun findByProject(projectId: UUID): List<Page> = pages.filter { it.projectId == projectId }

        override suspend fun reorder(
            projectId: UUID,
            orderedPageIds: List<UUID>,
        ) {
            reorderCalls += orderedPageIds
            val byId = pages.associateBy(Page::id)
            undoSnapshot = pages
            pages = orderedPageIds.mapIndexedNotNull { index, id -> byId[id]?.copy(sequence = index + 1) }
        }

        override suspend fun delete(
            projectId: UUID,
            pageIds: Set<UUID>,
        ) {
            deleteCalls += pageIds
            undoSnapshot = pages
            pages =
                pages
                    .filterNot { it.id in pageIds }
                    .mapIndexed { index, page -> page.copy(sequence = index + 1) }
        }

        override suspend fun updateRotation(
            pageId: UUID,
            rotation: Int,
        ) = throw UnsupportedOperationException()

        override suspend fun updateCrop(
            pageId: UUID,
            crop: PageCrop,
        ) = throw UnsupportedOperationException()

        override suspend fun undoLastEdit(): Boolean {
            val snapshot = undoSnapshot ?: return false
            pages = snapshot
            undoSnapshot = null
            return true
        }
    }

    private companion object {
        /** requirements §16.1 の最低基準 */
        const val LARGE_PAGE_COUNT = 500
        const val THUMBNAIL_PIXELS = 8

        /**
         * ドラッグ判定に入るまでに食われるタッチスロップぶんの余分な移動（px）。
         * 実測値ではなく「スロップを確実に超える小さな値」として置く
         */
        const val TOUCH_SLOP_PX = 40f

        /** バッジの取り違え（別ページの警告が混ざる等）を検出するための全候補 */
        val OCR_LABEL_RES =
            listOf(
                R.string.page_list_ocr_pending,
                R.string.page_list_ocr_running,
                R.string.page_list_ocr_succeeded,
                R.string.page_list_ocr_failed,
                R.string.page_list_ocr_stale,
            )
        val WARNING_LABEL_RES =
            listOf(
                R.string.page_list_warning_duplicate,
                R.string.page_list_warning_black,
                R.string.page_list_warning_image_error,
            )
    }
}
