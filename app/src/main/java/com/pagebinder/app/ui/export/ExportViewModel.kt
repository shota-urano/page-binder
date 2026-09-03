package com.pagebinder.app.ui.export

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pagebinder.app.domain.ExportDestination
import com.pagebinder.app.domain.ExportFailureCode
import com.pagebinder.app.domain.ExportOptions
import com.pagebinder.app.domain.ExportPdfQuality
import com.pagebinder.app.domain.ExportProgressEvent
import com.pagebinder.app.domain.ExportProgressPhase
import com.pagebinder.app.domain.ExportProjectSummary
import com.pagebinder.app.domain.ExportStarter
import com.pagebinder.app.domain.ExportType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * 書き出し画面（docs/design/11-export.md / docs/specs/11-export.md §3.2）の ViewModel。
 *
 * 書き出し開始までのゲートは3つあり、すべてこの ViewModel が持つ。
 * 1. 権限確認「この成果物を利用する権限を確認しました」（specs 12 §3.2）
 * 2. OCR未完了ページの警告に対する「続行」の選択（FR-EXP-009）
 * 3. ファイル名・ページ範囲の妥当性
 *
 * [ExportStarter] を呼ぶ経路は [onDestinationSelected] だけで、そこでも3ゲートを再確認する。
 * 画面側の不具合で保存先が渡ってきても、続行を選ばない限り書き出しは始まらない。
 *
 * ゲート2の「続行」は**1回の書き出し試行で消費する**（FR-EXP-009 は書き出しごとの選択を要求する）。
 * 消費は書き出しを開始する [onDestinationSelected] の1点だけで起き、
 * 保存先を選ばずに閉じた場合は試行が成立していないので消費を取り消す。
 * これにより成功・失敗・キャンセルのいずれで終わっても、次の書き出しでは再び続行/中止を選ばせる。
 */
