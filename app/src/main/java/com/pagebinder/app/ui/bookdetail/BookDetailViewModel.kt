package com.pagebinder.app.ui.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pagebinder.app.domain.BookProjectRepository
import com.pagebinder.app.domain.BookProjectSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.CancellationException

data class MoveToTrashConfirmationUiState(
    val title: String,
    val pageCount: Int,
    val storageBytes: Long,
)

enum class BookDetailOperationError {
    LOAD,
    MOVE_TO_TRASH,
    OCR_BATCH,
}

data class BookDetailUiState(
    val loading: Boolean = true,
    val title: String = "",
    val author: String? = null,
    val note: String? = null,
    val pageCount: Int = 0,
    val ocrCompletedCount: Int = 0,
    val ocrErrorCount: Int = 0,
    val storageBytes: Long = 0,
    val moveToTrashConfirmation: MoveToTrashConfirmationUiState? = null,
    val operationInProgress: Boolean = false,
    val operationError: BookDetailOperationError? = null,
    val movedToTrash: Boolean = false,
    val queuedOcrCount: Int? = null,
) {
    /** 1ページも無い書籍には書き出す成果物が無い（docs/specs/11-export.md §2 入力）。 */
    val exportAvailable: Boolean get() = !loading && pageCount > 0
}

class BookDetailViewModel(
    private val projectId: UUID,
    private val repository: BookProjectRepository,
    private val enqueueProjectOcr: suspend (UUID) -> Int,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = mutableUiState.asStateFlow()
    private var summaryJob: Job? = null

    /** ごみ箱へ移動した直後の「書籍が消えた」通知を、読み込み失敗と取り違えないための印。 */
    private var movingToTrash = false

    init {
        load()
    }

    /**
     * 統計を Repository の Flow で購読し直す。
     *
     * 一度読みでは撮影オーバーレイでページが増えても書籍詳細が古い統計のまま残り、書き出しが
     * 無効に見えてしまう。購読にすることで、画面を離れずにページ追加・削除が反映される。
     */
    fun load() {
        summaryJob?.cancel()
        mutableUiState.update { it.copy(loading = true, operationError = null) }
        summaryJob =
            viewModelScope.launch {
                repository
                    .observeSummaryById(projectId)
                    .catch { failure ->
                        if (failure is CancellationException) throw failure
                        mutableUiState.update {
                            it.copy(loading = false, operationError = BookDetailOperationError.LOAD)
                        }
                    }.collect(::onSummary)
            }
    }

    private fun onSummary(summary: BookProjectSummary?) {
        if (summary == null || summary.project.deletedAt != null) {
            // 自分の操作でごみ箱へ入れた直後は、ホームへ戻る遷移に任せる
            if (movingToTrash || mutableUiState.value.movedToTrash) return
            mutableUiState.update { it.copy(loading = false, operationError = BookDetailOperationError.LOAD) }
            return
        }
        mutableUiState.update { it.withSummary(summary) }
    }

    fun onMoveToTrashRequested() {
        val state = mutableUiState.value
        if (state.loading || state.operationInProgress || state.operationError == BookDetailOperationError.LOAD) return
        mutableUiState.update {
            it.copy(
                moveToTrashConfirmation =
                    MoveToTrashConfirmationUiState(
                        title = it.title,
                        pageCount = it.pageCount,
                        storageBytes = it.storageBytes,
                    ),
            )
        }
    }

    fun onMoveToTrashDismissed() {
        mutableUiState.update { it.copy(moveToTrashConfirmation = null) }
    }

    fun onMoveToTrashConfirmed() {
        if (mutableUiState.value.moveToTrashConfirmation == null) return
        movingToTrash = true
        mutableUiState.update {
            it.copy(moveToTrashConfirmation = null, operationInProgress = true, operationError = null)
        }
        viewModelScope.launch {
            try {
                repository.moveToTrash(projectId)
                mutableUiState.update { state ->
                    state.copy(operationInProgress = false, movedToTrash = true)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                movingToTrash = false
                mutableUiState.update {
                    it.copy(operationInProgress = false, operationError = BookDetailOperationError.MOVE_TO_TRASH)
                }
            }
        }
    }

    fun onOcrBatchRequested() {
        if (mutableUiState.value.operationInProgress) return
        mutableUiState.update { it.copy(operationInProgress = true, operationError = null, queuedOcrCount = null) }
        viewModelScope.launch {
            try {
                val count = enqueueProjectOcr(projectId)
                mutableUiState.update { it.copy(operationInProgress = false, queuedOcrCount = count) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(operationInProgress = false, operationError = BookDetailOperationError.OCR_BATCH)
                }
            }
        }
    }

    fun onMessageDismissed() {
        mutableUiState.update { it.copy(operationError = null, queuedOcrCount = null) }
    }

    companion object {
        fun factory(
            projectId: UUID,
            repository: BookProjectRepository,
            enqueueProjectOcr: suspend (UUID) -> Int,
        ): ViewModelProvider.Factory =
            viewModelFactory { initializer { BookDetailViewModel(projectId, repository, enqueueProjectOcr) } }
    }
}

/** 統計だけを差し替え、進行中の操作・確認ダイアログ・メッセージはそのまま残す。 */
private fun BookDetailUiState.withSummary(summary: BookProjectSummary) =
    copy(
        loading = false,
        title = summary.project.title,
        author = summary.project.author,
        note = summary.project.note,
        pageCount = summary.pageCount,
        ocrCompletedCount = summary.ocrCompletedCount,
        ocrErrorCount = summary.ocrErrorCount,
        storageBytes = summary.storageBytes,
        operationError = operationError.takeIf { it != BookDetailOperationError.LOAD },
    )
