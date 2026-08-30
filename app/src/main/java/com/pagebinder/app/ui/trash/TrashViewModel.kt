package com.pagebinder.app.ui.trash

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
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

data class TrashBookUiState(
    val id: UUID,
    val title: String,
    val pageCount: Int,
    val storageBytes: Long,
    val remainingDays: Int,
)

data class PermanentDeleteConfirmationUiState(
    val id: UUID,
    val title: String,
    val pageCount: Int,
    val storageBytes: Long,
)

enum class TrashOperationError {
    LOAD,
    RESTORE,
    DELETE,
}

data class TrashUiState(
    val loading: Boolean = true,
    val books: List<TrashBookUiState> = emptyList(),
    val operationInProgress: Boolean = false,
    val operationError: TrashOperationError? = null,
    val deleteConfirmation: PermanentDeleteConfirmationUiState? = null,
) {
    val empty: Boolean get() = !loading && operationError != TrashOperationError.LOAD && books.isEmpty()
}

class TrashViewModel(
    private val repository: BookProjectRepository,
    private val now: () -> Instant = Instant::now,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(TrashUiState())
    val uiState: StateFlow<TrashUiState> = mutableUiState.asStateFlow()

    init {
        load()
    }

    fun load() {
        mutableUiState.update { it.copy(loading = true, operationError = null) }
        viewModelScope.launch {
            try {
                repository.purgeExpiredTrash()
                val books = repository.listTrash()
                mutableUiState.update {
                    it.copy(loading = false, books = books.map { summary -> summary.toTrashBook(now()) })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.update { it.copy(loading = false, operationError = TrashOperationError.LOAD) }
            }
        }
    }

    fun restore(id: UUID) {
        if (mutableUiState.value.operationInProgress) return
        mutableUiState.update { it.copy(operationInProgress = true, operationError = null) }
        viewModelScope.launch {
            try {
                repository.restore(id)
                mutableUiState.update { it.copy(operationInProgress = false) }
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(operationInProgress = false, operationError = TrashOperationError.RESTORE)
                }
            }
        }
    }

    fun requestPermanentDelete(id: UUID) {
        val book = mutableUiState.value.books.firstOrNull { it.id == id } ?: return
        mutableUiState.update {
            it.copy(
                deleteConfirmation =
                    PermanentDeleteConfirmationUiState(
                        id = book.id,
                        title = book.title,
                        pageCount = book.pageCount,
                        storageBytes = book.storageBytes,
                    ),
            )
        }
    }

    fun dismissPermanentDelete() {
        mutableUiState.update { it.copy(deleteConfirmation = null) }
    }

    fun confirmPermanentDelete() {
        val confirmation = mutableUiState.value.deleteConfirmation ?: return
        mutableUiState.update {
            it.copy(deleteConfirmation = null, operationInProgress = true, operationError = null)
        }
        viewModelScope.launch {
            try {
                repository.deletePermanently(confirmation.id)
                mutableUiState.update { it.copy(operationInProgress = false) }
                load()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(operationInProgress = false, operationError = TrashOperationError.DELETE)
                }
            }
        }
    }

    companion object {
        fun factory(repository: BookProjectRepository): ViewModelProvider.Factory =
            viewModelFactory { initializer { TrashViewModel(repository) } }
    }
}

private fun BookProjectSummary.toTrashBook(now: Instant): TrashBookUiState {
    val deletion = requireNotNull(project.deletedAt)
    val elapsedDays = Duration.between(deletion, now).toDays().coerceAtLeast(0).toInt()
    return TrashBookUiState(
        id = project.id,
        title = project.title,
        pageCount = pageCount,
        storageBytes = storageBytes,
        remainingDays = (TRASH_RETENTION_DAYS - elapsedDays).coerceAtLeast(0),
    )
}

private const val TRASH_RETENTION_DAYS = 30