class ExportViewModel(
    project: ExportProjectSummary,
    private val exportStarter: ExportStarter,
    /**
     * 最初に選ばれている出力形式。未完了の書き出しの再試行（docs/specs/11-export.md §3.2 末尾）で
     * 前回と同じ形式から始めるために書籍詳細が渡す。通常の書き出しは既定のまま。
     */
    initialFormat: ExportType = ExportType.SEARCHABLE_PDF,
    /**
     * 未完了の書き出しの再試行として開かれたときだけ渡る、取り残されたレコードの片付け
     * （docs/specs/11-export.md §3.2 末尾。何をするかは ExportRecordCoordinator.markInterrupted）。
     *
     * 呼ぶのは書き出しが**成功した**ときだけ。手順5の「完了で確定する」を満たして初めて
     * 取り残しが解消したとみなす（FR-EXP-007）。戻る・SAF を閉じる・失敗した場合は呼ばないので、
     * 書籍詳細の提示は残り、もう一度再試行できる。
     */
    private val resolveInterruptedExport: (suspend () -> Unit)? = null,
) : ViewModel() {
    private val projectId: UUID = project.projectId

    private val mutableUiState =
        MutableStateFlow(
            ExportUiState(
                pageCount = project.pageCount,
                format = initialFormat,
                fileName = project.title,
                ocrIncompletePageCount = project.ocrIncompletePageCount,
            ),
        )
    val uiState: StateFlow<ExportUiState> = mutableUiState.asStateFlow()

    private var exportJob: Job? = null

    fun onFormatChange(format: ExportType) {
        if (mutableUiState.value.exportInProgress) return
        mutableUiState.update { it.copy(format = format) }
    }

    fun onFileNameChange(fileName: String) {
        mutableUiState.update {
            it.copy(fileName = fileName, fileNameErrorVisible = false)
        }
    }

    fun onPageRangeSelectionChange(selection: ExportPageRangeSelection) {
        mutableUiState.update { current ->
            val prefillNeeded =
                selection == ExportPageRangeSelection.CUSTOM &&
                    current.pageRangeStartInput.isBlank() &&
                    current.pageRangeEndInput.isBlank()
            current.copy(
                pageRangeSelection = selection,
                pageRangeStartInput = if (prefillNeeded) "1" else current.pageRangeStartInput,
                pageRangeEndInput =
                    if (prefillNeeded) current.pageCount.toString() else current.pageRangeEndInput,
                pageRangeErrorVisible = false,
            )
        }
    }

    fun onPageRangeStartChange(value: String) {
        mutableUiState.update { it.copy(pageRangeStartInput = value, pageRangeErrorVisible = false) }
    }

    fun onPageRangeEndChange(value: String) {
        mutableUiState.update { it.copy(pageRangeEndInput = value, pageRangeErrorVisible = false) }
    }

    fun onPdfQualityChange(quality: ExportPdfQuality) {
        if (mutableUiState.value.exportInProgress) return
        mutableUiState.update { it.copy(pdfQuality = quality) }
    }

    /** 権限確認チェックの切り替え（[ExportConsentCard] からのコールバック） */
    fun onPermissionConfirmedChange(confirmed: Boolean) {
        mutableUiState.update { it.copy(consent = it.consent.withPermissionConfirmed(confirmed)) }
    }

    /** 警告バナーの「確認」。続行/中止の選択を出す（FR-EXP-009） */
    fun onOcrWarningReviewRequested() {
        if (!mutableUiState.value.ocrWarningVisible) return
        mutableUiState.update { it.copy(ocrWarningDialogVisible = true) }
    }

    /** 続行を選んだ。ここで初めて書き出しへ進める（承認は今回の書き出し1回分） */
    fun onOcrWarningContinue() {
        mutableUiState.update {
            it.copy(ocrWarningAcknowledged = true, ocrWarningDialogVisible = false)
        }
        onStartExportRequested()
    }

    /** 中止を選んだ。続行は記録されないので書き出しは始まらない */
    fun onOcrWarningAbort() {
        mutableUiState.update { it.copy(ocrWarningDialogVisible = false) }
    }

    /**
     * 「保存先を選んで書き出す」。ゲートを順に見て、すべて通ったときだけ SAF の起動要求を出す。
     * この時点ではまだ書き出しは始まらない（保存先が決まっていない）。
     */
    fun onStartExportRequested() {
        val state = mutableUiState.value
        if (state.exportInProgress) return
        if (!state.consent.canStartExport) {
            mutableUiState.update { it.copy(consent = it.consent.withConfirmationRequired()) }
            return
        }
        if (state.blockedByOcrWarning) {
            mutableUiState.update { it.copy(ocrWarningDialogVisible = true) }
            return
        }
        if (state.fileName.isBlank()) {
            mutableUiState.update { it.copy(fileNameErrorVisible = true) }
            return
        }
        if (state.resolvedPageRange == null) {
            mutableUiState.update { it.copy(pageRangeErrorVisible = true) }
            return
        }
        mutableUiState.update {
            it.copy(
                safRequest = ExportSafRequest(state.suggestedFileName, state.format.safMimeType),
                result = null,
            )
        }
    }

    /** SAF を起動したので起動要求を消費する（画面回転で二重に開かないため） */
    fun onSafRequestHandled() {
        mutableUiState.update { it.copy(safRequest = null) }
    }

    /**
     * SAF の結果。[uri] が null なら利用者が保存先を選ばずに閉じたので書き出しを始めない。
     * このとき OCR未完了警告の承認も取り消し、次の書き出しで再び続行/中止を選ばせる（FR-EXP-009）。
     *
     * 書き出しを実際に開始する唯一の場所であり、ここでもゲートを再確認する。
     * 開始と同時に承認を消費するので、成功・失敗・キャンセルのどれで終わっても承認は残らない。
     */
    fun onDestinationSelected(uri: String?) {
        if (uri.isNullOrBlank()) {
            mutableUiState.update { it.copy(ocrWarningAcknowledged = false) }
            return
        }
        val state = mutableUiState.value
        if (!state.canStartExport) return
        val pageRange = state.resolvedPageRange ?: return

        val options =
            ExportOptions(
                projectId = projectId,
                type = state.format,
                fileName = state.suggestedFileName,
                pageRange = pageRange,
                pdfQuality = state.pdfQuality,
                destination = ExportDestination(uri),
            )
        mutableUiState.update {
            it.copy(
                progress = ExportProgressUiState(ExportProgressPhase.QUEUED, 0, 1),
                result = null,
                // 「続行」はこの試行で消費する。次の書き出しは再びゲート2に掛かる（FR-EXP-009）
                ocrWarningAcknowledged = false,
            )
        }
        exportJob?.cancel()
        exportJob =
            viewModelScope.launch {
                exportStarter.startExport(options).collect(::onExportEvent)
            }
    }

    /** 進捗表示のキャンセル。Flow を止めることで Export Engine 側のキャンセルになる */
    fun onCancelExport() {
        if (!mutableUiState.value.exportInProgress) return
        exportJob?.cancel()
        exportJob = null
        mutableUiState.update {
            it.copy(
                progress = null,
                result = ExportResultUiState.Failed(exportFailureGuidanceOf(ExportFailureCode.CANCELLED, it.format)),
            )
        }
    }

    fun onResultDismissed() {
        mutableUiState.update { it.copy(result = null) }
    }

    private fun onExportEvent(event: ExportProgressEvent) {
        if (event is ExportProgressEvent.Succeeded) onRetriedExportSucceeded()
        mutableUiState.update { current ->
            when (event) {
                is ExportProgressEvent.Progress ->
                    current.copy(
                        progress =
                            ExportProgressUiState(event.phase, event.completedUnits, event.totalUnits),
                    )
                ExportProgressEvent.Succeeded ->
                    current.copy(progress = null, result = ExportResultUiState.Succeeded)
                is ExportProgressEvent.Failed ->
                    current.copy(
                        progress = null,
                        result =
                            ExportResultUiState.Failed(
                                exportFailureGuidanceOf(event.errorCode, current.format),
                            ),
                    )
            }
        }
    }

    /**
     * 再試行の書き出しが成功したので、取り残されていたレコードを閉じる。
     * 失敗しても画面は成功のまま（成果物は確定している）。次に書籍詳細を開いたときの
     * 検出でまだ未完了なら提示が出るので、利用者は再試行し直せる。
     */
    private fun onRetriedExportSucceeded() {
        val resolve = resolveInterruptedExport ?: return
        viewModelScope.launch {
            try {
                resolve()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 例外の内容はログへ出さない（保存URIが混ざりうる — AGENTS.md ルール6）
            }
        }
    }

    companion object {
        fun factory(
            project: ExportProjectSummary,
            exportStarter: ExportStarter,
            initialFormat: ExportType = ExportType.SEARCHABLE_PDF,
            resolveInterruptedExport: (suspend () -> Unit)? = null,
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ExportViewModel(project, exportStarter, initialFormat, resolveInterruptedExport)
                }
            }
    }
}
