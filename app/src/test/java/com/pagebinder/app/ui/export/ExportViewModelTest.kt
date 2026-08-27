package com.pagebinder.app.ui.export

import com.pagebinder.app.domain.ExportFailureCode
import com.pagebinder.app.domain.ExportOptions
import com.pagebinder.app.domain.ExportPageRange
import com.pagebinder.app.domain.ExportPdfQuality
import com.pagebinder.app.domain.ExportProgressEvent
import com.pagebinder.app.domain.ExportProgressPhase
import com.pagebinder.app.domain.ExportProjectSummary
import com.pagebinder.app.domain.ExportStarter
import com.pagebinder.app.domain.ExportStorageErrorCode
import com.pagebinder.app.domain.ExportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * 書き出し画面（docs/specs/11-export.md §3.2）の受け入れ確認。
 *
 * 検証の中心は「書き出しが始まらない条件」。
 * - 権限確認なし（docs/specs/12-legal-guardrails.md §3.2）
 * - OCR未完了の警告に「続行」を選んでいない（FR-EXP-009）
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExportViewModelTest {
    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- 初期状態 -------------------------------------------------------------

    @Test
    fun `初期状態は書籍プロジェクトの内容から作られる`() =
        runTest {
            val viewModel = viewModel(project = project(title = "検証用タイトル", pageCount = 42))

            val state = viewModel.uiState.value
            assertEquals("検証用タイトル", state.fileName)
            assertEquals(42, state.pageCount)
            assertEquals(ExportType.SEARCHABLE_PDF, state.format)
            assertEquals(ExportPdfQuality.STANDARD, state.pdfQuality)
            assertEquals(ExportPageRangeSelection.ALL, state.pageRangeSelection)
            assertFalse(state.consent.permissionConfirmed)
            assertNull(state.safRequest)
            assertNull(state.progress)
            assertNull(state.result)
        }

    // --- 権限確認ゲート（docs/specs/12-legal-guardrails.md §3.2）-----------------

    @Test
    fun `権限確認なしで書き出しを要求しても保存先選択へ進まない`() =
        runTest {
            val starter = RecordingExportStarter()
            val viewModel = viewModel(starter = starter)

            viewModel.onStartExportRequested()

            assertEquals(0, starter.startCount)
            assertNull(viewModel.uiState.value.safRequest)
            assertTrue(viewModel.uiState.value.consent.confirmationRequiredVisible)
        }

    @Test
    fun `チェックを外すと再び保存先選択へ進めなくなる`() =
        runTest {
            val starter = RecordingExportStarter()
            val viewModel = viewModel(starter = starter)

            viewModel.onPermissionConfirmedChange(true)
            viewModel.onPermissionConfirmedChange(false)
            viewModel.onStartExportRequested()

            assertEquals(0, starter.startCount)
            assertNull(viewModel.uiState.value.safRequest)
        }

    @Test
    fun `権限確認なしで保存先が渡ってきても書き出しは始まらない`() =
        runTest {
            val starter = RecordingExportStarter()
            val viewModel = viewModel(starter = starter)

            viewModel.onDestinationSelected(DESTINATION_URI)

            assertEquals(0, starter.startCount)
            assertNull(viewModel.uiState.value.progress)
        }

    // --- OCR未完了の警告（FR-EXP-009）-------------------------------------------

    @Test
    fun `OCR未完了ページがあると警告バナーを出す`() =
        runTest {
            val viewModel = viewModel(project = project(ocrIncompletePageCount = 3))

            val state = viewModel.uiState.value
            assertTrue(state.ocrWarningVisible)
            assertEquals(3, state.ocrIncompletePageCount)
            assertFalse(state.ocrWarningDialogVisible)
        }

    @Test
    fun `OCR未完了のまま書き出しを要求すると続行と中止の選択が出て書き出しは始まらない`() =
        runTest {
            val starter = RecordingExportStarter()
            val viewModel = viewModel(project = project(ocrIncompletePageCount = 3), starter = starter)
            viewModel.onPermissionConfirmedChange(true)

            viewModel.onStartExportRequested()

            val state = viewModel.uiState.value
            assertTrue("続行/中止の選択が出ること", state.ocrWarningDialogVisible)
            assertFalse(state.ocrWarningAcknowledged)
            assertNull("保存先選択へ進まないこと", state.safRequest)
            assertEquals("書き出しが始まらないこと", 0, starter.startCount)
        }

    @Test
    fun `中止を選ぶと書き出しは始まらない`() =
        runTest {
            val starter = RecordingExportStarter()
            val viewModel = viewModel(project = project(ocrIncompletePageCount = 3), starter = starter)
            viewModel.onPermissionConfirmedChange(true)
            viewModel.onStartExportRequested()

            viewModel.onOcrWarningAbort()

            val state = viewModel.uiState.value
            assertFalse(state.ocrWarningDialogVisible)
            assertFalse(state.ocrWarningAcknowledged)
            assertNull(state.safRequest)
            assertEquals(0, starter.startCount)
        }

    @Test
    fun `続行を選ぶと保存先選択へ進み、保存先が決まって初めて書き出しが始まる`() =
        runTest {
            val starter = RecordingExportStarter()
            val viewModel = viewModel(project = project(ocrIncompletePageCount = 3), starter = starter)
            viewModel.onPermissionConfirmedChange(true)
            viewModel.onStartExportRequested()

            viewModel.onOcrWarningContinue()

            assertTrue(viewModel.uiState.value.ocrWarningAcknowledged)
            assertNotNull(viewModel.uiState.value.safRequest)
            assertEquals("保存先が決まるまでは始まらないこと", 0, starter.startCount)

            viewModel.onSafRequestHandled()
            viewModel.onDestinationSelected(DESTINATION_URI)

            assertEquals(1, starter.startCount)
        }

    @Test
    fun `続行を選ばないまま保存先が渡ってきても書き出しは始まらない`() =
        runTest {
            val starter = RecordingExportStarter()
            val viewModel = viewModel(project = project(ocrIncompletePageCount = 1), starter = starter)
            viewModel.onPermissionConfirmedChange(true)

            viewModel.onDestinationSelected(DESTINATION_URI)

            assertEquals(0, starter.startCount)
            assertNull(viewModel.uiState.value.progress)
        }

    @Test
    fun `OCR未完了が無ければ警告なしで保存先選択へ進む`() =
        runTest {
            val viewModel = viewModel(project = project(ocrIncompletePageCount = 0))
            viewModel.onPermissionConfirmedChange(true)

            viewModel.onStartExportRequested()

            assertFalse(viewModel.uiState.value.ocrWarningVisible)
            assertFalse(viewModel.uiState.value.ocrWarningDialogVisible)
            assertNotNull(viewModel.uiState.value.safRequest)
        }

    @Test
    fun `バナーの確認からも続行と中止を選べる`() =
        runTest {
            val viewModel = viewModel(project = project(ocrIncompletePageCount = 2))

            viewModel.onOcrWarningReviewRequested()

            assertTrue(viewModel.uiState.value.ocrWarningDialogVisible)
        }

    // --- 入力の妥当性 -----------------------------------------------------------

    @Test
    fun `ファイル名が空だと保存先選択へ進まない`() =
        runTest {
            val starter = RecordingExportStarter()
            val viewModel = viewModel(starter = starter)
            viewModel.onPermissionConfirmedChange(true)
            viewModel.onFileNameChange("")

            viewModel.onStartExportRequested()

            assertTrue(viewModel.uiState.value.fileNameErrorVisible)
            assertNull(viewModel.uiState.value.safRequest)
            assertEquals(0, starter.startCount)
        }

    @Test
    fun `ページ範囲が書籍のページ数を超えると保存先選択へ進まない`() =
        runTest {
            val starter = RecordingExportStarter()
            val viewModel = viewModel(project = project(pageCount = 10), starter = starter)
            viewModel.onPermissionConfirmedChange(true)
            viewModel.onPageRangeSelectionChange(ExportPageRangeSelection.CUSTOM)
            viewModel.onPageRangeEndChange("11")

            viewModel.onStartExportRequested()

            assertNull(viewModel.uiState.value.resolvedPageRange)
            assertTrue(viewModel.uiState.value.pageRangeErrorVisible)
            assertNull(viewModel.uiState.value.safRequest)
            assertEquals(0, starter.startCount)
        }

    @Test
    fun `範囲を指定に切り替えると全ページが初期値になり、指定した範囲が書き出し条件に載る`() =
        runTest {
            val starter = RecordingExportStarter()
            val viewModel = viewModel(project = project(pageCount = 128), starter = starter)
            viewModel.onPermissionConfirmedChange(true)

            viewModel.onPageRangeSelectionChange(ExportPageRangeSelection.CUSTOM)
            assertEquals("1", viewModel.uiState.value.pageRangeStartInput)
            assertEquals("128", viewModel.uiState.value.pageRangeEndInput)

            viewModel.onPageRangeStartChange("10")
            viewModel.onPageRangeEndChange("20")
            viewModel.onStartExportRequested()
            viewModel.onDestinationSelected(DESTINATION_URI)

            assertEquals(ExportPageRange.Bounded(10, 20), starter.lastOptions?.pageRange)
        }

    // --- 書き出し条件の受け渡し ---------------------------------------------------

    @Test
    fun `選んだ形式と画質と保存先が書き出し条件として渡る`() =
        runTest {
            val projectId = UUID.randomUUID()
            val starter = RecordingExportStarter()
            val viewModel =
                viewModel(project = project(projectId = projectId, title = "資料"), starter = starter)
            viewModel.onPermissionConfirmedChange(true)
            viewModel.onFormatChange(ExportType.MARKDOWN)
            viewModel.onPdfQualityChange(ExportPdfQuality.HIGH)

            viewModel.onStartExportRequested()
            viewModel.onDestinationSelected(DESTINATION_URI)

            val options = requireNotNull(starter.lastOptions)
            assertEquals(projectId, options.projectId)
            assertEquals(ExportType.MARKDOWN, options.type)
            assertEquals("資料.md", options.fileName)
            assertEquals(ExportPageRange.All, options.pageRange)
            assertEquals(ExportPdfQuality.HIGH, options.pdfQuality)
            assertEquals(DESTINATION_URI, options.destination.uri)
        }

    @Test
    fun `保存先選択の起動要求は形式に応じた拡張子とMIMEタイプを運ぶ`() =
        runTest {
            val viewModel = viewModel(project = project(title = "資料"))
            viewModel.onPermissionConfirmedChange(true)
            viewModel.onFormatChange(ExportType.IMAGE_ZIP)

            viewModel.onStartExportRequested()

            val request = requireNotNull(viewModel.uiState.value.safRequest)
            assertEquals("資料.zip", request.suggestedFileName)
            assertEquals("application/zip", request.mimeType)
        }

    @Test
    fun `保存先を選ばずに閉じても書き出しは始まらない`() =
        runTest {
            val starter = RecordingExportStarter()
            val viewModel = viewModel(starter = starter)
            viewModel.onPermissionConfirmedChange(true)
            viewModel.onStartExportRequested()

            viewModel.onDestinationSelected(null)

            assertEquals(0, starter.startCount)
            assertNull(viewModel.uiState.value.progress)
        }

    @Test
    fun `PDF画質はPDF形式のときだけ意味を持つ`() =
        runTest {
            val viewModel = viewModel()

            assertTrue(viewModel.uiState.value.pdfQualityVisible)
            viewModel.onFormatChange(ExportType.TEXT_ZIP)
            assertFalse(viewModel.uiState.value.pdfQualityVisible)
        }

    // --- 進捗・結果（docs/specs/11-export.md §3.2 手順6・§6）----------------------

    @Test
    fun `進捗が UiState に反映される`() =
        runTest {
            val starter =
                RecordingExportStarter(
                    listOf(ExportProgressEvent.Progress(ExportProgressPhase.GENERATING, 3, 4)),
                )
            val viewModel = startedExport(starter)

            val progress = requireNotNull(viewModel.uiState.value.progress)
            assertEquals(ExportProgressPhase.GENERATING, progress.phase)
            assertEquals(75, progress.percent)
        }

    @Test
    fun `成功で進捗が消えて成功表示になる`() =
        runTest {
            val starter = RecordingExportStarter(listOf(ExportProgressEvent.Succeeded))
            val viewModel = startedExport(starter)

            assertNull(viewModel.uiState.value.progress)
            assertEquals(ExportResultUiState.Succeeded, viewModel.uiState.value.result)
        }

    @Test
    fun `PDF生成の失敗は画像PDFやMarkdownへのフォールバック案内になる`() =
        runTest {
            val starter =
                RecordingExportStarter(
                    listOf(ExportProgressEvent.Failed(ExportFailureCode.GENERATION_FAILED)),
                )
            val viewModel = startedExport(starter)

            assertEquals(
                ExportResultUiState.Failed(ExportFailureGuidance.PDF_FALLBACK),
                viewModel.uiState.value.result,
            )
        }

    @Test
    fun `保存先が使えない失敗は端末内保存への切替案内になる`() =
        runTest {
            val starter =
                RecordingExportStarter(
                    listOf(
                        ExportProgressEvent.Failed(
                            ExportStorageErrorCode.DESTINATION_PERMISSION_DENIED.serializedName,
                        ),
                    ),
                )
            val viewModel = startedExport(starter)

            assertEquals(
                ExportResultUiState.Failed(ExportFailureGuidance.DESTINATION_UNAVAILABLE),
                viewModel.uiState.value.result,
            )
        }

    @Test
    fun `キャンセルすると進捗が消えて中止表示になる`() =
        runTest {
            val starter = RecordingExportStarter(neverCompleting = true)
            val viewModel = startedExport(starter)
            assertNotNull(viewModel.uiState.value.progress)

            viewModel.onCancelExport()

            assertNull(viewModel.uiState.value.progress)
            assertEquals(
                ExportResultUiState.Failed(ExportFailureGuidance.CANCELLED),
                viewModel.uiState.value.result,
            )
        }

    @Test
    fun `書き出し中は二重に開始しない`() =
        runTest {
            val starter = RecordingExportStarter(neverCompleting = true)
            val viewModel = startedExport(starter)

            viewModel.onStartExportRequested()

            assertNull(viewModel.uiState.value.safRequest)
            assertEquals(1, starter.startCount)

            viewModel.onCancelExport()
        }

    // --- ヘルパ -----------------------------------------------------------------

    private fun viewModel(
        project: ExportProjectSummary = project(),
        starter: ExportStarter = RecordingExportStarter(),
    ) = ExportViewModel(project, starter)

    private fun project(
        projectId: UUID = UUID.randomUUID(),
        title: String = "検証用タイトル",
        pageCount: Int = 20,
        ocrIncompletePageCount: Int = 0,
    ) = ExportProjectSummary(projectId, title, pageCount, ocrIncompletePageCount)

    /** 権限確認済み・保存先選択済みまで進めた ViewModel */
    private fun startedExport(starter: RecordingExportStarter): ExportViewModel {
        val viewModel = viewModel(starter = starter)
        viewModel.onPermissionConfirmedChange(true)
        viewModel.onStartExportRequested()
        viewModel.onSafRequestHandled()
        viewModel.onDestinationSelected(DESTINATION_URI)
        return viewModel
    }

    /** Export Engine（`export/`）の代役。呼ばれた条件を記録し、決められた経過だけを流す */
    private class RecordingExportStarter(
        private val events: List<ExportProgressEvent> = emptyList(),
        private val neverCompleting: Boolean = false,
    ) : ExportStarter {
        private val requests = mutableListOf<ExportOptions>()

        val startCount: Int get() = requests.size
        val lastOptions: ExportOptions? get() = requests.lastOrNull()

        override fun startExport(options: ExportOptions): Flow<ExportProgressEvent> {
            requests += options
            if (!neverCompleting) return events.asFlow()
            return flow {
                emit(ExportProgressEvent.Progress(ExportProgressPhase.GENERATING, 0, 1))
                awaitCancellation()
            }
        }
    }

    private companion object {
        const val DESTINATION_URI = "content://com.example.documents/document/42"
    }
}
