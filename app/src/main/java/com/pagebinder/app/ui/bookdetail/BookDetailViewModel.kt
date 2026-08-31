package com.pagebinder.app.ui.bookdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pagebinder.app.domain.BookProjectRepository
import com.pagebinder.app.domain.BookProjectSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
)

class BookDetailViewModel(
    private val projectId: UUID,
    private val repository: BookProjectRepository,
    private val enqueueProjectOcr: suspend (UUID) -> Int,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = mutableUiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutableUiState.update { it.copy(loading = true, operationError = null) }
        viewModelScope.launch {
            try {
                val summary = repository.findSummaryById(projectId)
                if (summary == null || summary.project.deletedAt != null) {
                    mutableUiState.update {
                        it.copy(loading = false, operationError = BookDetailOperationError.LOAD)
                    }
                } else {
                    mutableUiState.value = summary.toUiState()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.update { it.copy(loading = false, operationError = BookDetailOperationError.LOAD) }
            }
        }
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

private fun BookProjectSummary.toUiState() =
    BookDetailUiState(
        loading = false,
        title = project.title,
        author = project.author,
        note = project.note,
        pageCount = pageCount,
        ocrCompletedCount = ocrCompletedCount,
        ocrErrorCount = ocrErrorCount,
        storageBytes = storageBytes,
    )
