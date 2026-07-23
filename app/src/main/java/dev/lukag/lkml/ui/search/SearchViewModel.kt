package dev.lukag.lkml.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lukag.lkml.core.AppError
import dev.lukag.lkml.core.NetworkMonitor
import dev.lukag.lkml.core.Resource
import dev.lukag.lkml.domain.model.Message
import dev.lukag.lkml.domain.model.ThreadSummary
import dev.lukag.lkml.domain.repository.ThreadRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val remoteResults: List<ThreadSummary> = emptyList(),
    val localResults: List<Message> = emptyList(),
    val isSearching: Boolean = false,
    val isLoadingMore: Boolean = false,
    val nextOffset: Int? = null,
    val isOffline: Boolean = false,
    val hasSearched: Boolean = false,
    val error: AppError? = null,
) {
    val hasMore: Boolean get() = nextOffset != null
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: ThreadRepository,
    private val networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChange(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update {
                it.copy(remoteResults = emptyList(), localResults = emptyList(), hasSearched = false)
            }
            return
        }
        searchJob = viewModelScope.launch {
            // Debounce: lore's search is a real Xapian query on a shared host, so a request
            // per keystroke would be both slow for the user and rude to the archive.
            delay(DEBOUNCE_MILLIS)
            runSearch(query, offset = 0, append = false)
        }
    }

    fun submit() {
        searchJob?.cancel()
        val query = _state.value.query
        if (query.isBlank()) return
        searchJob = viewModelScope.launch { runSearch(query, offset = 0, append = false) }
    }

    fun loadMore() {
        val current = _state.value
        val offset = current.nextOffset ?: return
        if (current.isLoadingMore) return
        viewModelScope.launch {
            _state.update { it.copy(isLoadingMore = true) }
            runSearch(current.query, offset, append = true)
        }
    }

    /**
     * Searches the local index first, then the archive.
     *
     * Cached results appear instantly and remain visible even when the network call fails,
     * so search degrades to "everything you already have" rather than to an error page.
     */
    private suspend fun runSearch(query: String, offset: Int, append: Boolean) {
        _state.update {
            it.copy(isSearching = !append, error = null, hasSearched = true, isOffline = !networkMonitor.currentlyOnline())
        }

        (repository.searchLocal(query) as? Resource.Success)?.let { local ->
            _state.update { it.copy(localResults = local.data) }
        }

        when (val remote = repository.searchRemote(query, offset)) {
            is Resource.Success -> _state.update {
                it.copy(
                    remoteResults = if (append) it.remoteResults + remote.data.results else remote.data.results,
                    nextOffset = remote.data.nextOffset,
                    isSearching = false,
                    isLoadingMore = false,
                )
            }
            is Resource.Offline -> _state.update {
                it.copy(isSearching = false, isLoadingMore = false, isOffline = true)
            }
            is Resource.Error -> _state.update {
                it.copy(isSearching = false, isLoadingMore = false, error = remote.error)
            }
            is Resource.Loading -> Unit
        }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private companion object {
        const val DEBOUNCE_MILLIS = 450L
    }
}
