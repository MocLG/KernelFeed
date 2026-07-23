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

package dev.lukag.kernelfeed.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.OfflineBolt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lukag.kernelfeed.domain.model.ThreadSummary
import dev.lukag.kernelfeed.ui.components.EmptyState
import dev.lukag.kernelfeed.ui.components.ErrorBanner
import dev.lukag.kernelfeed.ui.components.OfflineBanner
import dev.lukag.kernelfeed.ui.components.ThreadRow
import dev.lukag.kernelfeed.ui.util.formatRelative

@Composable
fun SearchScreen(
    onOpenThread: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Search the archive") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear search")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { viewModel.submit() }),
        )

        if (state.isOffline) OfflineBanner()
        state.error?.let {
            ErrorBanner(error = it, onRetry = viewModel::submit, onDismiss = viewModel::dismissError)
        }

        when {
            !state.hasSearched -> EmptyState(
                title = "Search lore.kernel.org",
                subtitle = "Try a subsystem (\"io_uring\"), a file path, or an author's address. " +
                    "Threads you've already opened are searched offline too.",
            )

            state.isSearching -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator()
            }

            state.remoteResults.isEmpty() && state.localResults.isEmpty() -> EmptyState(
                title = "No matches",
                subtitle = "Nothing in the archive matched “${state.query}”.",
            )

            else -> SearchResults(state, viewModel, onOpenThread)
        }
    }
}

@Composable
private fun SearchResults(
    state: SearchUiState,
    viewModel: SearchViewModel,
    onOpenThread: (String) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        // Cached hits come first: they open instantly and work with the radio off.
        if (state.localResults.isNotEmpty()) {
            item(key = "local-header") {
                SectionHeader(
                    text = "On this device (${state.localResults.size})",
                    icon = true,
                )
            }
            items(
                items = state.localResults,
                key = { "local-${it.messageId}" },
                contentType = { "local-hit" },
            ) { message ->
                LocalHitRow(
                    subject = message.subject,
                    author = message.authorDisplay,
                    epochMillis = message.dateEpochMillis,
                    onClick = { onOpenThread(message.threadRootId) },
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.5.dp,
                )
            }
        }

        if (state.remoteResults.isNotEmpty()) {
            item(key = "remote-header") {
                SectionHeader(text = "From the archive", icon = false)
            }
            items(
                items = state.remoteResults,
                key = { "remote-${it.rootMessageId}" },
                contentType = { "remote-hit" },
            ) { thread: ThreadSummary ->
                ThreadRow(
                    thread = thread,
                    onClick = { onOpenThread(thread.rootMessageId) },
                    onToggleSaved = { },
                )
                HorizontalDivider(
                    color = MaterialTheme.colorScheme.outlineVariant,
                    thickness = 0.5.dp,
                )
            }
        }

        if (state.hasMore) {
            item(key = "load-more") {
                // Explicit tap rather than infinite scroll: every page is a fresh Xapian
                // query on a shared host, so paging stays user-initiated.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !state.isLoadingMore) { viewModel.loadMore() }
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isLoadingMore) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = "Load more results",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, icon: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (icon) {
            Icon(
                Icons.Default.OfflineBolt,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = if (icon) 6.dp else 0.dp),
        )
    }
}

@Composable
private fun LocalHitRow(
    subject: String,
    author: String,
    epochMillis: Long,
    onClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(subject, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
        Text(
            text = "$author · ${formatRelative(epochMillis)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
