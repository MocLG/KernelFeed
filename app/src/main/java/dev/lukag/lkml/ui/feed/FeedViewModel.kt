package dev.lukag.lkml.ui.feed

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lukag.lkml.core.AppError
import dev.lukag.lkml.core.NetworkMonitor
import dev.lukag.lkml.core.Resource
import dev.lukag.lkml.domain.model.MailingList
import dev.lukag.lkml.domain.model.ThreadSummary
import dev.lukag.lkml.domain.repository.ThreadRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FeedUiState(
    val listSlug: String = "",
    val list: MailingList? = null,
    val threads: List<ThreadSummary> = emptyList(),
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = true,
    val isOffline: Boolean = false,
    val error: AppError? = null,
) {
    /** Distinguishes "nothing cached yet" from "cache is genuinely empty". */
    val showEmptyState: Boolean get() = threads.isEmpty() && !isRefreshing
}

@HiltViewModel
class FeedViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: ThreadRepository,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    /** Which archive this feed shows; supplied by the route. */
    private val listSlug: String = checkNotNull(savedStateHandle["listSlug"]) {
        "FeedViewModel requires a listSlug route argument"
    }

    private val local = MutableStateFlow(LocalState())

    private data class LocalState(
        val isRefreshing: Boolean = false,
        val isLoadingMore: Boolean = false,
        val hasMore: Boolean = true,
        val error: AppError? = null,
    )

    /**
     * The list always comes from Room.
     *
     * A cold start therefore paints the last-seen feed immediately and the refresh, if it
     * succeeds, arrives as a database update. There is no spinner-first state and no
     * code path where an unreachable network yields a blank screen.
     */
    val state: StateFlow<FeedUiState> = combine(
        repository.observeFeed(listSlug),
        repository.observeList(listSlug),
        local,
        networkMonitor.isOnline,
    ) { threads, list, localState, online ->
        FeedUiState(
            listSlug = listSlug,
            list = list,
            threads = threads,
            isRefreshing = localState.isRefreshing,
            isLoadingMore = localState.isLoadingMore,
            hasMore = localState.hasMore,
            isOffline = !online,
            error = localState.error,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        FeedUiState(listSlug = listSlug),
    )

    init {
        refresh()
    }

    fun refresh() {
        if (local.value.isRefreshing) return
        viewModelScope.launch {
            local.update { it.copy(isRefreshing = true, error = null) }
            when (val result = repository.refreshFeed(listSlug)) {
                is Resource.Success ->
                    local.update { it.copy(isRefreshing = false, hasMore = true) }
                is Resource.Offline ->
                    // Cached rows are already on screen; an offline refresh is not an error.
                    local.update { it.copy(isRefreshing = false) }
                // The error is always recorded; whether it reads as a full-screen failure
                // or a dismissible banner is the screen's call, based on whether there is
                // cached data underneath it.
                is Resource.Error ->
                    local.update { it.copy(isRefreshing = false, error = result.error) }
                is Resource.Loading -> Unit
            }
        }
    }

    fun loadMore() {
        val current = local.value
        if (current.isLoadingMore || current.isRefreshing || !current.hasMore) return
        viewModelScope.launch {
            local.update { it.copy(isLoadingMore = true) }
            when (val result = repository.loadMoreFeed(listSlug)) {
                is Resource.Success ->
                    local.update { it.copy(isLoadingMore = false, hasMore = result.data) }
                is Resource.Offline ->
                    local.update { it.copy(isLoadingMore = false, hasMore = false) }
                is Resource.Error ->
                    local.update { it.copy(isLoadingMore = false, error = result.error) }
                is Resource.Loading -> Unit
            }
        }
    }

    fun toggleSaved(thread: ThreadSummary) {
        viewModelScope.launch {
            repository.setSaved(thread.rootMessageId, !thread.isSaved)
            if (!thread.isSaved) repository.ensureThreadCached(thread.rootMessageId)
        }
    }

    fun dismissError() = local.update { it.copy(error = null) }
}
