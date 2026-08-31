package com.pagebinder.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.pagebinder.app.domain.BookProjectRepository
import com.pagebinder.app.domain.BookProjectSort
import com.pagebinder.app.domain.BookProjectSummary
import com.pagebinder.app.domain.Page
import com.pagebinder.app.domain.PageCrop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CancellationException

data class HomeBookUiState(
    val id: UUID,
    val title: String,
    val author: String?,
    val pageCount: Int,
    val updatedAt: Instant,
    val firstPageId: UUID? = null,
    val firstPageRotation: Int = 0,
    val firstPageCrop: PageCrop = PageCrop(),
)

data class HomeUiState(
    val loading: Boolean = true,
    val loadFailed: Boolean = false,
    val query: String = "",
    val sort: BookProjectSort = BookProjectSort.UPDATED_AT,
    val books: List<HomeBookUiState> = emptyList(),
) {
    val empty: Boolean get() = !loading && !loadFailed && books.isEmpty() && query.isBlank()
    val noSearchResults: Boolean get() = !loading && !loadFailed && books.isEmpty() && query.isNotBlank()
}

class HomeViewModel(
    private val repository: BookProjectRepository,
    private val findFirstPage: suspend (UUID) -> Page? = { null },
) : ViewModel() {
    private val mutableUiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = mutableUiState.asStateFlow()
    private var requestVersion = 0

    init {
        load()
    }

    fun load() {
        val version = ++requestVersion
        mutableUiState.update { it.copy(loading = true, loadFailed = false) }
        viewModelScope.launch {
            val state = mutableUiState.value
            try {
                val summaries =
                    if (state.query.isBlank()) {
                        repository.listActive(state.sort)
                    } else {
                        repository.searchActive(state.query, state.sort)
                    }
                val books =
                    summaries.map { summary ->
                        summary.toHomeBookUiState(findFirstPage(summary.project.id))
                    }
                if (version != requestVersion) return@launch
                mutableUiState.update {
                    it.copy(
                        loading = false,
                        loadFailed = false,
                        books = books,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (version != requestVersion) return@launch
                mutableUiState.update { it.copy(loading = false, loadFailed = true) }
            }
        }
    }

    fun onQueryChange(query: String) {
        mutableUiState.update { it.copy(query = query) }
        load()
    }

    fun onSortChange(sort: BookProjectSort) {
        if (sort == mutableUiState.value.sort) return
        mutableUiState.update { it.copy(sort = sort) }
        load()
    }

    companion object {
        fun factory(
            repository: BookProjectRepository,
            findFirstPage: suspend (UUID) -> Page?,
        ): ViewModelProvider.Factory = viewModelFactory { initializer { HomeViewModel(repository, findFirstPage) } }
    }
}

private fun BookProjectSummary.toHomeBookUiState(firstPage: Page?) =
    HomeBookUiState(
        id = project.id,
        title = project.title,
        author = project.author,
        pageCount = pageCount,
        updatedAt = project.updatedAt,
        firstPageId = firstPage?.id,
        firstPageRotation = firstPage?.rotation ?: 0,
        firstPageCrop = firstPage?.crop ?: PageCrop(),
    )
