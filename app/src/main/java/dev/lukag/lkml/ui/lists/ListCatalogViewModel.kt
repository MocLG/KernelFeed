/*
 * LKML — an offline-first reader for the lore.kernel.org mailing-list archives.
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

package dev.lukag.lkml.ui.lists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lukag.lkml.core.AppError
import dev.lukag.lkml.core.NetworkMonitor
import dev.lukag.lkml.core.Resource
import dev.lukag.lkml.domain.model.MailingList
import dev.lukag.lkml.domain.repository.ThreadRepository
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
    val featured: List<MailingList> = emptyList(),
    val others: List<MailingList> = emptyList(),
    val isRefreshing: Boolean = false,
    val isOffline: Boolean = false,
    val error: AppError? = null,
) {
    val isSearching: Boolean get() = query.isNotBlank()
    val total: Int get() = pinned.size + featured.size + others.size
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
        // Sections are derived here rather than with three queries: one pass over an
        // already-ordered list is cheaper than three round trips to SQLite.
        val pinned = lists.filter { it.isPinned }
        val featured = lists.filter { !it.isPinned && it.isFeatured }
        val others = lists.filter { !it.isPinned && !it.isFeatured }

        ListCatalogUiState(
            query = q,
            pinned = pinned,
            featured = featured,
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
