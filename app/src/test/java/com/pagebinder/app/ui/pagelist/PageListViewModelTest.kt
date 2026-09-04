package com.pagebinder.app.ui.pagelist

import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageCropScope
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.PageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
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
 * ページ一覧の UiState（選択モード・警告フィルタ）を利用者操作の順に確認する
 * （docs/specs/08-page-editing.md §3.1・§9 の受け入れ基準）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageListViewModelTest {
    private val projectId = UUID.fromString("30000000-0000-0000-0000-000000000001")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `一覧はsequence昇順で並び初期はグリッド表示`() =
        runTest {
            val viewModel = createViewModel(pages = samplePages())

            val uiState = viewModel.uiState.value
            assertFalse(uiState.loading)
            assertEquals(listOf(1, 2, 3, 4, 5), uiState.pages.map(PageListItemUiState::sequence))
            assertEquals(PageListViewMode.GRID, uiState.viewMode)
            assertEquals(PageListFilter.ALL, uiState.filter)
        }

    @Test
    fun `表示切替はUiStateのviewModeだけを変える`() =
        runTest {
            val viewModel = createViewModel(pages = samplePages())

            viewModel.onViewModeChange(PageListViewMode.LIST)

            assertEquals(PageListViewMode.LIST, viewModel.uiState.value.viewMode)
            assertEquals(5, viewModel.uiState.value.visiblePages.size)
        }

    @Test
    fun `長押しで選択モードに入り件数が増える`() =
        runTest {
            val pages = samplePages()
            val viewModel = createViewModel(pages)
            assertFalse(viewModel.uiState.value.selectionMode)

            viewModel.onPageLongPressed(pages[0].id)
            viewModel.onSelectionToggled(pages[1].id)

            val uiState = viewModel.uiState.value
            assertTrue(uiState.selectionMode)
            assertEquals(2, uiState.selectedCount)
            assertTrue(uiState.isSelected(pages[0].id))
            assertTrue(uiState.isSelected(pages[1].id))
        }

    @Test
    fun `選択をすべて外すと選択モードが終わる`() =
        runTest {
            val pages = samplePages()
            val viewModel = createViewModel(pages)
            viewModel.onPageLongPressed(pages[0].id)

            viewModel.onSelectionToggled(pages[0].id)

            assertFalse(viewModel.uiState.value.selectionMode)
            assertEquals(0, viewModel.uiState.value.selectedCount)
        }

    @Test
    fun `選択解除で選択モードを抜ける`() =
        runTest {
            val pages = samplePages()
            val viewModel = createViewModel(pages)
            viewModel.onPageLongPressed(pages[0].id)
            viewModel.onSelectionToggled(pages[1].id)

            viewModel.onSelectionCleared()

            assertFalse(viewModel.uiState.value.selectionMode)
        }

    @Test
    fun `一覧に無いページは選択できない`() =
        runTest {
            val viewModel = createViewModel(pages = samplePages())

            viewModel.onPageLongPressed(UUID.fromString("40000000-0000-0000-0000-00000000ffff"))

            assertFalse(viewModel.uiState.value.selectionMode)
        }

    @Test
    fun `重複フィルタは重複候補だけを残す`() =
        runTest {
            val viewModel = createViewModel(pages = samplePages())

            viewModel.onFilterChange(PageListFilter.DUPLICATE)

            val visible = viewModel.uiState.value.visiblePages
            assertEquals(listOf(4), visible.map(PageListItemUiState::sequence))
            assertTrue(visible.all { it.qualityState == PageQualityState.DUPLICATE })
        }

    @Test
    fun `黒画面フィルタは黒画面候補だけを残す`() =
        runTest {
            val viewModel = createViewModel(pages = samplePages())

            viewModel.onFilterChange(PageListFilter.BLACK)

            assertEquals(listOf(5), viewModel.uiState.value.visiblePages.map(PageListItemUiState::sequence))
        }

    @Test
    fun `OCR未完了フィルタは完了以外を残す`() =
        runTest {
            val viewModel = createViewModel(pages = samplePages())

            viewModel.onFilterChange(PageListFilter.OCR_INCOMPLETE)

            val visible = viewModel.uiState.value.visiblePages
            assertEquals(listOf(2, 3), visible.map(PageListItemUiState::sequence))
            assertTrue(visible.none { it.ocrState == PageOcrState.SUCCEEDED })
        }

    @Test
    fun `フィルタを変えると選択は持ち越さない`() =
        runTest {
            val pages = samplePages()
            val viewModel = createViewModel(pages)
            viewModel.onPageLongPressed(pages[0].id)

            viewModel.onFilterChange(PageListFilter.DUPLICATE)

            assertFalse(viewModel.uiState.value.selectionMode)
            assertEquals(0, viewModel.uiState.value.selectedCount)
        }

    @Test
    fun `フィルタで0件になったことと空の書籍を区別する`() =
        runTest {
            val viewModel = createViewModel(pages = samplePages().take(1))

            viewModel.onFilterChange(PageListFilter.DUPLICATE)

            assertTrue(viewModel.uiState.value.emptyByFilter)
            assertFalse(viewModel.uiState.value.emptyProject)

            val emptyProjectViewModel = createViewModel(pages = emptyList())
            assertTrue(emptyProjectViewModel.uiState.value.emptyProject)
            assertFalse(emptyProjectViewModel.uiState.value.emptyByFilter)
        }

    @Test
    fun `読み込み失敗はエラー表示になり再読み込みで復帰する`() =
        runTest {
            val repository = FakePageRepository(samplePages(), failNextReads = 1)
            val viewModel = PageListViewModel(projectId, repository)
            assertTrue(viewModel.uiState.value.loadFailed)

            viewModel.load()

            assertFalse(viewModel.uiState.value.loadFailed)
            assertEquals(5, viewModel.uiState.value.pages.size)
        }

    @Test
    fun `撮影で増えたページが読み直しなしで一覧に入る`() =
        runTest {
            val repository = FakePageRepository(samplePages())
            val viewModel = PageListViewModel(projectId, repository)
            assertEquals(5, viewModel.uiState.value.pages.size)

            // 一覧を開いたまま撮影が1ページ保存した状況（pagebinder-3my）
            repository.insert(page(6))

            assertEquals(
                listOf(1, 2, 3, 4, 5, 6),
                viewModel.uiState.value.pages.map(PageListItemUiState::sequence),
            )
        }

    @Test
    fun `ドラッグ中に届いた購読の値で並びが巻き戻らない`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = PageListViewModel(projectId, repository)
            viewModel.onPageMoved(0, 4)
            val draggedOrder = viewModel.uiState.value.pages.map(PageListItemUiState::pageId)

            // 指を離す前に別経路の保存が流れてきても、指の下の並びは古い順序へ戻さない
            repository.insert(page(6))

            assertEquals(draggedOrder, viewModel.uiState.value.pages.map(PageListItemUiState::pageId))
        }

    @Test
    fun `500ページでも全件が一覧のUiStateに載る`() =
        runTest {
            val pages = (1..LARGE_PAGE_COUNT).map { sequence -> page(sequence) }

            val viewModel = createViewModel(pages.shuffled())

            val uiState = viewModel.uiState.value
            assertEquals(LARGE_PAGE_COUNT, uiState.visiblePages.size)
            assertEquals(
                (1..LARGE_PAGE_COUNT).toList(),
                uiState.visiblePages.map(PageListItemUiState::sequence),
            )
        }

    @Test
    fun `再読み込みで消えたページの選択は残らない`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = PageListViewModel(projectId, repository)
            viewModel.onPageLongPressed(pages[0].id)
            viewModel.onSelectionToggled(pages[1].id)

            repository.pages = pages.drop(1)
            viewModel.load()

            assertEquals(1, viewModel.uiState.value.selectedCount)
            assertTrue(viewModel.uiState.value.isSelected(pages[1].id))
        }

    // ---- 削除確認（docs/specs/08-page-editing.md §6・§9 の受け入れ基準「確認ダイアログに選択件数」） ----

    @Test
    fun `削除確認ダイアログに選択件数が表示される`() =
        runTest {
            val pages = samplePages()
            val viewModel = createViewModel(pages)
            viewModel.onPageLongPressed(pages[0].id)
            viewModel.onSelectionToggled(pages[2].id)
            viewModel.onSelectionToggled(pages[4].id)

            viewModel.onDeleteSelectedRequested()

            assertEquals(3, viewModel.uiState.value.deleteConfirmation?.pageCount)
            assertEquals(3, viewModel.uiState.value.selectedCount)
        }

    @Test
    fun `選択が無ければ削除確認は出ない`() =
        runTest {
            val viewModel = createViewModel(pages = samplePages())

            viewModel.onDeleteSelectedRequested()

            assertNull(viewModel.uiState.value.deleteConfirmation)
        }

    @Test
    fun `確認をキャンセルしても選択とページは変わらない`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = PageListViewModel(projectId, repository)
            viewModel.onPageLongPressed(pages[0].id)
            viewModel.onDeleteSelectedRequested()

            viewModel.onDeleteDismissed()

            assertNull(viewModel.uiState.value.deleteConfirmation)
            assertEquals(1, viewModel.uiState.value.selectedCount)
            assertEquals(5, viewModel.uiState.value.pages.size)
            assertTrue(repository.deleteCalls.isEmpty())
        }

    @Test
    fun `削除を確定すると選択したページが消えて取り消しを案内する`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = PageListViewModel(projectId, repository)
            viewModel.onPageLongPressed(pages[1].id)
            viewModel.onSelectionToggled(pages[3].id)
            viewModel.onDeleteSelectedRequested()

            viewModel.onDeleteConfirmed()

            val uiState = viewModel.uiState.value
            assertEquals(listOf(setOf(pages[1].id, pages[3].id)), repository.deleteCalls)
            assertNull(uiState.deleteConfirmation)
            assertFalse(uiState.selectionMode)
            assertEquals(listOf(1, 2, 3), uiState.pages.map(PageListItemUiState::sequence))
            assertEquals(PageListUndoableEdit.Delete(pageCount = 2), uiState.undoableEdit)
            assertNull(uiState.operationError)
        }

    @Test
    fun `削除に失敗しても一覧は消えずエラーを出す`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages, failDelete = true)
            val viewModel = PageListViewModel(projectId, repository)
            viewModel.onPageLongPressed(pages[0].id)
            viewModel.onDeleteSelectedRequested()

            viewModel.onDeleteConfirmed()

            val uiState = viewModel.uiState.value
            assertEquals(PageListOperationError.DELETE, uiState.operationError)
            assertEquals(5, uiState.pages.size)
            assertNull(uiState.undoableEdit)
            assertFalse(uiState.deleting)
        }

    // ---- ドラッグ並べ替え（docs/specs/08-page-editing.md §3.2 FR-EDT-002） ----

    @Test
    fun `ドラッグ中の入れ替えは連番を振り直し指を離すと永続化する`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = PageListViewModel(projectId, repository)

            viewModel.onPageMoved(fromIndex = 0, toIndex = 2)

            val duringDrag = viewModel.uiState.value
            assertEquals(
                listOf(pages[1].id, pages[2].id, pages[0].id, pages[3].id, pages[4].id),
                duringDrag.pages.map(PageListItemUiState::pageId),
            )
            assertEquals(listOf(1, 2, 3, 4, 5), duringDrag.pages.map(PageListItemUiState::sequence))
            assertTrue(repository.reorderCalls.isEmpty())

            viewModel.onReorderFinished()

            assertEquals(
                listOf(listOf(pages[1].id, pages[2].id, pages[0].id, pages[3].id, pages[4].id)),
                repository.reorderCalls,
            )
            val afterDrop = viewModel.uiState.value
            assertEquals(
                listOf(pages[1].id, pages[2].id, pages[0].id, pages[3].id, pages[4].id),
                afterDrop.pages.map(PageListItemUiState::pageId),
            )
            assertEquals(PageListUndoableEdit.Reorder, afterDrop.undoableEdit)
        }

    @Test
    fun `動かしていなければ指を離しても保存しない`() =
        runTest {
            val repository = FakePageRepository(samplePages())
            val viewModel = PageListViewModel(projectId, repository)

            viewModel.onReorderFinished()

            assertTrue(repository.reorderCalls.isEmpty())
            assertNull(viewModel.uiState.value.undoableEdit)
        }

    @Test
    fun `並べ替えに失敗すると保存済みの順序へ戻してエラーを出す`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages, failReorder = true)
            val viewModel = PageListViewModel(projectId, repository)
            viewModel.onPageMoved(fromIndex = 0, toIndex = 4)

            viewModel.onReorderFinished()

            val uiState = viewModel.uiState.value
            assertEquals(PageListOperationError.REORDER, uiState.operationError)
            assertEquals(pages.map(Page::id), uiState.pages.map(PageListItemUiState::pageId))
            assertNull(uiState.undoableEdit)
        }

    @Test
    fun `絞り込み中と選択中は並べ替えを受け付けない`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = PageListViewModel(projectId, repository)

            viewModel.onFilterChange(PageListFilter.OCR_INCOMPLETE)
            assertFalse(viewModel.uiState.value.reorderEnabled)
            viewModel.onPageMoved(fromIndex = 0, toIndex = 1)
            viewModel.onReorderFinished()

            viewModel.onFilterChange(PageListFilter.ALL)
            viewModel.onPageLongPressed(pages[0].id)
            assertFalse(viewModel.uiState.value.reorderEnabled)
            viewModel.onPageMoved(fromIndex = 0, toIndex = 1)
            viewModel.onReorderFinished()

            assertTrue(repository.reorderCalls.isEmpty())
            assertEquals(pages.map(Page::id), viewModel.uiState.value.pages.map(PageListItemUiState::pageId))
        }

    // ---- 取り消し（docs/specs/08-page-editing.md §3.4。直前1操作） ----

    @Test
    fun `削除の取り消しでページが戻り案内が消える`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = PageListViewModel(projectId, repository)
            viewModel.onPageLongPressed(pages[0].id)
            viewModel.onDeleteSelectedRequested()
            viewModel.onDeleteConfirmed()
            assertEquals(4, viewModel.uiState.value.pages.size)

            viewModel.onUndoRequested()

            val uiState = viewModel.uiState.value
            assertEquals(1, repository.undoCalls)
            assertEquals(pages.map(Page::id), uiState.pages.map(PageListItemUiState::pageId))
            assertNull(uiState.undoableEdit)
            assertNull(uiState.operationError)
        }

    @Test
    fun `取り消せる操作が無ければ取り消しを呼ばない`() =
        runTest {
            val repository = FakePageRepository(samplePages())
            val viewModel = PageListViewModel(projectId, repository)

            viewModel.onUndoRequested()

            assertEquals(0, repository.undoCalls)
        }

    @Test
    fun `取り消しに失敗するとエラーを出す`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages, failUndo = true)
            val viewModel = PageListViewModel(projectId, repository)
            viewModel.onPageLongPressed(pages[0].id)
            viewModel.onDeleteSelectedRequested()
            viewModel.onDeleteConfirmed()

            viewModel.onUndoRequested()

            assertEquals(PageListOperationError.UNDO, viewModel.uiState.value.operationError)
            assertNull(viewModel.uiState.value.undoableEdit)
        }

    @Test
    fun `案内を閉じると取り消しもエラーも消える`() =
        runTest {
            val pages = samplePages()
            val viewModel = createViewModel(pages)
            viewModel.onPageLongPressed(pages[0].id)
            viewModel.onDeleteSelectedRequested()
            viewModel.onDeleteConfirmed()

            viewModel.onMessageDismissed()

            assertNull(viewModel.uiState.value.undoableEdit)
            assertNull(viewModel.uiState.value.operationError)
        }

    private fun createViewModel(pages: List<Page>) = PageListViewModel(projectId, FakePageRepository(pages))

    /** 各状態が1件ずつ出るようにしたサンプル。1=完了/2=待機/3=失敗/4=重複/5=黒画面 */
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

    /**
     * 一覧が使うのは読み出しと編集系（並べ替え・削除・取り消し）だけ。
     * 実際に並びを書き換えるので、操作後の読み直しが何を返すかまで確かめられる。
     */
    private class FakePageRepository(
        pages: List<Page>,
        private var failNextReads: Int = 0,
        private val failReorder: Boolean = false,
        private val failDelete: Boolean = false,
        private val failUndo: Boolean = false,
    ) : PageRepository {
        val reorderCalls = mutableListOf<List<UUID>>()
        val deleteCalls = mutableListOf<Set<UUID>>()
        var undoCalls = 0
            private set

        /** 保存の結果を購読側へ流すための現在値。Room の購読クエリと同じ振る舞いにする */
        private val storedPages = MutableStateFlow(pages)

        var pages: List<Page>
            get() = storedPages.value
            set(value) {
                storedPages.value = value
            }

        private var undoSnapshot: List<Page>? = null

        override suspend fun insert(page: Page) {
            storedPages.value = storedPages.value + page
        }

        override suspend fun findById(id: UUID): Page? = pages.firstOrNull { it.id == id }

        override suspend fun findByProject(projectId: UUID): List<Page> {
            if (failNextReads > 0) {
                failNextReads--
                throw IOException("read failed")
            }
            return pages.filter { it.projectId == projectId }
        }

        override fun observeByProject(projectId: UUID): Flow<List<Page>> =
            storedPages.map { current ->
                if (failNextReads > 0) {
                    failNextReads--
                    throw IOException("read failed")
                }
                current.filter { it.projectId == projectId }
            }

        override suspend fun reorder(
            projectId: UUID,
            orderedPageIds: List<UUID>,
        ) {
            reorderCalls += orderedPageIds
            if (failReorder) throw IOException("reorder failed")
            val byId = pages.associateBy(Page::id)
            undoSnapshot = pages
            pages = orderedPageIds.mapIndexedNotNull { index, id -> byId[id]?.copy(sequence = index + 1) }
        }

        override suspend fun delete(
            projectId: UUID,
            pageIds: Set<UUID>,
        ) {
            deleteCalls += pageIds
            if (failDelete) throw IOException("delete failed")
            undoSnapshot = pages
            pages =
                pages
                    .filterNot { it.id in pageIds }
                    .mapIndexed { index, page -> page.copy(sequence = index + 1) }
        }

        /** この画面は重複の解消を行わないので、呼ばれたら誤り */
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

        override suspend fun undoLastEdit(): Boolean {
            undoCalls++
            if (failUndo) throw IOException("undo failed")
            val snapshot = undoSnapshot ?: return false
            pages = snapshot
            undoSnapshot = null
            return true
        }
    }

    private companion object {
        /** requirements §16.1 の最低基準 */
        const val LARGE_PAGE_COUNT = 500
    }
}
