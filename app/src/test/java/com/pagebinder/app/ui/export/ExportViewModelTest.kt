package com.pagebinder.app.ui.export

import com.pagebinder.app.domain.ExportStarter
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

/**
 * 書き出し時の権限確認（docs/specs/12-legal-guardrails.md §3.2）の受け入れ確認。
 * 「確認なしで書き出しが開始されない」ことを production の [ExportViewModel] 経由で検証する。
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

    @Test
    fun `初期状態は未確認で書き出しを開始できない`() =
        runTest {
            val viewModel = ExportViewModel(RecordingExportStarter())

            val consent = viewModel.uiState.value.consent
            assertFalse(consent.permissionConfirmed)
            assertFalse(consent.canStartExport)
            assertFalse(consent.confirmationRequiredVisible)
        }

    @Test
    fun `権限確認なしで書き出しを要求しても書き出しは開始されない`() =
        runTest {
            val starter = RecordingExportStarter()
            val viewModel = ExportViewModel(starter)

            viewModel.onStartExportRequested()

            assertEquals(0, starter.startCount)
            assertFalse(viewModel.uiState.value.consent.canStartExport)
            assertTrue(viewModel.uiState.value.consent.confirmationRequiredVisible)
        }

    @Test
    fun `権限確認にチェックすると書き出しが開始される`() =
        runTest {
            val starter = RecordingExportStarter()
            val viewModel = ExportViewModel(starter)

            viewModel.onPermissionConfirmedChange(true)
            viewModel.onStartExportRequested()

            assertEquals(1, starter.startCount)
            assertTrue(viewModel.uiState.value.consent.canStartExport)
        }

    @Test
    fun `チェックを外すと再び書き出しが開始されなくなる`() =
        runTest {
            val starter = RecordingExportStarter()
            val viewModel = ExportViewModel(starter)

            viewModel.onPermissionConfirmedChange(true)
            viewModel.onPermissionConfirmedChange(false)
            viewModel.onStartExportRequested()

            assertEquals(0, starter.startCount)
            assertFalse(viewModel.uiState.value.consent.canStartExport)
        }

    @Test
    fun `チェックを付けると未確認の案内は消える`() =
        runTest {
            val viewModel = ExportViewModel(RecordingExportStarter())

            viewModel.onStartExportRequested()
            assertTrue(viewModel.uiState.value.consent.confirmationRequiredVisible)

            viewModel.onPermissionConfirmedChange(true)

            assertFalse(viewModel.uiState.value.consent.confirmationRequiredVisible)
        }

    /** 書き出し処理（Export Engine — pagebinder-gph.5）の代役。呼ばれた回数だけを数える */
    private class RecordingExportStarter : ExportStarter {
        var startCount = 0
            private set

        override suspend fun startExport() {
            startCount++
        }
    }
}
