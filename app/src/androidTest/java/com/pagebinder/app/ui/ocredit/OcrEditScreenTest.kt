package com.pagebinder.app.ui.ocredit

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.R
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
import com.pagebinder.app.ui.pagelist.PageThumbnailLoader
import com.pagebinder.app.ui.theme.PageBinderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/**
 * OCR編集画面の受け入れ基準を、利用者操作の側から確認する（docs/specs/09-ocr.md §3.5）。
 *
 * production の [OcrEditScreen] と [OcrEditViewModel] をそのまま組み合わせ、
 * 画面のタップ・入力が production の状態と保存内容をどう動かすかだけを見る。
 */
@RunWith(AndroidJUnit4::class)
class OcrEditScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val projectId = UUID.fromString("30000000-0000-0000-0000-000000000001")
    private val pageId = UUID.fromString("50000000-0000-0000-0000-000000000012")
    private lateinit var results: FakeOcrResultRepository
    private lateinit var jobs: FakeOcrJobRepository

    @Test
    fun `分割表示でページ画像とOCRテキストが同時に出る`() {
        showScreen()

        composeTestRule.onNodeWithTag(OCR_EDIT_IMAGE_PANE_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(OCR_EDIT_TEXT_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.ocr_edit_title, 12)).assertIsDisplayed()
        composeTestRule
            .onNodeWithContentDescription(string(R.string.ocr_edit_split_handle))
            .assertIsDisplayed()
    }

    @Test
    fun `修正して保存すると修正済みになり元のOCR結果は保持される`() {
        showScreen()

        composeTestRule.onNodeWithTag(OCR_EDIT_TEXT_TEST_TAG).performTextReplacement(EDITED_TEXT)
        composeTestRule.onNodeWithText(string(R.string.ocr_edit_save)).performClick()
        composeTestRule.waitForIdle()

        val stored = requireNotNull(results.stored)
        assertEquals(EDITED_TEXT, stored.editedText)
        assertEquals(ORIGINAL_TEXT, stored.fullText)
        composeTestRule.onNodeWithText(string(R.string.ocr_edit_badge_edited)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.ocr_edit_original_preserved)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.ocr_edit_message_saved)).assertIsDisplayed()
    }

    @Test
    fun `元のOCR結果へ戻すは確認してから修正を破棄する`() {
        showScreen(editedText = EDITED_TEXT)
        composeTestRule.onNodeWithText(string(R.string.ocr_edit_badge_edited)).assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.ocr_edit_revert)).performClick()
        composeTestRule.onNodeWithText(string(R.string.ocr_edit_revert_dialog_title)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.ocr_edit_revert_dialog_confirm)).performClick()
        composeTestRule.waitForIdle()

        val stored = requireNotNull(results.stored)
        assertNull(stored.editedText)
        assertEquals(ORIGINAL_TEXT, stored.fullText)
        composeTestRule.onNodeWithText(string(R.string.ocr_edit_badge_edited)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.ocr_edit_message_reverted)).assertIsDisplayed()
    }

    @Test
    fun `確認をキャンセルすれば修正は残る`() {
        showScreen(editedText = EDITED_TEXT)

        composeTestRule.onNodeWithText(string(R.string.ocr_edit_revert)).performClick()
        composeTestRule.onNodeWithText(string(R.string.ocr_edit_revert_dialog_cancel)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(EDITED_TEXT, results.stored?.editedText)
        composeTestRule.onNodeWithText(string(R.string.ocr_edit_badge_edited)).assertIsDisplayed()
    }

    @Test
    fun `検索アイコンでページ内検索が開き一致件数が出る`() {
        val viewModel = showScreen()

        composeTestRule.onNodeWithContentDescription(string(R.string.ocr_edit_search)).performClick()
        composeTestRule.onNodeWithTag(OCR_EDIT_SEARCH_FIELD_TEST_TAG).performTextInput("OCR")
        composeTestRule.waitForIdle()

        assertEquals(2, viewModel.uiState.value.search.matchCount)
        composeTestRule.onNodeWithText(string(R.string.ocr_edit_search_match_count, 1, 2)).assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription(string(R.string.ocr_edit_search_next)).performClick()

        composeTestRule.onNodeWithText(string(R.string.ocr_edit_search_match_count, 2, 2)).assertIsDisplayed()
    }

    @Test
    fun `再実行アイコンでOCRキューへ載る`() {
        showScreen()

        composeTestRule.onNodeWithContentDescription(string(R.string.ocr_edit_rerun)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(pageId), jobs.pendingRequests)
        composeTestRule.onNodeWithText(string(R.string.ocr_edit_message_rerun_queued)).assertIsDisplayed()
    }

    @Test
    fun `ズームの操作は倍率表示に出る`() {
        showScreen()

        composeTestRule.onNodeWithContentDescription(string(R.string.ocr_edit_zoom_in)).performClick()

        composeTestRule
            .onNodeWithText(string(R.string.ocr_edit_zoom_percent, DEFAULT_ZOOM_PERCENT + ZOOM_STEP_PERCENT))
            .assertIsDisplayed()
    }

    @Test
    fun `OCR結果が無いページでは案内を出し編集させない`() {
        showScreen(stored = null)

        composeTestRule.onNodeWithText(string(R.string.ocr_edit_result_missing)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(OCR_EDIT_TEXT_TEST_TAG).assertDoesNotExist()
    }

    private fun showScreen(
        ocrState: PageOcrState = PageOcrState.SUCCEEDED,
        editedText: String? = null,
        stored: StoredOcrResult? = storedResult(editedText),
    ): OcrEditViewModel {
        results = FakeOcrResultRepository(stored)
        jobs = FakeOcrJobRepository()
        val viewModel =
            OcrEditViewModel(
                pageId = pageId,
                pageRepository = FakePageRepository(page(ocrState)),
                ocrResultRepository = results,
                ocrQueue = OcrQueue(jobs) { },
            )
        composeTestRule.setContent {
            PageBinderTheme {
                val uiState by viewModel.uiState.collectAsState()
                OcrEditScreen(
                    uiState = uiState,
                    imageLoader = PageThumbnailLoader { ImageBitmap(IMAGE_PIXELS, IMAGE_PIXELS) },
                    actions = actionsOf(viewModel),
                )
            }
        }
        return viewModel
    }

    private fun actionsOf(viewModel: OcrEditViewModel) =
        OcrEditScreenActions(
            onBack = {},
            onReload = viewModel::load,
            onSearchToggled = viewModel::onSearchToggled,
            onSearchQueryChange = viewModel::onSearchQueryChange,
            onSearchNext = viewModel::onSearchNext,
            onSearchPrevious = viewModel::onSearchPrevious,
            onRerunRequested = viewModel::onRerunRequested,
            onZoomIn = viewModel::onZoomIn,
            onZoomOut = viewModel::onZoomOut,
            onSplitRatioChange = viewModel::onSplitRatioChange,
            onTextChange = viewModel::onTextChange,
            onSaveRequested = viewModel::onSaveRequested,
            onRevertRequested = viewModel::onRevertRequested,
            onRevertConfirmed = viewModel::onRevertConfirmed,
            onRevertDismissed = viewModel::onRevertDismissed,
        )

    private fun string(
        resId: Int,
        vararg formatArgs: Any,
    ): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(resId, *formatArgs)

    private fun page(ocrState: PageOcrState = PageOcrState.SUCCEEDED) =
        Page(
            id = pageId,
            projectId = projectId,
            sequence = 12,
            originalImagePath = "pages/12.webp",
            width = 1080,
            height = 1920,
            rotation = 0,
            crop = PageCrop(),
            capturedAt = Instant.parse("2026-08-26T00:00:00Z"),
            contentHash = "content-12",
            perceptualHash = "perceptual-12",
            qualityState = PageQualityState.NORMAL,
            ocrState = ocrState,
        )

    private fun storedResult(editedText: String? = null) =
        StoredOcrResult(
            pageId = pageId,
            fullText = ORIGINAL_TEXT,
            blocksJson = BLOCKS_JSON,
            editedText = editedText,
            engineVersion = "test-engine",
            sourceImageHash = "source-hash",
            processedAt = Instant.parse("2026-08-26T00:01:00Z"),
        )

    private class FakePageRepository(
        private val page: Page,
    ) : PageRepository {
        override suspend fun insert(page: Page) = throw UnsupportedOperationException()

        override suspend fun findById(id: UUID): Page? = page.takeIf { it.id == id }

        override suspend fun findByProject(projectId: UUID): List<Page> = throw UnsupportedOperationException()

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

    /** 保存内容を覗ける代役。editedText だけを差し替えられる（production の口と同じ形） */
    private class FakeOcrResultRepository(
        var stored: StoredOcrResult?,
    ) : OcrResultRepository {
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

    private class FakeOcrJobRepository : OcrJobRepository {
        val pendingRequests = mutableListOf<UUID>()

        override suspend fun markPending(
            pageId: UUID,
            expectedStates: Set<OcrState>,
        ): Boolean {
            pendingRequests += pageId
            return true
        }

        override suspend fun markProjectPending(
            projectId: UUID,
            expectedStates: Set<OcrState>,
        ): Int = throw UnsupportedOperationException()

        override suspend fun claimNextPending(): OcrPage? = throw UnsupportedOperationException()

        override suspend fun recoverInterrupted(): Int = throw UnsupportedOperationException()

        override suspend fun storeSuccess(
            pageId: UUID,
            result: StoredOcrResult,
        ): Boolean = throw UnsupportedOperationException()

        override suspend fun markFailed(pageId: UUID): Boolean = throw UnsupportedOperationException()

        override suspend fun returnToPending(pageId: UUID): Boolean = throw UnsupportedOperationException()
    }

    private companion object {
        /** 「OCR」が2箇所出る本文。実在の書籍の文面は使わない */
        const val ORIGINAL_TEXT = "OCRの結果です。\n2行目にもOCRという語が入っています。\n3行目。"
        const val EDITED_TEXT = "修正した本文です。"
        const val BLOCKS_JSON = """{"blocks":[]}"""
        const val IMAGE_PIXELS = 8
    }
}
