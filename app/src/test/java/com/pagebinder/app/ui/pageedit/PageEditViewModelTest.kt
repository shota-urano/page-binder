package com.pagebinder.app.ui.pageedit

import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageCropScope
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.PageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
 * 回転・切り取り編集画面の受け入れ基準（docs/specs/08-page-editing.md §9）を
 * UiState と保存内容の両方から確認する。
 *
 * 要は3点。
 * 1. 編集結果が **正規化 crop 座標** として保存される（0〜1・端末の解像度に依存しない）
 * 2. 回転は90度単位でだけ保存される（FR-EDT-004）
 * 3. 保存されるのは属性だけで、元画像には触れない（FR-IMG-007）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageEditViewModelTest {
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
    fun `初期表示は保存済みの回転と切り取りで開く`() =
        runTest {
            val viewModel = createViewModel(rotation = 90, crop = PageCrop(0.1f, 0.2f, 0.8f, 0.9f))

            val uiState = viewModel.uiState.value
            assertFalse(uiState.loading)
            assertEquals(12, uiState.pageSequence)
            assertEquals(90, uiState.rotation)
            assertEquals(PageCrop(0.1f, 0.2f, 0.8f, 0.9f), uiState.crop)
            assertFalse(uiState.unsavedChanges)
            assertFalse(uiState.canSave)
            assertFalse(uiState.canUndo)
        }

    @Test
    fun `編集した切り取りは正規化座標のまま保存される`() =
        runTest {
            val pages = FakePageRepository(listOf(page()))
            val viewModel = createViewModel(pages = pages)

            // つまみのドラッグ量は画面側で正規化してから渡される（px はここへ来ない）
            viewModel.onCropHandleDragged(PageCropHandle.TOP_LEFT, dx = 0.25f, dy = 0.1f)
            viewModel.onCropHandleDragged(PageCropHandle.BOTTOM_RIGHT, dx = -0.05f, dy = -0.2f)
            viewModel.onCropDragFinished()
            assertTrue(viewModel.uiState.value.canSave)
            // 保存するまでは書き込まない
            assertTrue(pages.croppedPages.isEmpty())

            viewModel.onSaveRequested()

            val saved = pages.croppedPages.single()
            assertEquals(pageId, saved.first)
            assertCrop(PageCrop(0.25f, 0.1f, 0.95f, 0.8f), saved.second)
            // 保存後は編集中の値と保存済みの値が一致する
            val uiState = viewModel.uiState.value
            assertFalse(uiState.unsavedChanges)
            assertEquals(PageEditMessage.Saved, uiState.message)
        }

    @Test
    fun `切り取りは画像の外へ出ず最小の大きさを下回らない`() =
        runTest {
            val pages = FakePageRepository(listOf(page()))
            val viewModel = createViewModel(pages = pages)

            // 画像の外まで大きく引っぱっても 0〜1 に収まる
            viewModel.onCropHandleDragged(PageCropHandle.TOP_LEFT, dx = -5f, dy = -5f)
            assertCrop(PageCrop(0f, 0f, 1f, 1f), viewModel.uiState.value.crop)

            // 反対側の辺を越えて詰めても、辺の長さは最小値で止まる
            viewModel.onCropHandleDragged(PageCropHandle.TOP_LEFT, dx = 5f, dy = 5f)
            val crop = viewModel.uiState.value.crop
            assertTrue(crop.left in 0f..1f && crop.top in 0f..1f)
            assertEquals(MIN_CROP_SIZE, crop.right - crop.left, TOLERANCE)
            assertEquals(MIN_CROP_SIZE, crop.bottom - crop.top, TOLERANCE)
        }

    @Test
    fun `回転は90度単位で保存され一周すると0度へ戻る`() =
        runTest {
            val pages = FakePageRepository(listOf(page()))
            val viewModel = createViewModel(pages = pages)

            viewModel.onRotateClockwise()
            assertEquals(90, viewModel.uiState.value.rotation)
            viewModel.onSaveRequested()
            assertEquals(pageId to 90, pages.rotatedPages.single())

            repeat(3) { viewModel.onRotateClockwise() }
            assertEquals(0, viewModel.uiState.value.rotation)
            viewModel.onSaveRequested()
            assertEquals(listOf(pageId to 90, pageId to 0), pages.rotatedPages)
        }

    @Test
    fun `回転すると切り取り範囲も回転後の座標系へ移る`() =
        runTest {
            val viewModel = createViewModel(crop = PageCrop(0f, 0f, 0.5f, 0.5f))

            viewModel.onRotateClockwise()

            // 時計回りに90度回すと、左上の四半分は右上へ来る
            assertCrop(PageCrop(0.5f, 0f, 1f, 0.5f), viewModel.uiState.value.crop)
        }

    @Test
    fun `保存で書き換わるのは回転と切り取りだけで元画像には触れない`() =
        runTest {
            val pages = FakePageRepository(listOf(page()))
            val viewModel = createViewModel(pages = pages)

            viewModel.onRotateClockwise()
            viewModel.onCropHandleDragged(PageCropHandle.BOTTOM, dy = -0.2f, dx = 0f)
            viewModel.onSaveRequested()

            assertEquals(listOf(pageId to 90), pages.rotatedPages)
            assertEquals(1, pages.croppedPages.size)
            // 元画像を指す値は最初のまま（この画面は画像ファイルの口を持たない）
            assertEquals("pages/12.webp", pages.pages.single().originalImagePath)
        }

    @Test
    fun `一括適用は件数を確認してから書籍の全ページへ同じ切り取りを書く`() =
        runTest {
            val pages = FakePageRepository(listOf(page(), page(sequence = 13), page(sequence = 14)))
            val viewModel = createViewModel(pages = pages)
            assertEquals(3, viewModel.uiState.value.projectPageCount)

            viewModel.onCropHandleDragged(PageCropHandle.LEFT, dx = 0.2f, dy = 0f)
            viewModel.onApplyToAllPagesChanged(true)
            viewModel.onSaveRequested()

            // 確認を出しただけでは1件も書かない
            assertTrue(viewModel.uiState.value.bulkConfirmationVisible)
            assertTrue(pages.croppedPages.isEmpty())

            viewModel.onBulkApplyConfirmed()

            assertEquals(3, pages.croppedPages.size)
            pages.croppedPages.forEach { (_, crop) -> assertCrop(PageCrop(0.2f, 0f, 1f, 1f), crop) }
            assertEquals(PageEditMessage.SavedToAllPages(3), viewModel.uiState.value.message)
        }

    @Test
    fun `一括適用の確認をやめれば何も書かない`() =
        runTest {
            val pages = FakePageRepository(listOf(page(), page(sequence = 13)))
            val viewModel = createViewModel(pages = pages)

            viewModel.onCropHandleDragged(PageCropHandle.LEFT, dx = 0.2f, dy = 0f)
            viewModel.onApplyToAllPagesChanged(true)
            viewModel.onSaveRequested()
            viewModel.onBulkApplyDismissed()

            assertFalse(viewModel.uiState.value.bulkConfirmationVisible)
            assertTrue(pages.croppedPages.isEmpty())
            assertTrue(viewModel.uiState.value.unsavedChanges)
        }

    @Test
    fun `一括適用は切り取りが変わっていなくても書籍全体へ広げられる`() =
        runTest {
            val crop = PageCrop(0.1f, 0.1f, 0.9f, 0.9f)
            val pages = FakePageRepository(listOf(page(crop = crop), page(sequence = 13)))
            val viewModel = createViewModel(pages = pages, crop = crop)
            assertFalse(viewModel.uiState.value.unsavedChanges)

            viewModel.onApplyToAllPagesChanged(true)
            assertTrue(viewModel.uiState.value.canSave)
            viewModel.onSaveRequested()
            viewModel.onBulkApplyConfirmed()

            // 既に同じ切り取りのページは書き直さないが、対象は書籍全体
            assertEquals(listOf(Triple(pageId, 0, PageCropScope.PROJECT)), pages.editCalls)
            assertTrue(pages.pages.all { it.crop == crop })
            assertEquals(PageEditMessage.SavedToAllPages(2), viewModel.uiState.value.message)
        }

    @Test
    fun `元に戻すは直前の1操作だけを取り消す`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onRotateClockwise()
            viewModel.onRotateClockwise()
            assertEquals(180, viewModel.uiState.value.rotation)
            assertTrue(viewModel.uiState.value.canUndo)

            viewModel.onUndoRequested()

            assertEquals(90, viewModel.uiState.value.rotation)
            // 履歴の深さは1（docs/specs/08-page-editing.md §3.4 確定）
            assertFalse(viewModel.uiState.value.canUndo)
            viewModel.onUndoRequested()
            assertEquals(90, viewModel.uiState.value.rotation)
        }

    @Test
    fun `1回のドラッグは取り消し1件として扱う`() =
        runTest {
            val viewModel = createViewModel()

            // 指を動かすたびに履歴を積むと、直前1操作が1コマ分になってしまう
            viewModel.onCropHandleDragged(PageCropHandle.TOP, dx = 0f, dy = 0.1f)
            viewModel.onCropHandleDragged(PageCropHandle.TOP, dx = 0f, dy = 0.1f)
            viewModel.onCropHandleDragged(PageCropHandle.TOP, dx = 0f, dy = 0.1f)
            viewModel.onCropDragFinished()

            viewModel.onUndoRequested()

            assertCrop(PageCrop(), viewModel.uiState.value.crop)
        }

    @Test
    fun `保存に失敗したら表示中の編集も保存済みの内容へ戻る`() =
        runTest {
            val pages = FakePageRepository(listOf(page()), failWrites = true)
            val viewModel = createViewModel(pages = pages)

            viewModel.onCropHandleDragged(PageCropHandle.TOP_LEFT, dx = 0.2f, dy = 0.2f)
            viewModel.onSaveRequested()

            // 属性更新の失敗時は UI 状態を元に戻してエラー表示（docs/specs/08-page-editing.md §6）。
            // 書けていない編集が画面に残ると、保存済みだと思って閉じてしまう
            val uiState = viewModel.uiState.value
            assertEquals(PageEditMessage.SaveFailed, uiState.message)
            assertFalse(uiState.unsavedChanges)
            assertCrop(PageCrop(), uiState.crop)
            assertFalse(uiState.canUndo)
            assertFalse(uiState.saving)
        }

    @Test
    fun `保存は回転と切り取りを1回の呼び出しでまとめて渡す`() =
        runTest {
            val pages = FakePageRepository(listOf(page()))
            val viewModel = createViewModel(pages = pages)

            viewModel.onRotateClockwise()
            viewModel.onCropHandleDragged(PageCropHandle.TOP, dx = 0f, dy = 0.2f)
            viewModel.onSaveRequested()

            // 回転と切り取りが別々の書き込みへ割れると、片方だけが残る保存が起きうる
            assertEquals(listOf(Triple(pageId, 90, PageCropScope.PAGE)), pages.editCalls)
        }

    @Test
    fun `一括適用も1回の呼び出しで書籍全体へ渡す`() =
        runTest {
            val pages = FakePageRepository(listOf(page(), page(sequence = 13), page(sequence = 14)))
            val viewModel = createViewModel(pages = pages)

            viewModel.onCropHandleDragged(PageCropHandle.LEFT, dx = 0.2f, dy = 0f)
            viewModel.onApplyToAllPagesChanged(true)
            viewModel.onSaveRequested()
            viewModel.onBulkApplyConfirmed()

            // ページごとに呼ぶと、途中で失敗したときに一部だけ適用された状態が残る
            assertEquals(listOf(Triple(pageId, 0, PageCropScope.PROJECT)), pages.editCalls)
        }

    @Test
    fun `保存に失敗したら保存済みの状態を保存先から取り直す`() =
        runTest {
            val pages = FakePageRepository(listOf(page()), failWrites = true)
            val viewModel = createViewModel(pages = pages)
            viewModel.onRotateClockwise()

            // 保存が失敗した時点の保存先の内容を、画面の「保存済み」と表示中の値の両方へ反映し直す
            pages.pages = listOf(page(rotation = 180))
            viewModel.onSaveRequested()

            val uiState = viewModel.uiState.value
            assertEquals(PageEditMessage.SaveFailed, uiState.message)
            assertEquals(180, uiState.savedRotation)
            assertEquals(180, uiState.rotation)
            assertFalse(uiState.unsavedChanges)
        }

    @Test
    fun `保存した直後でも直前1操作を取り消せる`() =
        runTest {
            val pages = FakePageRepository(listOf(page()))
            val viewModel = createViewModel(pages = pages)

            viewModel.onRotateClockwise()
            viewModel.onCropHandleDragged(PageCropHandle.TOP, dx = 0f, dy = 0.2f)
            viewModel.onSaveRequested()
            assertEquals(90, pages.pages.single().rotation)
            // 回転・切り取りも取り消しの対象（docs/specs/08-page-editing.md §3.4）
            assertTrue(viewModel.uiState.value.canUndo)

            viewModel.onUndoRequested()

            assertEquals(1, pages.undoCalls)
            val uiState = viewModel.uiState.value
            // 保存先が戻り、画面の表示と「保存済み」もそれに揃う
            assertEquals(0, pages.pages.single().rotation)
            assertCrop(PageCrop(), pages.pages.single().crop)
            assertEquals(0, uiState.rotation)
            assertEquals(0, uiState.savedRotation)
            assertCrop(PageCrop(), uiState.crop)
            assertFalse(uiState.unsavedChanges)
            assertEquals(PageEditMessage.EditUndone, uiState.message)
            // 履歴の深さは1（同 §3.4 確定）
            assertFalse(uiState.canUndo)
        }

    @Test
    fun `保存済みの取り消しに失敗したらエラーを出す`() =
        runTest {
            val pages = FakePageRepository(listOf(page()), failUndo = true)
            val viewModel = createViewModel(pages = pages)

            viewModel.onRotateClockwise()
            viewModel.onSaveRequested()
            viewModel.onUndoRequested()

            val uiState = viewModel.uiState.value
            assertEquals(PageEditMessage.UndoFailed, uiState.message)
            // 取り消せなかったので保存済みの内容はそのまま
            assertEquals(90, pages.pages.single().rotation)
            assertEquals(90, uiState.rotation)
            assertEquals(90, uiState.savedRotation)
            assertFalse(uiState.saving)
            assertFalse(uiState.canUndo)
        }

    @Test
    fun `変更が無ければ保存できない`() =
        runTest {
            val viewModel = createViewModel()

            assertFalse(viewModel.uiState.value.canSave)
            viewModel.onSaveRequested()

            assertNull(viewModel.uiState.value.message)
        }

    @Test
    fun `未保存の変更があるときだけ破棄確認を出せる`() =
        runTest {
            val viewModel = createViewModel()

            viewModel.onRotateClockwise()
            assertTrue(viewModel.uiState.value.unsavedChanges)

            viewModel.onDiscardRequested()
            assertTrue(viewModel.uiState.value.discardConfirmationVisible)
            viewModel.onDiscardDismissed()
            assertFalse(viewModel.uiState.value.discardConfirmationVisible)
        }

    @Test
    fun `ページを読めないときはエラー表示になり再読み込みで復帰する`() =
        runTest {
            val pages = FakePageRepository(listOf(page()), failNextReads = 1)
            val viewModel = PageEditViewModel(pageId, pages)
            assertTrue(viewModel.uiState.value.loadFailed)

            viewModel.load()

            assertFalse(viewModel.uiState.value.loadFailed)
            assertEquals(12, viewModel.uiState.value.pageSequence)
        }

    private fun createViewModel(
        rotation: Int = 0,
        crop: PageCrop = PageCrop(),
        pages: FakePageRepository = FakePageRepository(listOf(page(rotation = rotation, crop = crop))),
    ) = PageEditViewModel(pageId = pageId, pageRepository = pages)

    private fun page(
        sequence: Int = 12,
        rotation: Int = 0,
        crop: PageCrop = PageCrop(),
    ) = Page(
        id = if (sequence == 12) pageId else UUID.fromString("50000000-0000-0000-0000-0000000000$sequence"),
        projectId = projectId,
        sequence = sequence,
        originalImagePath = "pages/$sequence.webp",
        width = 1080,
        height = 1920,
        rotation = rotation,
        crop = crop,
        capturedAt = Instant.parse("2026-08-26T00:00:00Z"),
        contentHash = "content-$sequence",
        perceptualHash = "perceptual-$sequence",
        qualityState = PageQualityState.NORMAL,
        ocrState = PageOcrState.SUCCEEDED,
    )

    private fun assertCrop(
        expected: PageCrop,
        actual: PageCrop,
    ) {
        assertEquals(expected.left, actual.left, TOLERANCE)
        assertEquals(expected.top, actual.top, TOLERANCE)
        assertEquals(expected.right, actual.right, TOLERANCE)
        assertEquals(expected.bottom, actual.bottom, TOLERANCE)
    }

    /**
     * 書き込みの中身をそのまま覗ける代役。
     * 元画像のファイルを触る口は [PageRepository] に無く、この代役にも無い（FR-IMG-007）。
     *
     * 保存は原子的な [updatePageEdit] だけを受け付ける（[updateRotation] / [updateCrop] を呼ぶと落ちる）。
     * 回転と切り取りが別々の書き込みへ割れていないことを、代役の側からも縛るため。
     */
    private class FakePageRepository(
        var pages: List<Page>,
        private val failWrites: Boolean = false,
        private var failNextReads: Int = 0,
        private val failUndo: Boolean = false,
    ) : PageRepository {
        val rotatedPages = mutableListOf<Pair<UUID, Int>>()
        val croppedPages = mutableListOf<Pair<UUID, PageCrop>>()

        /** 保存の呼び出し（ページID・回転・適用範囲）。1回の保存で必ず1件だけ増える */
        val editCalls = mutableListOf<Triple<UUID, Int, PageCropScope>>()

        /** [undoLastEdit] を呼ばれた回数 */
        var undoCalls = 0
            private set

        /** 直前1操作の1手前（深さ1。docs/specs/08-page-editing.md §3.4） */
        private var undoSnapshot: List<Page>? = null

        override suspend fun insert(page: Page) = throw UnsupportedOperationException()

        override suspend fun findById(id: UUID): Page? {
            failRead()
            return pages.firstOrNull { it.id == id }
        }

        override suspend fun findByProject(projectId: UUID): List<Page> {
            failRead()
            return pages.filter { it.projectId == projectId }
        }

        override fun observeByProject(projectId: UUID): Flow<List<Page>> = flow { emit(findByProject(projectId)) }

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

        /** 1回の呼び出しで全部書くか、何も書かないか（production の @Transaction と同じ約束） */
        override suspend fun updatePageEdit(
            pageId: UUID,
            rotation: Int,
            crop: PageCrop,
            cropScope: PageCropScope,
        ): Int {
            editCalls += Triple(pageId, rotation, cropScope)
            if (failWrites) throw IOException("write failed")
            undoSnapshot = pages
            require(rotation in setOf(0, 90, 180, 270)) { "Page rotation must be 0, 90, 180, or 270 degrees" }
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

        /** 直前1操作の前の内容へ戻す。戻せる操作が無ければ false（production の契約と同じ） */
        override suspend fun undoLastEdit(): Boolean {
            undoCalls++
            if (failUndo) throw IOException("undo failed")
            val snapshot = undoSnapshot ?: return false
            pages = snapshot
            undoSnapshot = null
            return true
        }

        private fun failRead() {
            if (failNextReads > 0) {
                failNextReads--
                throw IOException("read failed")
            }
        }
    }

    private companion object {
        /** 正規化座標の比較に使う許容誤差。Float の丸め分だけ見逃す */
        const val TOLERANCE = 1e-4f
    }
}
