package com.pagebinder.app.ui.duplicatereview

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/**
 * 重複候補比較・黒画面候補一覧の受け入れ基準を、利用者操作の側から確認する
 * （docs/specs/08-page-editing.md §3.2 FR-EDT-006・FR-EDT-007）。
 *
 * production の [DuplicateReviewScreen] と [DuplicateReviewViewModel] をそのまま組み合わせ、
 * 画面のタップが production の状態をどう動かすかだけを見る。
 */
@RunWith(AndroidJUnit4::class)
class DuplicateReviewScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val projectId = UUID.fromString("30000000-0000-0000-0000-000000000001")
    private lateinit var repository: FakePageRepository

    @Test
    fun `重複ペアから残すページを選ぶと他方が削除候補になる`() {
        val pages = samplePages()
        val viewModel = showScreen(pages)
        composeTestRule.onNodeWithTag(duplicateReviewKeepTestTag(2)).assertIsSelected()
        composeTestRule.onNodeWithTag(duplicateReviewKeepTestTag(3)).assertIsNotSelected()

        composeTestRule.onNodeWithTag(duplicateReviewKeepTestTag(3)).performClick()

        composeTestRule.onNodeWithTag(duplicateReviewKeepTestTag(3)).assertIsSelected()
        composeTestRule.onNodeWithTag(duplicateReviewKeepTestTag(2)).assertIsNotSelected()
        assertEquals(setOf(pages[1].id), viewModel.uiState.value.duplicateDeleteCandidatePageIds)
    }

    @Test
    fun `見出しは候補の件数を出し比較ペアと注記が並ぶ`() {
        showScreen(samplePages())

        composeTestRule
            .onNodeWithText(string(R.string.duplicate_review_duplicate_heading, 1))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.duplicate_review_black_heading, 2))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.duplicate_review_note)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(duplicateReviewCandidateTestTag(2)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(duplicateReviewCandidateTestTag(3)).assertIsDisplayed()
    }

    @Test
    fun `削除候補の削除は件数を出す確認を開くだけで削除しない`() {
        // docs/specs/08-page-editing.md §6: 削除確認で件数を必ず表示。確認なしの複数削除を行わない
        showScreen(samplePages())

        composeTestRule.onNodeWithTag(DUPLICATE_REVIEW_DELETE_BAR_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.duplicate_review_delete_candidates, 1)).performClick()

        composeTestRule
            .onNodeWithText(string(R.string.duplicate_review_delete_dialog_title))
            .assertIsDisplayed()
        composeTestRule
            .onNodeWithText(string(R.string.duplicate_review_delete_dialog_message, 1))
            .assertIsDisplayed()
        assertTrue(repository.deleteCalls.isEmpty())
    }

    @Test
    fun `確認で削除すると削除候補が消えて取り消しで戻る`() {
        val pages = samplePages()
        val viewModel = showScreen(pages)
        composeTestRule.onNodeWithText(string(R.string.duplicate_review_delete_candidates, 1)).performClick()

        composeTestRule.onNodeWithTag(DUPLICATE_REVIEW_DELETE_CONFIRM_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(setOf(pages[2].id)), repository.deleteCalls)
        composeTestRule.onNodeWithTag(DUPLICATE_REVIEW_MESSAGE_BAR_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(DUPLICATE_REVIEW_DELETE_BAR_TEST_TAG).assertDoesNotExist()

        composeTestRule.onNodeWithText(string(R.string.duplicate_review_undo_action)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, viewModel.uiState.value.duplicateGroupCount)
        composeTestRule.onNodeWithTag(DUPLICATE_REVIEW_MESSAGE_BAR_TEST_TAG).assertDoesNotExist()
    }

    @Test
    fun `黒画面候補は行ごとに削除と残すを選べる`() {
        val pages = samplePages()
        showScreen(pages)
        composeTestRule.onNodeWithTag(duplicateReviewBlackRowTestTag(4)).assertIsDisplayed()

        composeTestRule.onNodeWithTag(duplicateReviewBlackKeepTestTag(4)).performClick()

        // 「残す」はページに手を触れず、確認一覧から外すだけ
        composeTestRule.onNodeWithTag(duplicateReviewBlackRowTestTag(4)).assertDoesNotExist()
        composeTestRule.onNodeWithTag(duplicateReviewBlackRowTestTag(5)).assertIsDisplayed()
        assertTrue(repository.deleteCalls.isEmpty())
    }

    @Test
    fun `黒画面候補の削除も件数を出す確認を挟む`() {
        val pages = samplePages()
        showScreen(pages)

        composeTestRule.onNodeWithTag(duplicateReviewBlackDeleteTestTag(5)).performClick()

        composeTestRule
            .onNodeWithText(string(R.string.duplicate_review_delete_dialog_message, 1))
            .assertIsDisplayed()
        assertTrue(repository.deleteCalls.isEmpty())

        composeTestRule.onNodeWithTag(DUPLICATE_REVIEW_DELETE_CONFIRM_TEST_TAG).performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf(setOf(pages[4].id)), repository.deleteCalls)
    }

    @Test
    fun `黒画面候補の説明は回避の案内を含まない`() {
        // AGENTS.md ルール2・docs/design/09-duplicate-review.md「回避案内を書かない」。
        // 説明文はこの1文だけで、行ごとに同じ文言が付く
        showScreen(samplePages())

        listOf(4, 5).forEach { sequence ->
            composeTestRule
                .onNodeWithTag(duplicateReviewBlackRowTestTag(sequence))
                .assert(hasAnyDescendant(hasText(string(R.string.duplicate_review_black_reason))))
        }
    }

    @Test
    fun `確認するものが無ければ案内を出す`() {
        showScreen(listOf(page(1), page(2)))

        composeTestRule.onNodeWithText(string(R.string.duplicate_review_empty)).assertIsDisplayed()
        composeTestRule.onNodeWithTag(DUPLICATE_REVIEW_LIST_TEST_TAG).assertDoesNotExist()
        composeTestRule.onNodeWithTag(DUPLICATE_REVIEW_DELETE_BAR_TEST_TAG).assertDoesNotExist()
    }

    private fun showScreen(
        pages: List<Page>,
        thumbnailLoader: PageThumbnailLoader = PageThumbnailLoader { ImageBitmap(THUMBNAIL_PIXELS, THUMBNAIL_PIXELS) },
    ): DuplicateReviewViewModel {
        repository = FakePageRepository(pages)
        val viewModel = DuplicateReviewViewModel(projectId, repository)
        composeTestRule.setContent {
            PageBinderTheme {
                val uiState by viewModel.uiState.collectAsState()
                DuplicateReviewScreen(
                    uiState = uiState,
                    thumbnailLoader = thumbnailLoader,
                    actions = actionsOf(viewModel),
                )
            }
        }
        return viewModel
    }

    private fun actionsOf(viewModel: DuplicateReviewViewModel) =
        DuplicateReviewScreenActions(
            onBack = {},
            onKeepPageSelected = viewModel::onKeepPageSelected,
            onBlackPageDeleteRequested = viewModel::onBlackPageDeleteRequested,
            onBlackPageKept = viewModel::onBlackPageKept,
            onDuplicateDeleteRequested = viewModel::onDuplicateDeleteRequested,
            onDeleteConfirmed = viewModel::onDeleteConfirmed,
            onDeleteDismissed = viewModel::onDeleteDismissed,
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

    /** 画面から出る削除・取り消しをメモリ上で実際に反映する代役 */
    private class FakePageRepository(
        initialPages: List<Page>,
    ) : PageRepository {
        private var pages: List<Page> = initialPages
        private var undoSnapshot: List<Page>? = null

        val deleteCalls = mutableListOf<Set<UUID>>()

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
        ) = deleteResolvingDuplicates(projectId, pageIds, emptySet())

        /** production と同じく、削除・重複警告の解消・詰め直しをひとまとめの1操作として扱う */
        override suspend fun deleteResolvingDuplicates(
            projectId: UUID,
            pageIds: Set<UUID>,
            resolvedDuplicatePageIds: Set<UUID>,
        ) {
            deleteCalls += pageIds
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
            val snapshot = undoSnapshot ?: return false
            pages = snapshot
            undoSnapshot = null
            return true
        }
    }

    private companion object {
        const val THUMBNAIL_PIXELS = 16
    }
}
