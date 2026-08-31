package com.pagebinder.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.R
import com.pagebinder.app.ui.bookdetail.BookDetailActions
import com.pagebinder.app.ui.bookdetail.BookDetailScreen
import com.pagebinder.app.ui.bookdetail.BookDetailUiState
import com.pagebinder.app.ui.theme.PageBinderTheme
import com.pagebinder.app.ui.trash.PermanentDeleteConfirmationUiState
import com.pagebinder.app.ui.trash.TrashScreen
import com.pagebinder.app.ui.trash.TrashUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookProjectScreensTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun detailExposesAllSixActions() {
        val calls = mutableListOf<String>()
        composeTestRule.setContent {
            PageBinderTheme {
                BookDetailScreen(
                    uiState = BookDetailUiState(loading = false, title = "実データ"),
                    actions =
                        actions(
                            manual = { calls += "manual" },
                            continuous = { calls += "continuous" },
                            pages = { calls += "pages" },
                            ocr = { calls += "ocr" },
                            export = { calls += "export" },
                            settings = { calls += "settings" },
                        ),
                )
            }
        }

        listOf(
            R.string.book_detail_manual_capture,
            R.string.book_detail_continuous_capture,
            R.string.book_detail_pages,
            R.string.book_detail_ocr_batch,
            R.string.book_detail_export,
            R.string.book_detail_settings,
        ).forEach { label ->
            composeTestRule.onNodeWithText(string(label)).performScrollTo().performClick()
        }

        assertEquals(listOf("manual", "continuous", "pages", "ocr", "export", "settings"), calls)
    }

    @Test
    fun trashShowsRetentionAndCompleteDeletionTarget() {
        val id = UUID.fromString("10000000-0000-0000-0000-000000000001")
        composeTestRule.setContent {
            PageBinderTheme {
                TrashScreen(
                    uiState =
                        TrashUiState(
                            loading = false,
                            deleteConfirmation =
                                PermanentDeleteConfirmationUiState(
                                    id = id,
                                    title = "削除対象",
                                    pageCount = 12,
                                    storageBytes = 28L * 1_048_576,
                                ),
                        ),
                    onBack = {},
                    onRestore = {},
                    onDeleteRequested = {},
                    onDeleteConfirmed = {},
                    onDeleteDismissed = {},
                    onReload = {},
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.trash_retention)).assertIsDisplayed()
        composeTestRule
            .onNodeWithText(
                string(
                    R.string.trash_delete_dialog_message,
                    "削除対象",
                    12,
                    formatStorageBytes(28L * 1_048_576),
                ),
            ).assertIsDisplayed()
    }

    private fun actions(
        manual: () -> Unit,
        continuous: () -> Unit,
        pages: () -> Unit,
        ocr: () -> Unit,
        export: () -> Unit,
        settings: () -> Unit,
    ) = BookDetailActions(
        onBack = {},
        onEdit = {},
        onManualCapture = manual,
        onContinuousCapture = continuous,
        onPageList = pages,
        onOcrBatch = ocr,
        onExport = export,
        onBookSettings = settings,
        onMoveToTrashRequested = {},
        onMoveToTrashConfirmed = {},
        onMoveToTrashDismissed = {},
        onReload = {},
    )

    private fun string(
        resId: Int,
        vararg args: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resId, *args)
}
