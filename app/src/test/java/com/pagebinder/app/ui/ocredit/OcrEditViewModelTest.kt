package com.pagebinder.app.ui.ocredit

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * OCR編集画面の受け入れ基準（docs/specs/09-ocr.md §9）を UiState と保存内容の両方から確認する。
 *
 * 要は2点。
 * 1. 修正は editedText へ保存され、fullText は変わらない
 * 2. 「元へ戻す」で editedText が破棄される
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OcrEditViewModelTest {
    private val projectId = UUID.fromString("30000000-0000-0000-0000-000000000001")
    private val pageId = UUID.fromString("50000000-0000-0000-0000-000000000012")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `初期表示はOCR結果の全文で未修正`() =
        runTest {
            val viewModel = createViewModel()

            val uiState = viewModel.uiState.value
            assertFalse(uiState.loading)
            assertEquals(12, uiState.pageSequence)
            assertEquals(ORIGINAL_TEXT, uiState.originalText)
            assertEquals(ORIGINAL_TEXT, uiState.draftText)
            assertNull(uiState.savedEditedText)
            assertFalse(uiState.edited)
            assertFalse(uiState.canSave)
            assertFalse(uiState.canRevert)
        }

    @Test
    fun `保存済みの修正があるときは修正済みとして開く`() =
        runTest {
            val viewModel = createViewModel(editedText = EDITED_TEXT)

            val uiState = viewModel.uiState.value
            assertTrue(uiState.edited)
            assertEquals(ORIGINAL_TEXT, uiState.originalText)
            assertEquals(EDITED_TEXT, uiState.draftText)
        }

    @Test
    fun `修正はeditedTextへ保存されfullTextは変わらない`() =
        runTest {
            val results = FakeOcrResultRepository(storedResult())
            val viewModel = createViewModel(results = results)

            viewModel.onTextChange(EDITED_TEXT)
            assertTrue(viewModel.uiState.value.canSave)
            // 保存前は editedText を書いていない
            assertNull(results.stored?.editedText)

            viewModel.onSaveRequested()

            val stored = requireNotNull(results.stored)
            assertEquals(EDITED_TEXT, stored.editedText)
            assertEquals(ORIGINAL_TEXT, stored.fullText)
            assertEquals(BLOCKS_JSON, stored.blocksJson)

            val uiState = viewModel.uiState.value
            assertTrue(uiState.edited)
            assertEquals(EDITED_TEXT, uiState.savedEditedText)
            // 画面が持つ元のOCR結果も動かない
            assertEquals(ORIGINAL_TEXT, uiState.originalText)
            assertFalse(uiState.canSave)
            assertEquals(OcrEditMessage.SAVED, uiState.message)
        }

    @Test
    fun `元へ戻すとeditedTextが破棄される`() =
        runTest {
            val results = FakeOcrResultRepository(storedResult(editedText = EDITED_TEXT))
            val viewModel = createViewModel(results = results)
            assertTrue(viewModel.uiState.value.edited)

            viewModel.onRevertRequested()
            assertTrue(viewModel.uiState.value.revertDialogVisible)
            // 確認を出しただけでは何も捨てない
            assertEquals(EDITED_TEXT, results.stored?.editedText)

            viewModel.onRevertConfirmed()

            val stored = requireNotNull(results.stored)
            assertNull(stored.editedText)
            assertEquals(ORIGINAL_TEXT, stored.fullText)

            val uiState = viewModel.uiState.value
            assertFalse(uiState.edited)
            assertNull(uiState.savedEditedText)
            assertEquals(ORIGINAL_TEXT, uiState.draftText)
            assertFalse(uiState.revertDialogVisible)
            assertEquals(OcrEditMessage.REVERTED, uiState.message)
        }

    @Test
    fun `確認をやめれば修正は残る`() =
        runTest {
            val results = FakeOcrResultRepository(storedResult(editedText = EDITED_TEXT))
            val viewModel = createViewModel(results = results)

            viewModel.onRevertRequested()
            viewModel.onRevertDismissed()

            assertFalse(viewModel.uiState.value.revertDialogVisible)
            assertTrue(viewModel.uiState.value.edited)
            assertEquals(EDITED_TEXT, results.stored?.editedText)
        }

    @Test
    fun `保存に失敗しても元のOCR結果と編集内容は残る`() =
        runTest {
            val results = FakeOcrResultRepository(storedResult(), failWrites = true)
            val viewModel = createViewModel(results = results)

            viewModel.onTextChange(EDITED_TEXT)
            viewModel.onSaveRequested()

            val uiState = viewModel.uiState.value
            assertEquals(OcrEditMessage.SAVE_FAILED, uiState.message)
            assertFalse(uiState.edited)
            assertEquals(EDITED_TEXT, uiState.draftText)
            assertEquals(ORIGINAL_TEXT, uiState.originalText)
            assertNull(results.stored?.editedText)
        }

    @Test
    fun `OCR結果が無いページでは本文を編集できない`() =
        runTest {
            val viewModel = createViewModel(results = FakeOcrResultRepository(stored = null))

            viewModel.onTextChange(EDITED_TEXT)

            val uiState = viewModel.uiState.value
            assertFalse(uiState.resultAvailable)
            assertEquals("", uiState.draftText)
            assertFalse(uiState.canSave)
            assertFalse(uiState.canRevert)
        }

    @Test
    fun `再実行はOCRキューへ載せて状態を待機にする`() =
        runTest {
            val jobs = FakeOcrJobRepository()
            val viewModel = createViewModel(jobs = jobs)

            viewModel.onRerunRequested()

            assertEquals(listOf(pageId), jobs.pendingRequests)
            assertEquals(PageOcrState.PENDING, viewModel.uiState.value.ocrState)
            assertEquals(OcrEditMessage.RERUN_QUEUED, viewModel.uiState.value.message)
            // 既にキューへ載ったページは二重に積まない
            assertFalse(viewModel.uiState.value.canRerun)
        }

    @Test
    fun `実行中のページは再実行できない`() =
        runTest {
            val jobs = FakeOcrJobRepository()
            val viewModel = createViewModel(ocrState = PageOcrState.RUNNING, jobs = jobs)

            viewModel.onRerunRequested()

            assertTrue(jobs.pendingRequests.isEmpty())
        }

    @Test
    fun `ページ内検索は一致件数を数えて前後へ回る`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onSearchToggled()
            viewModel.onSearchQueryChange("OCR")

            val search = viewModel.uiState.value.search
            assertTrue(search.visible)
            assertEquals(2, search.matchCount)
            assertEquals(1, search.currentMatchNumber)
            assertEquals(ORIGINAL_TEXT.indexOf("OCR"), search.currentMatch?.first)

            viewModel.onSearchNext()
            assertEquals(2, viewModel.uiState.value.search.currentMatchNumber)
            // 末尾の次は先頭へ戻る
            viewModel.onSearchNext()
            assertEquals(1, viewModel.uiState.value.search.currentMatchNumber)
            viewModel.onSearchPrevious()
            assertEquals(2, viewModel.uiState.value.search.currentMatchNumber)
        }

    @Test
    fun `検索を閉じると検索語も一致位置も残らない`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.onSearchToggled()
            viewModel.onSearchQueryChange("OCR")

            viewModel.onSearchToggled()

            val search = viewModel.uiState.value.search
            assertFalse(search.visible)
            assertEquals("", search.query)
            assertEquals(0, search.matchCount)
        }

    @Test
    fun `一致しない検索語は0件として示す`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.onSearchToggled()

            viewModel.onSearchQueryChange("該当なし")

            assertTrue(viewModel.uiState.value.search.noMatch)
            assertEquals(0, viewModel.uiState.value.search.matchCount)
        }

    @Test
    fun `本文を直すと検索の一致も数え直す`() =
        runTest {
            val viewModel = createViewModel()
            viewModel.onSearchToggled()
            viewModel.onSearchQueryChange("OCR")
            assertEquals(2, viewModel.uiState.value.search.matchCount)

            viewModel.onTextChange("OCR")

            assertEquals(1, viewModel.uiState.value.search.matchCount)
            assertEquals(1, viewModel.uiState.value.search.currentMatchNumber)
        }

    @Test
    fun `ズームは下限と上限で止まる`() =
        runTest {
            val viewModel = createViewModel()

            repeat(MANY_STEPS) { viewModel.onZoomOut() }
            assertEquals(MIN_ZOOM_PERCENT, viewModel.uiState.value.zoomPercent)
            assertFalse(viewModel.uiState.value.canZoomOut)

            repeat(MANY_STEPS) { viewModel.onZoomIn() }
            assertEquals(MAX_ZOOM_PERCENT, viewModel.uiState.value.zoomPercent)
            assertFalse(viewModel.uiState.value.canZoomIn)
        }

    @Test
    fun `分割比率はどちらのペインも潰れない範囲に収まる`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onSplitRatioChange(0.95f)
            assertEquals(MAX_SPLIT_RATIO, viewModel.uiState.value.splitRatio, 0f)

            viewModel.onSplitRatioChange(0.05f)
            assertEquals(MIN_SPLIT_RATIO, viewModel.uiState.value.splitRatio, 0f)
        }

    @Test
    fun `ページを読めないときはエラー表示になり再読み込みで復帰する`() =
        runTest {
            val pages = FakePageRepository(page(), failNextReads = 1)
            val viewModel =
                OcrEditViewModel(pageId, pages, FakeOcrResultRepository(storedResult()), ocrQueue())
            assertTrue(viewModel.uiState.value.loadFailed)

            viewModel.load()

            assertFalse(viewModel.uiState.value.loadFailed)
            assertEquals(ORIGINAL_TEXT, viewModel.uiState.value.draftText)
        }

    private fun createViewModel(
        ocrState: PageOcrState = PageOcrState.SUCCEEDED,
        editedText: String? = null,
        results: FakeOcrResultRepository = FakeOcrResultRepository(storedResult(editedText = editedText)),
        jobs: FakeOcrJobRepository = FakeOcrJobRepository(),
    ) = OcrEditViewModel(
        pageId = pageId,
        pageRepository = FakePageRepository(page(ocrState = ocrState)),
        ocrResultRepository = results,
        ocrQueue = ocrQueue(jobs),
    )

    private fun ocrQueue(jobs: FakeOcrJobRepository = FakeOcrJobRepository()) = OcrQueue(jobs) { }

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

    /** この画面が読むのは findById だけ。ページの書き込み系はここから呼ばない */
    private class FakePageRepository(
        private val page: Page,
        private var failNextReads: Int = 0,
    ) : PageRepository {
        override suspend fun insert(page: Page) = throw UnsupportedOperationException()

        override suspend fun findById(id: UUID): Page? {
            if (failNextReads > 0) {
                failNextReads--
                throw IOException("read failed")
            }
            return page.takeIf { it.id == id }
        }

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

    /**
     * 保存内容をそのまま覗ける代役。editedText の書き換えだけを許し、
     * fullText / blocksJson を差し替える口は持たない（production の口と同じ形）。
     */
    private class FakeOcrResultRepository(
        var stored: StoredOcrResult?,
        private val failWrites: Boolean = false,
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
            if (failWrites) return false
            val current = stored?.takeIf { it.pageId == pageId } ?: return false
            stored = current.copy(editedText = editedText)
            return true
        }
    }

    /** 再実行がキューへ届いたことだけを見る代役 */
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
        const val EDITED_TEXT = "OCRの結果です。\n2行目にもOCRという語が入っています。\n3行目を直しました。"
        const val BLOCKS_JSON = """{"blocks":[]}"""

        /** 上下限に必ず張り付くだけの回数 */
        const val MANY_STEPS = 20
    }
}
