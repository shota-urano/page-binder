package com.pagebinder.app.ui.pageedit

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.R
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageCropScope
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader
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
 * 回転・切り取り編集画面の受け入れ基準を、利用者操作の側から確認する
 * （docs/design/08-page-edit.md / docs/specs/08-page-editing.md §3.2）。
 *
 * production の [PageEditScreen] と [PageEditViewModel] をそのまま組み合わせ、
 * 画面のタップ・ドラッグが production の保存内容をどう動かすかだけを見る。
 */
@RunWith(AndroidJUnit4::class)
class PageEditScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val projectId = UUID.fromString("30000000-0000-0000-0000-000000000001")
    private val pageId = UUID.fromString("50000000-0000-0000-0000-000000000012")
    private lateinit var pages: FakePageRepository
    private var closed = false

    @Test
    fun `編集キャンバスと切り取り枠のつまみが出る`() {
        showScreen()

        composeTestRule.onNodeWithTag(PAGE_EDIT_CANVAS_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.page_edit_title, 12)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.page_edit_non_destructive)).assertIsDisplayed()
        PageCropHandle.entries.forEach { handle ->
            composeTestRule.onNodeWithTag(pageEditCropHandleTestTag(handle)).assertIsDisplayed()
        }
    }

    @Test
    fun `90度回転ボタンで回転が進み保存される`() {
        val viewModel = showScreen()

        composeTestRule.onNodeWithText(string(R.string.page_edit_rotate)).performClick()
        assertEquals(90, viewModel.uiState.value.rotation)

        composeTestRule.onNodeWithText(string(R.string.page_edit_save)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(pageId to 90), pages.rotatedPages)
    }

    @Test
    fun `つまみのドラッグ結果は正規化された切り取り座標として保存される`() {
        val viewModel = showScreen()

        dragTopLeftHandle()

        val crop = viewModel.uiState.value.crop
        assertTrue("左辺が動いていない: $crop", crop.left > 0f)
        assertTrue("上辺が動いていない: $crop", crop.top > 0f)

        composeTestRule.onNodeWithText(string(R.string.page_edit_save)).performClick()
        composeTestRule.waitForIdle()

        val (savedPageId, savedCrop) = pages.croppedPages.single()
        assertEquals(pageId, savedPageId)
        assertEquals(crop, savedCrop)
        // 保存されるのは 0〜1 の正規化座標だけ（端末の解像度・表示倍率に依存しない）
        assertTrue(
            "正規化されていない: $savedCrop",
            listOf(savedCrop.left, savedCrop.top, savedCrop.right, savedCrop.bottom).all { it in 0f..1f },
        )
    }

    @Test
    fun `一括適用は件数を確認してから書籍の全ページへ適用される`() {
        showScreen(projectPages = 3)

        dragTopLeftHandle()
        composeTestRule.onNodeWithText(string(R.string.page_edit_apply_to_all)).performClick()
        composeTestRule.onNodeWithText(string(R.string.page_edit_save)).performClick()

        composeTestRule.onNodeWithText(string(R.string.page_edit_apply_dialog_message, 3)).assertIsDisplayed()
        assertTrue(pages.croppedPages.isEmpty())

        composeTestRule.onNodeWithText(string(R.string.page_edit_apply_dialog_confirm)).performClick()
        composeTestRule.waitForIdle()

        // 書籍の全ページが同じ切り取りになる
        assertEquals(3, pages.croppedPages.size)
        assertEquals(1, pages.croppedPages.map { it.second }.distinct().size)
        composeTestRule
            .onNodeWithText(string(R.string.page_edit_message_saved_to_all, 3))
            .assertIsDisplayed()
    }

    @Test
    fun `一括適用の確認をキャンセルすれば適用されない`() {
        showScreen(projectPages = 2)

        dragTopLeftHandle()
        composeTestRule.onNodeWithText(string(R.string.page_edit_apply_to_all)).performClick()
        composeTestRule.onNodeWithText(string(R.string.page_edit_save)).performClick()
        composeTestRule.onNodeWithText(string(R.string.page_edit_apply_dialog_cancel)).performClick()
        composeTestRule.waitForIdle()

        assertTrue(pages.croppedPages.isEmpty())
    }

    @Test
    fun `元に戻すで直前の回転が取り消される`() {
        val viewModel = showScreen()

        composeTestRule.onNodeWithText(string(R.string.page_edit_rotate)).performClick()
        composeTestRule.onNodeWithText(string(R.string.page_edit_rotate)).performClick()
        assertEquals(180, viewModel.uiState.value.rotation)

        composeTestRule.onNodeWithText(string(R.string.page_edit_undo)).performClick()

        assertEquals(90, viewModel.uiState.value.rotation)
    }

    @Test
    fun `未保存の編集があるまま閉じると破棄確認が出る`() {
        showScreen()

        composeTestRule.onNodeWithText(string(R.string.page_edit_rotate)).performClick()
        composeTestRule.onNodeWithContentDescription(string(R.string.page_edit_close)).performClick()

        composeTestRule.onNodeWithText(string(R.string.page_edit_discard_dialog_title)).assertIsDisplayed()
        assertFalse(closed)
        assertTrue(pages.rotatedPages.isEmpty())

        composeTestRule.onNodeWithText(string(R.string.page_edit_discard_dialog_confirm)).performClick()
        composeTestRule.waitForIdle()

        assertTrue(closed)
        // 破棄しても元画像・保存済みの属性はそのまま
        assertTrue(pages.rotatedPages.isEmpty())
        assertTrue(pages.croppedPages.isEmpty())
    }

    @Test
    fun `編集していなければ確認なしで閉じる`() {
        showScreen()

        composeTestRule.onNodeWithContentDescription(string(R.string.page_edit_close)).performClick()
        composeTestRule.waitForIdle()

        assertTrue(closed)
    }

    /** 左上のつまみを右下へ引っぱって切り取り範囲を狭める */
    private fun dragTopLeftHandle() {
        composeTestRule.onNodeWithTag(pageEditCropHandleTestTag(PageCropHandle.TOP_LEFT)).performTouchInput {
            swipe(start = center, end = center + Offset(width * 2f, height * 2f))
        }
        composeTestRule.waitForIdle()
    }

    private fun showScreen(projectPages: Int = 1): PageEditViewModel {
        closed = false
        pages = FakePageRepository((1..projectPages).map { page(sequence = it + 11) })
        val viewModel = PageEditViewModel(pageId = pageId, pageRepository = pages)
        composeTestRule.setContent {
            PageBinderTheme {
                val uiState by viewModel.uiState.collectAsState()
                PageEditScreen(
                    uiState = uiState,
                    imageLoader = PageThumbnailLoader { ImageBitmap(IMAGE_WIDTH_PIXELS, IMAGE_HEIGHT_PIXELS) },
                    actions = actionsOf(viewModel),
                )
            }
        }
        return viewModel
    }

    /** 画面遷移の判断は Route と同じ（未保存の変更があるときだけ破棄確認を挟む） */
    private fun actionsOf(viewModel: PageEditViewModel) =
        PageEditScreenActions(
            onCloseRequested = {
                if (viewModel.uiState.value.unsavedChanges) viewModel.onDiscardRequested() else closed = true
            },
            onReload = viewModel::load,
            onRotateClockwise = viewModel::onRotateClockwise,
            onUndoRequested = viewModel::onUndoRequested,
            onCropHandleDragged = viewModel::onCropHandleDragged,
            onCropDragFinished = viewModel::onCropDragFinished,
            onApplyToAllPagesChanged = viewModel::onApplyToAllPagesChanged,
            onSaveRequested = viewModel::onSaveRequested,
            onBulkApplyConfirmed = viewModel::onBulkApplyConfirmed,
            onBulkApplyDismissed = viewModel::onBulkApplyDismissed,
            onDiscardConfirmed = {
                viewModel.onDiscardDismissed()
                closed = true
            },
            onDiscardDismissed = viewModel::onDiscardDismissed,
            onMessageDismissed = viewModel::onMessageDismissed,
        )

    private fun string(
        resId: Int,
        vararg formatArgs: Any,
    ): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(resId, *formatArgs)

    private fun page(sequence: Int = 12) =
        Page(
            id =
                if (sequence == 12) {
                    pageId
                } else {
                    UUID.fromString("50000000-0000-0000-0000-0000000000$sequence")
                },
            projectId = projectId,
            sequence = sequence,
            originalImagePath = "pages/$sequence.webp",
            width = 1080,
            height = 1920,
            rotation = 0,
            crop = PageCrop(),
            capturedAt = Instant.parse("2026-08-26T00:00:00Z"),
            contentHash = "content-$sequence",
            perceptualHash = "perceptual-$sequence",
            qualityState = PageQualityState.NORMAL,
            ocrState = PageOcrState.SUCCEEDED,
        )

    /** 書き込みの中身を覗ける代役。元画像を触る口は持たない（FR-IMG-007） */
    private class FakePageRepository(
        private var pages: List<Page>,
    ) : PageRepository {
        val rotatedPages = mutableListOf<Pair<UUID, Int>>()
        val croppedPages = mutableListOf<Pair<UUID, PageCrop>>()

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

        /** 1回の呼び出しで全部書くか、何も書かないか（production の @Transaction と同じ約束） */
        override suspend fun updatePageEdit(
            pageId: UUID,
            rotation: Int,
            crop: PageCrop,
            cropScope: PageCropScope,
        ): Int {
            val page = pages.first { it.id == pageId }
            val cropTargets =
                if (cropScope == PageCropScope.PROJECT) {
                    pages.filter { it.projectId == page.projectId }
                } else {
                    listOf(page)
                }
            if (page.rotation != rotation) rotatedPages += pageId to rotation
            cropTargets.filter { it.crop != crop }.forEach { croppedPages += it.id to crop }
            val cropTargetIds = cropTargets.map(Page::id).toSet()
            pages =
                pages.map {
                    when {
                        it.id == pageId && it.id in cropTargetIds -> it.copy(rotation = rotation, crop = crop)
                        it.id == pageId -> it.copy(rotation = rotation)
                        it.id in cropTargetIds -> it.copy(crop = crop)
                        else -> it
                    }
                }
            return cropTargets.size
        }

        override suspend fun undoLastEdit(): Boolean = throw UnsupportedOperationException()
    }

    private companion object {
        /** 縦長のページ画像を模した大きさ。切り取り枠の縦横比の扱いを実機と揃える */
        const val IMAGE_WIDTH_PIXELS = 9
        const val IMAGE_HEIGHT_PIXELS = 16
    }
}
