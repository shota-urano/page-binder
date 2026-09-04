package com.pagebinder.app.ui.duplicatereview

import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import com.pagebinder.app.domain.PageCropScope
import com.pagebinder.app.domain.PageOcrState
import com.pagebinder.app.domain.PageQualityState
import com.pagebinder.app.domain.PageRepository
import com.pagebinder.app.ui.pagelist.PageListItemUiState
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
 * 重複候補比較・黒画面候補一覧の UiState を利用者操作の順に確認する
 * （docs/specs/08-page-editing.md §3.2 FR-EDT-006・FR-EDT-007、§9 の受け入れ基準
 * 「重複ペアから残すページを選ぶと他方が削除候補になる」）。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DuplicateReviewViewModelTest {
    private val projectId = UUID.fromString("30000000-0000-0000-0000-000000000001")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ---- 重複ペア（FR-EDT-006） ----

    @Test
    fun `重複の印が付いたページは直前のページとひと組になる`() =
        runTest {
            val viewModel = createViewModel(samplePages())

            val uiState = viewModel.uiState.value
            assertFalse(uiState.loading)
            assertEquals(1, uiState.duplicateGroupCount)
            assertEquals(
                listOf(2, 3),
                uiState.duplicateGroups
                    .first()
                    .pages
                    .map(PageListItemUiState::sequence),
            )
        }

    @Test
    fun `既定では重複の印が付いていない先頭ページを残す`() =
        runTest {
            val pages = samplePages()
            val viewModel = createViewModel(pages)

            val group = viewModel.uiState.value.duplicateGroups.first()
            assertEquals(pages[1].id, group.keptPageId)
            assertEquals(setOf(pages[2].id), group.deleteCandidatePageIds)
        }

    @Test
    fun `重複ペアから残すページを選ぶと他方が削除候補になる`() =
        runTest {
            // 受け入れ基準（docs/specs/08-page-editing.md §9）そのもの
            val pages = samplePages()
            val viewModel = createViewModel(pages)
            val group = viewModel.uiState.value.duplicateGroups.first()

            viewModel.onKeepPageSelected(groupId = group.groupId, pageId = pages[2].id)

            val kept = viewModel.uiState.value.duplicateGroups.first()
            assertEquals(pages[2].id, kept.keptPageId)
            assertTrue(kept.isKept(pages[2].id))
            assertFalse(kept.isKept(pages[1].id))
            assertEquals(setOf(pages[1].id), kept.deleteCandidatePageIds)
            assertEquals(setOf(pages[1].id), viewModel.uiState.value.duplicateDeleteCandidatePageIds)
        }

    @Test
    fun `残す選択は排他で組ごとに1件だけ`() =
        runTest {
            val pages = samplePages()
            val viewModel = createViewModel(pages)
            val group = viewModel.uiState.value.duplicateGroups.first()

            viewModel.onKeepPageSelected(group.groupId, pages[2].id)
            viewModel.onKeepPageSelected(group.groupId, pages[1].id)

            val kept = viewModel.uiState.value.duplicateGroups.first()
            assertEquals(pages[1].id, kept.keptPageId)
            assertEquals(setOf(pages[2].id), kept.deleteCandidatePageIds)
        }

    @Test
    fun `組に属さないページは残す対象にできない`() =
        runTest {
            val pages = samplePages()
            val viewModel = createViewModel(pages)
            val group = viewModel.uiState.value.duplicateGroups.first()

            viewModel.onKeepPageSelected(group.groupId, pages[0].id)

            assertEquals(pages[1].id, viewModel.uiState.value.duplicateGroups.first().keptPageId)
        }

    @Test
    fun `連続した重複は同じ組にまとまり残す1件以外が削除候補になる`() =
        runTest {
            // 3枚以上の組は素材に無い（docs/design/09-duplicate-review.md「未定事項」）が、
            // 検出は直前ページとの比較なので連続すると1組にまとまる
            val pages =
                listOf(
                    page(1),
                    page(2, qualityState = PageQualityState.DUPLICATE),
                    page(3, qualityState = PageQualityState.DUPLICATE),
                )
            val viewModel = createViewModel(pages)

            val group = viewModel.uiState.value.duplicateGroups.single()
            assertEquals(listOf(1, 2, 3), group.pages.map(PageListItemUiState::sequence))
            assertEquals(setOf(pages[1].id, pages[2].id), group.deleteCandidatePageIds)
        }

    @Test
    fun `比較相手がいない先頭ページの重複は組にしない`() =
        runTest {
            val viewModel = createViewModel(listOf(page(1, qualityState = PageQualityState.DUPLICATE), page(2)))

            assertTrue(viewModel.uiState.value.duplicateGroups.isEmpty())
        }

    // ---- 黒画面候補（FR-EDT-007） ----

    @Test
    fun `黒画面の印が付いたページだけが候補一覧に出る`() =
        runTest {
            val viewModel = createViewModel(samplePages())

            val uiState = viewModel.uiState.value
            assertEquals(2, uiState.blackCandidateCount)
            assertEquals(listOf(4, 5), uiState.blackCandidates.map(PageListItemUiState::sequence))
        }

    @Test
    fun `黒画面候補を残すと確認一覧から外れページは消えない`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = DuplicateReviewViewModel(projectId, repository)

            viewModel.onBlackPageKept(pages[3].id)

            assertEquals(listOf(5), viewModel.uiState.value.blackCandidates.map(PageListItemUiState::sequence))
            assertTrue(repository.deleteCalls.isEmpty())
        }

    @Test
    fun `残した黒画面候補は読み直しても戻らない`() =
        runTest {
            val pages = samplePages()
            val viewModel = createViewModel(pages)
            viewModel.onBlackPageKept(pages[3].id)

            viewModel.load()

            assertEquals(listOf(5), viewModel.uiState.value.blackCandidates.map(PageListItemUiState::sequence))
        }

    // ---- 削除確認（docs/specs/08-page-editing.md §6「削除確認で件数を必ず表示」） ----

    @Test
    fun `黒画面候補の削除は件数を出す確認を開くだけで削除しない`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = DuplicateReviewViewModel(projectId, repository)

            viewModel.onBlackPageDeleteRequested(pages[3].id)

            assertEquals(1, viewModel.uiState.value.deleteConfirmation?.pageCount)
            assertEquals(setOf(pages[3].id), viewModel.uiState.value.deleteConfirmation?.pageIds)
            assertTrue(repository.deleteCalls.isEmpty())
        }

    @Test
    fun `重複の削除候補はまとめて確認へ回る`() =
        runTest {
            val pages =
                listOf(
                    page(1),
                    page(2, qualityState = PageQualityState.DUPLICATE),
                    page(3),
                    page(4, qualityState = PageQualityState.DUPLICATE),
                )
            val repository = FakePageRepository(pages)
            val viewModel = DuplicateReviewViewModel(projectId, repository)

            viewModel.onDuplicateDeleteRequested()

            assertEquals(2, viewModel.uiState.value.deleteConfirmation?.pageCount)
            assertEquals(
                setOf(pages[1].id, pages[3].id),
                viewModel.uiState.value.deleteConfirmation?.pageIds,
            )
            assertTrue(repository.deleteCalls.isEmpty())
        }

    @Test
    fun `削除候補が無ければ確認は出ない`() =
        runTest {
            val viewModel = createViewModel(listOf(page(1), page(2, qualityState = PageQualityState.BLACK)))

            viewModel.onDuplicateDeleteRequested()

            assertNull(viewModel.uiState.value.deleteConfirmation)
        }

    @Test
    fun `確認をキャンセルすると候補も選択も変わらない`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = DuplicateReviewViewModel(projectId, repository)
            viewModel.onDuplicateDeleteRequested()

            viewModel.onDeleteDismissed()

            assertNull(viewModel.uiState.value.deleteConfirmation)
            assertEquals(1, viewModel.uiState.value.duplicateGroupCount)
            assertTrue(repository.deleteCalls.isEmpty())
        }

    @Test
    fun `削除を確定すると削除候補が消えて取り消しを案内する`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = DuplicateReviewViewModel(projectId, repository)
            viewModel.onDuplicateDeleteRequested()

            viewModel.onDeleteConfirmed()

            val uiState = viewModel.uiState.value
            assertEquals(listOf(setOf(pages[2].id)), repository.deleteCalls)
            assertNull(uiState.deleteConfirmation)
            assertTrue(uiState.duplicateGroups.isEmpty())
            assertEquals(DuplicateReviewUndoableDelete(pageCount = 1), uiState.undoableDelete)
            assertNull(uiState.operationError)
            assertFalse(uiState.deleting)
        }

    @Test
    fun `黒画面候補の削除を確定するとその1件だけが消える`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = DuplicateReviewViewModel(projectId, repository)
            viewModel.onBlackPageDeleteRequested(pages[3].id)

            viewModel.onDeleteConfirmed()

            assertEquals(listOf(setOf(pages[3].id)), repository.deleteCalls)
            assertEquals(1, viewModel.uiState.value.blackCandidateCount)
            assertEquals(DuplicateReviewUndoableDelete(pageCount = 1), viewModel.uiState.value.undoableDelete)
        }

    @Test
    fun `重複側を残して他方を削除しても残したページは削除候補に戻らない`() =
        runTest {
            // 回帰: 残したページの重複警告を消さないと、詰め直しで隣に来たページと新しい組ができ、
            // 利用者が残すと選んだページがそのまま削除候補として再登場する
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = DuplicateReviewViewModel(projectId, repository)
            val group = viewModel.uiState.value.duplicateGroups.first()
            viewModel.onKeepPageSelected(group.groupId, pages[2].id)
            viewModel.onDuplicateDeleteRequested()

            viewModel.onDeleteConfirmed()

            // 削除したのは残さなかった側だけ。残す側は重複の解消対象として一緒に渡る
            assertEquals(listOf(setOf(pages[1].id)), repository.deleteCalls)
            assertEquals(listOf(setOf(pages[2].id)), repository.resolveCalls)
            val uiState = viewModel.uiState.value
            assertTrue(uiState.duplicateGroups.isEmpty())
            assertTrue(uiState.duplicateDeleteCandidatePageIds.isEmpty())
            assertEquals(
                PageQualityState.NORMAL,
                repository.findById(pages[2].id)?.qualityState,
            )
        }

    @Test
    fun `重複側を残した削除を取り消すと元の組と選択が戻る`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = DuplicateReviewViewModel(projectId, repository)
            val group = viewModel.uiState.value.duplicateGroups.first()
            viewModel.onKeepPageSelected(group.groupId, pages[2].id)
            viewModel.onDuplicateDeleteRequested()
            viewModel.onDeleteConfirmed()

            viewModel.onUndoRequested()

            val restored = viewModel.uiState.value.duplicateGroups.single()
            assertEquals(listOf(pages[1].id, pages[2].id), restored.pages.map(PageListItemUiState::pageId))
            // 重複警告も選択も削除前へ戻る（直前1操作の取り消し）
            assertEquals(pages[2].id, restored.keptPageId)
            assertEquals(setOf(pages[1].id), restored.deleteCandidatePageIds)
            assertEquals(
                PageQualityState.DUPLICATE,
                repository.findById(pages[2].id)?.qualityState,
            )
        }

    @Test
    fun `既定どおり先頭を残す削除では解消するページが無い`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = DuplicateReviewViewModel(projectId, repository)

            viewModel.onDuplicateDeleteRequested()
            viewModel.onDeleteConfirmed()

            // 残したページには重複警告が付いていないので、消すものは無い
            assertEquals(listOf(setOf(pages[2].id)), repository.deleteCalls)
            assertEquals(listOf(setOf(pages[1].id)), repository.resolveCalls)
            assertEquals(PageQualityState.NORMAL, repository.findById(pages[1].id)?.qualityState)
        }

    @Test
    fun `黒画面候補の削除では重複の解消を伴わない`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = DuplicateReviewViewModel(projectId, repository)
            viewModel.onBlackPageDeleteRequested(pages[3].id)

            viewModel.onDeleteConfirmed()

            assertEquals(listOf(emptySet<UUID>()), repository.resolveCalls)
            assertEquals(1, viewModel.uiState.value.duplicateGroupCount)
        }

    @Test
    fun `削除に失敗しても候補は消えずエラーを出す`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages, failDelete = true)
            val viewModel = DuplicateReviewViewModel(projectId, repository)
            viewModel.onDuplicateDeleteRequested()

            viewModel.onDeleteConfirmed()

            val uiState = viewModel.uiState.value
            assertEquals(DuplicateReviewOperationError.DELETE, uiState.operationError)
            assertEquals(1, uiState.duplicateGroupCount)
            assertNull(uiState.undoableDelete)
            assertFalse(uiState.deleting)
        }

    // ---- 取り消し（docs/specs/08-page-editing.md §3.4。直前1操作） ----

    @Test
    fun `削除の取り消しで候補が戻り案内が消える`() =
        runTest {
            val pages = samplePages()
            val repository = FakePageRepository(pages)
            val viewModel = DuplicateReviewViewModel(projectId, repository)
            viewModel.onDuplicateDeleteRequested()
            viewModel.onDeleteConfirmed()
            assertTrue(viewModel.uiState.value.duplicateGroups.isEmpty())

            viewModel.onUndoRequested()

            val uiState = viewModel.uiState.value
            assertEquals(1, repository.undoCalls)
            assertEquals(1, uiState.duplicateGroupCount)
            assertNull(uiState.undoableDelete)
            assertNull(uiState.operationError)
        }

    @Test
    fun `取り消せる操作が無ければ取り消しを呼ばない`() =
        runTest {
            val repository = FakePageRepository(samplePages())
            val viewModel = DuplicateReviewViewModel(projectId, repository)

            viewModel.onUndoRequested()

            assertEquals(0, repository.undoCalls)
        }

    @Test
    fun `取り消しに失敗するとエラーを出す`() =
        runTest {
            val repository = FakePageRepository(samplePages(), failUndo = true)
            val viewModel = DuplicateReviewViewModel(projectId, repository)
            viewModel.onDuplicateDeleteRequested()
            viewModel.onDeleteConfirmed()

            viewModel.onUndoRequested()

            assertEquals(DuplicateReviewOperationError.UNDO, viewModel.uiState.value.operationError)
            assertNull(viewModel.uiState.value.undoableDelete)
        }

    @Test
    fun `案内を閉じると取り消しもエラーも消える`() =
        runTest {
            val viewModel = createViewModel(samplePages())
            viewModel.onDuplicateDeleteRequested()
            viewModel.onDeleteConfirmed()

            viewModel.onMessageDismissed()

            assertNull(viewModel.uiState.value.undoableDelete)
            assertNull(viewModel.uiState.value.operationError)
        }

    // ---- 読み込み ----

    @Test
    fun `候補が1件も無ければ空として扱う`() =
        runTest {
            val viewModel = createViewModel(listOf(page(1), page(2)))

            val uiState = viewModel.uiState.value
            assertTrue(uiState.empty)
            assertFalse(uiState.hasCandidates)
        }

    @Test
    fun `読み込み失敗はエラー表示になり再読み込みで復帰する`() =
        runTest {
            val repository = FakePageRepository(samplePages(), failNextReads = 1)
            val viewModel = DuplicateReviewViewModel(projectId, repository)
            assertTrue(viewModel.uiState.value.loadFailed)
            assertFalse(viewModel.uiState.value.empty)

            viewModel.load()

            assertFalse(viewModel.uiState.value.loadFailed)
            assertEquals(1, viewModel.uiState.value.duplicateGroupCount)
        }

    @Test
    fun `残す選択は読み直しても引き継がれる`() =
        runTest {
            val pages = samplePages()
            val viewModel = createViewModel(pages)
            val group = viewModel.uiState.value.duplicateGroups.first()
            viewModel.onKeepPageSelected(group.groupId, pages[2].id)

            viewModel.load()

            assertEquals(pages[2].id, viewModel.uiState.value.duplicateGroups.first().keptPageId)
        }

    private fun createViewModel(pages: List<Page>) = DuplicateReviewViewModel(projectId, FakePageRepository(pages))

    /** 1=通常/2=通常（比較相手）/3=重複/4=黒画面/5=黒画面 */
    private fun samplePages(): List<Page> =
        listOf(
            page(1),
            page(2),
            page(3, qualityState = PageQualityState.DUPLICATE),
            page(4, qualityState = PageQualityState.BLACK),
            page(5, qualityState = PageQualityState.BLACK),
        )

    private fun page(
        sequence: Int,
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
            ocrState = PageOcrState.SUCCEEDED,
        )

    /** この画面が使うのは読み出しと削除・取り消しだけ。削除は実際に反映して読み直しの結果まで見る */
    private class FakePageRepository(
        private var pages: List<Page>,
        private var failNextReads: Int = 0,
        private val failDelete: Boolean = false,
        private val failUndo: Boolean = false,
    ) : PageRepository {
        val deleteCalls = mutableListOf<Set<UUID>>()

        /** 削除と同時に重複を解消したページ。呼び出しごとに1件記録する */
        val resolveCalls = mutableListOf<Set<UUID>>()
        var undoCalls = 0
            private set

        private var undoSnapshot: List<Page>? = null

        override suspend fun insert(page: Page) = throw UnsupportedOperationException()

        override suspend fun findById(id: UUID): Page? = pages.firstOrNull { it.id == id }

        override suspend fun findByProject(projectId: UUID): List<Page> {
            if (failNextReads > 0) {
                failNextReads--
                throw IOException("read failed")
            }
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
        ) = deleteResolvingDuplicates(projectId, pageIds, emptySet())

        /**
         * production と同じく、削除・重複警告の解消・連番の詰め直しをひとまとめに行い、
         * 取り消しでは3つとも1操作として戻す（[com.pagebinder.app.data.RoomPageRepository] の
         * 約束を代役でも写す。写さないと、解消が効いているかを代役の都合で見誤る）。
         */
        override suspend fun deleteResolvingDuplicates(
            projectId: UUID,
            pageIds: Set<UUID>,
            resolvedDuplicatePageIds: Set<UUID>,
        ) {
            deleteCalls += pageIds
            resolveCalls += resolvedDuplicatePageIds
            if (failDelete) throw IOException("delete failed")
            undoSnapshot = pages
            pages =
                pages
                    .filterNot { it.id in pageIds }
                    .map { page ->
                        if (page.id in resolvedDuplicatePageIds &&
                            page.qualityState == PageQualityState.DUPLICATE
                        ) {
                            page.copy(qualityState = PageQualityState.NORMAL)
                        } else {
                            page
                        }
                    }.mapIndexed { index, page -> page.copy(sequence = index + 1) }
        }

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
}
