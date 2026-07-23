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

package dev.lukag.kernelfeed.ui.thread

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lukag.kernelfeed.domain.model.MailingLists
import dev.lukag.kernelfeed.ui.components.EmptyState
import dev.lukag.kernelfeed.ui.components.ErrorBanner
import dev.lukag.kernelfeed.ui.components.ErrorState
import dev.lukag.kernelfeed.ui.components.OfflineBanner

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(
    onBack: () -> Unit,
    onOpenInBrowser: (listSlug: String, messageId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThreadViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val appBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(appBarState)

    /**
     * Jump-to-parent.
     *
     * The ViewModel has already un-collapsed every ancestor, so by the time this runs the
     * target is guaranteed to be present in the flattened list and `indexOfFirst` cannot
     * scroll to a stale position.
     */
    LaunchedEffect(state.scrollTarget, state.flattened.size) {
        val target = state.scrollTarget ?: return@LaunchedEffect
        val index = state.flattened.indexOfFirst { it.message.messageId == target }
        if (index >= 0) {
            listState.animateScrollToItem(index)
            viewModel.consumeScrollTarget()
        }
    }

    Scaffold(
        modifier = modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = state.summary?.cleanSubject
                                ?: state.summary?.subject
                                ?: "Thread",
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                        )
                        if (state.messageCount > 0) {
                            Text(
                                text = "${state.messageCount} messages",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    val allCollapsed = remember(state.collapsed, state.flattened) {
                        state.flattened.any { it.isCollapsed }
                    }
                    IconButton(
                        onClick = {
                            if (allCollapsed) viewModel.expandAll() else viewModel.collapseAll()
                        },
                    ) {
                        Icon(
                            imageVector = if (allCollapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                            contentDescription = if (allCollapsed) "Expand all" else "Collapse all",
                        )
                    }
                    IconButton(onClick = viewModel::toggleSaved) {
                        Icon(
                            imageVector = if (state.summary?.isSaved == true) Icons.Default.Bookmark
                            else Icons.Default.BookmarkBorder,
                            contentDescription = if (state.summary?.isSaved == true) {
                                "Remove from saved"
                            } else {
                                "Save for offline"
                            },
                        )
                    }
                    IconButton(
                        onClick = {
                            onOpenInBrowser(
                                state.summary?.sourceList ?: MailingLists.AGGREGATE,
                                state.rootMessageId,
                            )
                        },
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in browser")
                    }
                    IconButton(onClick = { viewModel.load(force = true) }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reload thread")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.isOffline) OfflineBanner()
            state.error?.let { error ->
                if (state.flattened.isNotEmpty()) {
                    ErrorBanner(
                        error = error,
                        onRetry = { viewModel.load(force = true) },
                        onDismiss = viewModel::dismissError,
                    )
                }
            }

            val error = state.error
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }

                state.flattened.isEmpty() && error != null ->
                    ErrorState(error = error, onRetry = { viewModel.load(force = true) })

                state.isEmpty -> EmptyState(
                    title = "Thread unavailable",
                    subtitle = "No messages were found for this thread in the archive.",
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        items = state.flattened,
                        // Message-ID keys let Compose preserve each card's internal state
                        // (expanded diffs, quote toggles) across collapse and reflow.
                        key = { it.message.messageId },
                        contentType = { "message" },
                    ) { item ->
                        MessageCard(
                            item = item,
                            isExpanded = item.message.messageId in state.expanded,
                            blocks = state.bodies[item.message.messageId],
                            onToggleExpanded = { viewModel.toggleExpanded(item.message.messageId) },
                            onToggleCollapsed = { viewModel.toggleCollapsed(item.message.messageId) },
                            onJumpToParent = { viewModel.jumpToParent(item.message.messageId) },
                            onRequestBody = { viewModel.requestBody(item.message) },
                        )
                        if (item.isCollapsed && item.descendantCount > 0) {
                            CollapsedSubtreeRow(
                                count = item.descendantCount,
                                depth = item.depth + 1,
                                onExpand = { viewModel.toggleCollapsed(item.message.messageId) },
                            )
                        }
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            thickness = 0.5.dp,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (state.isRefreshing) {
                        item(key = "refreshing", contentType = "spinner") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                                CircularProgressIndicator(strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
}
