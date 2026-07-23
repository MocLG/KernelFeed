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

package dev.lukag.kernelfeed.ui.saved

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lukag.kernelfeed.domain.model.ThreadSummary
import dev.lukag.kernelfeed.domain.repository.ThreadRepository
import dev.lukag.kernelfeed.ui.components.EmptyState
import dev.lukag.kernelfeed.ui.components.ThreadRow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SavedUiState(
    val threads: List<ThreadSummary> = emptyList(),
    val cachedBytes: Long = 0L,
)

@HiltViewModel
class SavedViewModel @Inject constructor(
    private val repository: ThreadRepository,
) : ViewModel() {

    val state: StateFlow<SavedUiState> = combine(
        repository.observeSaved(),
        repository.observeCachedBytes(),
    ) { threads, bytes -> SavedUiState(threads, bytes) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SavedUiState())

    fun unsave(thread: ThreadSummary) {
        viewModelScope.launch { repository.setSaved(thread.rootMessageId, false) }
    }
}

/**
 * Saved threads are read entirely from Room.
 *
 * This screen never issues a request, which is the point: whatever is listed here is
 * guaranteed to open with the network off.
 */
@Composable
fun SavedScreen(
    onOpenThread: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SavedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.threads.isEmpty()) {
        EmptyState(
            title = "Nothing saved yet",
            subtitle = "Bookmark a thread and its full message history is downloaded for offline reading.",
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize()) {
        Text(
            text = "${state.threads.size} threads · ${formatBytes(state.cachedBytes)} cached",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(
                items = state.threads,
                key = { it.rootMessageId },
                contentType = { "thread" },
            ) { thread ->
                ThreadRow(
                    thread = thread,
                    onClick = { onOpenThread(thread.rootMessageId) },
                    onToggleSaved = { viewModel.unsave(thread) },
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.5.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}
