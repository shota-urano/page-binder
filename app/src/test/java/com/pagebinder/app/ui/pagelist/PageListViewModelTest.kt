package com.pagebinder.app.ui.pagelist

import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.PageRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /** 一覧が読むのは findByProject だけ。書き込み系はこの画面から呼ばない */
    private class FakePageRepository(
        var pages: List<Page>,
        private var failNextReads: Int = 0,
    ) : PageRepository {
        override suspend fun insert(page: Page) = throw UnsupportedOperationException()

        override suspend fun findById(id: UUID): Page? = pages.firstOrNull { it.id == id }

        override suspend fun findByProject(projectId: UUID): List<Page> {
            if (failNextReads > 0) {
                failNextReads--
                throw IOException("read failed")
            }
            return pages.filter { it.projectId == projectId }
        }

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
    }
}
