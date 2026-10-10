/*
 * KernelFeed — an offline-first reader for the lore.kernel.org mailing-list archives.
 * Copyright (C) 2026 Luka Gejak
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License, version 3, as published
 * by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Alternatively, this file is available under a commercial licence that lifts
 * the obligations of the GPL. Enquiries: lukagejak5@gmail.com
 */

package dev.lukag.kernelfeed.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lukag.kernelfeed.core.AppError
import dev.lukag.kernelfeed.core.NetworkMonitor
import dev.lukag.kernelfeed.core.Resource
import dev.lukag.kernelfeed.domain.model.MailingList
import dev.lukag.kernelfeed.domain.repository.ThreadRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ListCatalogUiState(
    val query: String = "",
    val pinned: List<MailingList> = emptyList(),
    val others: List<MailingList> = emptyList(),
    val isRefreshing: Boolean = false,
    val isOffline: Boolean = false,
    val error: AppError? = null,
) {
    val isSearching: Boolean get() = query.isNotBlank()
    val total: Int get() = pinned.size + others.size
    val isEmpty: Boolean get() = total == 0 && !isRefreshing
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ListCatalogViewModel @Inject constructor(
    private val repository: ThreadRepository,
    networkMonitor: NetworkMonitor,
) : ViewModel() {

    private val query = MutableStateFlow("")
    private val local = MutableStateFlow(LocalState())

    private data class LocalState(
        val isRefreshing: Boolean = false,
        val error: AppError? = null,
    )

    /**
     * Catalogue rows come from Room, filtered in SQL.
     *
     * `flatMapLatest` over the query swaps the underlying `Flow` when the text changes, so
     * a stale query's results can never arrive after a newer one's. The filtering happens
     * in the database rather than in Kotlin because it is an indexed `LIKE` over ~350
     * short rows, which is cheaper than materialising them all on every keystroke.
     */
    val state: StateFlow<ListCatalogUiState> = combine(
        query.debounce(120).flatMapLatest { q ->
            if (q.isBlank()) repository.observeLists() else repository.searchLists(q)
        },
        query,
        local,
        networkMonitor.isOnline,
    ) { lists, q, localState, online ->
        // Split with one pass over the already-ordered list rather than with extra
        // queries. Row order is left exactly as the database returned it — the order
        // lore.kernel.org lists its archives in — with only the user's pinned lists
        // floated to the top.
        val pinned = lists.filter { it.isPinned }
        val others = lists.filter { !it.isPinned }

        ListCatalogUiState(
            query = q,
            pinned = pinned,
            others = others,
            isRefreshing = localState.isRefreshing,
            isOffline = !online,
            error = localState.error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListCatalogUiState())

    init {
        viewModelScope.launch {
            // First run populates the catalogue; later launches are a no-op and cost
            // nothing, so the screen opens straight from disk.
            when (val result = repository.ensureListsLoaded()) {
                is Resource.Error -> local.update { it.copy(error = result.error) }
                else -> Unit
            }
        }
    }

    fun onQueryChange(value: String) {
        query.value = value
    }

    fun refresh() {
        if (local.value.isRefreshing) return
        viewModelScope.launch {
            local.update { it.copy(isRefreshing = true, error = null) }
            when (val result = repository.refreshLists()) {
                is Resource.Error -> local.update { it.copy(isRefreshing = false, error = result.error) }
                else -> local.update { it.copy(isRefreshing = false) }
            }
        }
    }

    fun togglePinned(list: MailingList) {
        viewModelScope.launch { repository.setListPinned(list.slug, !list.isPinned) }
    }

    fun dismissError() = local.update { it.copy(error = null) }
}
