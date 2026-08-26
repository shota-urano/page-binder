package com.pagebinder.app.ui.export

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 書き出し時の権限確認（docs/specs/12-legal-guardrails.md §3.2）の受け入れ確認。
 * 「確認なしで書き出しが開始されない」ことを、状態ホルダ単体と ViewModel 経由の両方で検証する。
 */
class ExportConsentGateTest {
    @Test
    fun `初期状態は未確認で書き出しを開始できない`() {
        val gate = ExportConsentGate()

        assertFalse(gate.uiState.value.permissionConfirmed)
        assertFalse(gate.uiState.value.canStartExport)
        assertFalse(gate.uiState.value.confirmationRequiredVisible)
    }

    @Test
    fun `権限確認なしで書き出しを要求しても書き出しは開始されない`() {
        val gate = ExportConsentGate()
        var started = 0

        val accepted = gate.requestExport { started++ }

        assertFalse(accepted)
        assertEquals(0, started)
        assertFalse(gate.uiState.value.canStartExport)
        assertTrue(gate.uiState.value.confirmationRequiredVisible)
    }

    @Test
    fun `権限確認にチェックすると書き出しを開始できる`() {
        val gate = ExportConsentGate()
        var started = 0

        gate.onPermissionConfirmedChange(true)
        val accepted = gate.requestExport { started++ }

        assertTrue(accepted)
        assertEquals(1, started)
        assertTrue(gate.uiState.value.canStartExport)
    }

    @Test
    fun `チェックを付けると未確認の案内は消える`() {
        val gate = ExportConsentGate()

        gate.requestExport { }
        assertTrue(gate.uiState.value.confirmationRequiredVisible)

        gate.onPermissionConfirmedChange(true)

        assertFalse(gate.uiState.value.confirmationRequiredVisible)
    }

    @Test
    fun `チェックを外すと再び書き出しを開始できなくなる`() {
        val gate = ExportConsentGate()
        var started = 0

        gate.onPermissionConfirmedChange(true)
        gate.onPermissionConfirmedChange(false)
        val accepted = gate.requestExport { started++ }

        assertFalse(accepted)
        assertEquals(0, started)
        assertFalse(gate.uiState.value.canStartExport)
    }

    @Test
    fun `ViewModel 経由でも確認なしでは書き出しが開始されない`() =
        runTest {
            val viewModel = ExportConsentHostViewModel()

            viewModel.onExportRequested()

            assertEquals(0, viewModel.startedExports)
            assertFalse(viewModel.uiState.value.canStartExport)
            assertTrue(viewModel.uiState.value.confirmationRequiredVisible)

            viewModel.onPermissionConfirmedChange(true)
            viewModel.onExportRequested()

            assertEquals(1, viewModel.startedExports)
        }

    /**
     * 書き出し画面の ViewModel（`ExportViewModel` — pagebinder-gph.6 で実装）が
     * [ExportConsentGate] をどう抱えるかを表すテスト用のスタブ。
     * 本タスクでは書き出し画面そのものを実装しないため、production 側には置かない。
     */
    private class ExportConsentHostViewModel : ViewModel() {
        private val consentGate = ExportConsentGate()

        val uiState: StateFlow<ExportConsentUiState> = consentGate.uiState

        var startedExports = 0
            private set

        fun onPermissionConfirmedChange(confirmed: Boolean) = consentGate.onPermissionConfirmedChange(confirmed)

        fun onExportRequested() {
            consentGate.requestExport { startedExports++ }
        }
    }
}
