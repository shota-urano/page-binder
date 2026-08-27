package com.pagebinder.app.ui.export

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pagebinder.app.R
import com.pagebinder.app.domain.ExportOptions
import com.pagebinder.app.domain.ExportProgressEvent
import com.pagebinder.app.domain.ExportProjectSummary
import com.pagebinder.app.domain.ExportStarter
import com.pagebinder.app.ui.theme.PageBinderTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 書き出し画面の受け入れ基準を、利用者操作の側から確認する（docs/specs/11-export.md §3.2）。
 *
 * production の [ExportScreen] と [ExportViewModel] をそのまま組み合わせ、
 * 画面のタップが production の状態をどう動かすかだけを見る。SAF の起動は [ExportRoute] の役目なので、
 * ここでは「起動要求が出たか」までを検証する（システムの保存画面はテストで開かない）。
 */
@RunWith(AndroidJUnit4::class)
class ExportScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val starter = NeverStartingExportStarter()

    @Test
    fun `OCR未完了ページがあると件数付きの警告が出る`() {
        showScreen(ocrIncompletePageCount = 3)

        composeTestRule.onNodeWithText(string(R.string.export_ocr_warning, 3)).assertIsDisplayed()
    }

    @Test
    fun `権限確認にチェックせずに書き出すと案内が出て保存先選択へ進まない`() {
        val viewModel = showScreen(ocrIncompletePageCount = 0)

        composeTestRule.onNodeWithText(string(R.string.export_start)).performClick()

        composeTestRule.onNodeWithText(string(R.string.export_consent_required)).assertIsDisplayed()
        assertNull(viewModel.uiState.value.safRequest)
        assertEquals(0, starter.startCount)
    }

    @Test
    fun `OCR未完了のまま書き出すと続行と中止の選択が出る`() {
        val viewModel = showScreen(ocrIncompletePageCount = 3)
        confirmPermission()

        composeTestRule.onNodeWithText(string(R.string.export_start)).performClick()

        composeTestRule
            .onNodeWithText(string(R.string.export_ocr_warning_dialog_title))
            .assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.export_ocr_warning_continue)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.export_ocr_warning_abort)).assertIsDisplayed()
        assertNull("続行を選ぶまで保存先選択へ進まないこと", viewModel.uiState.value.safRequest)
        assertEquals(0, starter.startCount)
    }

    @Test
    fun `中止を選ぶと書き出しへ進まない`() {
        val viewModel = showScreen(ocrIncompletePageCount = 3)
        confirmPermission()
        composeTestRule.onNodeWithText(string(R.string.export_start)).performClick()

        composeTestRule.onNodeWithText(string(R.string.export_ocr_warning_abort)).performClick()

        assertFalse(viewModel.uiState.value.ocrWarningAcknowledged)
        assertNull(viewModel.uiState.value.safRequest)
        assertEquals(0, starter.startCount)
    }

    @Test
    fun `続行を選ぶと保存先選択の起動要求が出る`() {
        val viewModel = showScreen(ocrIncompletePageCount = 3)
        confirmPermission()
        composeTestRule.onNodeWithText(string(R.string.export_start)).performClick()

        composeTestRule.onNodeWithText(string(R.string.export_ocr_warning_continue)).performClick()

        assertNotNull(viewModel.uiState.value.safRequest)
        assertEquals("保存先が決まるまで書き出しは始まらないこと", 0, starter.startCount)
    }

    @Test
    fun `出力形式を切り替えるとPDF画質の行が消える`() {
        showScreen(ocrIncompletePageCount = 0)
        composeTestRule.onNodeWithText(string(R.string.export_pdf_quality_label)).assertIsDisplayed()

        composeTestRule.onNodeWithText(string(R.string.export_format_markdown)).performClick()

        composeTestRule.onNodeWithText(string(R.string.export_pdf_quality_label)).assertDoesNotExist()
    }

    private fun confirmPermission() {
        composeTestRule.onNodeWithText(string(R.string.export_consent_confirm)).performClick()
    }

    private fun showScreen(ocrIncompletePageCount: Int): ExportViewModel {
        val project =
            ExportProjectSummary(
                projectId = UUID.randomUUID(),
                title = "検証用タイトル",
                pageCount = 20,
                ocrIncompletePageCount = ocrIncompletePageCount,
            )
        val viewModel = ExportViewModel(project, starter)
        composeTestRule.setContent {
            PageBinderTheme {
                val uiState by viewModel.uiState.collectAsState()
                ExportScreen(uiState = uiState, actions = actionsOf(viewModel))
            }
        }
        return viewModel
    }

    private fun string(
        resId: Int,
        vararg formatArgs: Any,
    ): String =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .getString(resId, *formatArgs)

    private fun actionsOf(viewModel: ExportViewModel) =
        ExportScreenActions(
            onBack = {},
            onFormatChange = viewModel::onFormatChange,
            onFileNameChange = viewModel::onFileNameChange,
            onPageRangeSelectionChange = viewModel::onPageRangeSelectionChange,
            onPageRangeStartChange = viewModel::onPageRangeStartChange,
            onPageRangeEndChange = viewModel::onPageRangeEndChange,
            onPdfQualityChange = viewModel::onPdfQualityChange,
            onPermissionConfirmedChange = viewModel::onPermissionConfirmedChange,
            onOcrWarningReviewRequested = viewModel::onOcrWarningReviewRequested,
            onOcrWarningContinue = viewModel::onOcrWarningContinue,
            onOcrWarningAbort = viewModel::onOcrWarningAbort,
            onStartExportRequested = viewModel::onStartExportRequested,
            onCancelExport = viewModel::onCancelExport,
            onResultDismissed = viewModel::onResultDismissed,
        )

    /** 書き出しが始まったかどうかだけを数える代役。始まっても何も流さない */
    private class NeverStartingExportStarter : ExportStarter {
        var startCount = 0
            private set

        override fun startExport(options: ExportOptions): Flow<ExportProgressEvent> {
            startCount++
            return emptyFlow()
        }
    }
}
