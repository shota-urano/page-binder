package com.pagebinder.app.ui.bookedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pagebinder.app.domain.BookProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.CancellationException

enum class BookEditFieldError {
    REQUIRED,
    TOO_LONG,
}

enum class BookEditOperationError {
    LOAD,
    CREATE,
    UPDATE,
}

data class BookEditUiState(
    val projectId: UUID? = null,
    val loading: Boolean = false,
    val title: String = "",
    val author: String = "",
    val note: String = "",
    val titleError: BookEditFieldError? = null,
    val authorError: BookEditFieldError? = null,
    val noteError: BookEditFieldError? = null,
    val saving: Boolean = false,
    val operationError: BookEditOperationError? = null,
    val savedProjectId: UUID? = null,
) {
    val editing: Boolean get() = projectId != null
    val canSave: Boolean
        get() =
            !loading && !saving &&
                operationError != BookEditOperationError.LOAD &&
                title.isNotBlank() &&
                title.length <= TITLE_LIMIT &&
                author.length <= AUTHOR_LIMIT &&
                note.length <= NOTE_LIMIT
}

class BookEditViewModel(
    private val projectId: UUID?,
    private val repository: BookProjectRepository,
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(BookEditUiState(projectId = projectId, loading = projectId != null))
    val uiState: StateFlow<BookEditUiState> = mutableUiState.asStateFlow()

    init {
        if (projectId != null) load(projectId)
    }

    private fun load(id: UUID) {
        viewModelScope.launch {
            try {
                val project = repository.findById(id)
                if (project == null || project.deletedAt != null) {
                    mutableUiState.update {
                        it.copy(loading = false, operationError = BookEditOperationError.LOAD)
                    }
                } else {
                    mutableUiState.update {
                        it.copy(
                            loading = false,
                            title = project.title,
                            author = project.author.orEmpty(),
                            note = project.note.orEmpty(),
                            operationError = null,
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.update { it.copy(loading = false, operationError = BookEditOperationError.LOAD) }
            }
        }
    }

    fun onTitleChange(value: String) {
        mutableUiState.update {
            it.copy(
                title = value,
                titleError = validateTitle(value),
                operationError = it.operationError.keepLoadError(),
            )
        }
    }

    fun onAuthorChange(value: String) {
        mutableUiState.update {
            it.copy(
                author = value,
                authorError = tooLong(value, AUTHOR_LIMIT),
                operationError = it.operationError.keepLoadError(),
            )
        }
    }

    fun onNoteChange(value: String) {
        mutableUiState.update {
            it.copy(
                note = value,
                noteError = tooLong(value, NOTE_LIMIT),
                operationError = it.operationError.keepLoadError(),
            )
        }
    }

    fun save() {
        val current = mutableUiState.value
        val validated =
            current.copy(
                titleError = validateTitle(current.title),
                authorError = tooLong(current.author, AUTHOR_LIMIT),
                noteError = tooLong(current.note, NOTE_LIMIT),
            )
        mutableUiState.value = validated
        if (!validated.canSave) return
        mutableUiState.update { it.copy(saving = true, operationError = null) }
        viewModelScope.launch {
            try {
                val saved =
                    if (projectId == null) {
                        repository.create(
                            title = validated.title.trim(),
                            author = validated.author.trim().ifEmpty { null },
                            note = validated.note.trim().ifEmpty { null },
                        )
                    } else {
                        repository.update(
                            id = projectId,
                            title = validated.title.trim(),
                            author = validated.author.trim().ifEmpty { null },
                            note = validated.note.trim().ifEmpty { null },
                        )
                    }
                mutableUiState.update { it.copy(saving = false, savedProjectId = saved.id) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableUiState.update {
                    it.copy(
                        saving = false,
                        operationError =
                            if (projectId == null) BookEditOperationError.CREATE else BookEditOperationError.UPDATE,
                    )
                }
            }
        }
    }

    fun onNavigationHandled() {
        mutableUiState.update { it.copy(savedProjectId = null) }
    }

    companion object {
        fun factory(
            projectId: UUID?,
            repository: BookProjectRepository,
        ): ViewModelProvider.Factory = viewModelFactory { initializer { BookEditViewModel(projectId, repository) } }
    }
}

private fun validateTitle(value: String): BookEditFieldError? =
    when {
        value.isBlank() -> BookEditFieldError.REQUIRED
        value.length > TITLE_LIMIT -> BookEditFieldError.TOO_LONG
        else -> null
    }

private fun tooLong(
    value: String,
    limit: Int,
): BookEditFieldError? = BookEditFieldError.TOO_LONG.takeIf { value.length > limit }

private fun BookEditOperationError?.keepLoadError(): BookEditOperationError? =
    takeIf { it == BookEditOperationError.LOAD }

const val TITLE_LIMIT = 200
const val AUTHOR_LIMIT = 200
const val NOTE_LIMIT = 2_000
