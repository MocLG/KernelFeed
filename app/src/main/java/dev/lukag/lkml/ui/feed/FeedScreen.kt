package dev.lukag.lkml.ui.feed

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.snapshotFlow
import dev.lukag.lkml.domain.model.ThreadSummary
import dev.lukag.lkml.ui.components.EmptyState
import dev.lukag.lkml.ui.components.ErrorBanner
import dev.lukag.lkml.ui.components.ErrorState
import dev.lukag.lkml.ui.components.OfflineBanner
import dev.lukag.lkml.ui.components.ThreadRow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    onOpenThread: (ThreadSummary) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FeedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    /**
     * Prefetch trigger.
     *
     * `snapshotFlow` over a `derivedStateOf` means this only recomputes when the last
     * visible index actually changes, and only emits when the threshold is crossed —
     * unlike reading the scroll position in composition, which would recompose the whole
     * screen on every scroll frame.
     */
    val shouldLoadMore by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            total > 0 && last >= total - PREFETCH_DISTANCE
        }
    }

    LaunchedEffect(listState) {
        snapshotFlow { shouldLoadMore }
            .distinctUntilChanged()
            .filter { it }
            .collect { viewModel.loadMore() }
    }

    Column(modifier.fillMaxSize()) {
        if (state.isOffline) OfflineBanner()
        state.error?.let { error ->
            if (state.threads.isNotEmpty()) {
                ErrorBanner(
                    error = error,
                    onRetry = { viewModel.refresh() },
                    onDismiss = viewModel::dismissError,
                )
            }
        }

        val error = state.error
        if (state.threads.isEmpty() && error != null && !state.isRefreshing) {
            ErrorState(error = error, onRetry = { viewModel.refresh() })
            return@Column
        }

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = { viewModel.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.showEmptyState) {
                EmptyState(
                    title = "No threads yet",
                    subtitle = "Pull down to fetch the latest activity from lore.kernel.org.",
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // Message-ID is globally unique and stable, so Compose can reuse and
                    // reorder rows correctly across refreshes instead of rebuilding them.
                    items(
                        items = state.threads,
                        key = { it.rootMessageId },
                        contentType = { "thread" },
                    ) { thread ->
                        ThreadRow(
                            thread = thread,
                            onClick = { onOpenThread(thread) },
                            onToggleSaved = { viewModel.toggleSaved(thread) },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            thickness = 0.5.dp,
                        )
                    }

                    if (state.isLoadingMore) {
                        item(key = "loading-more", contentType = "spinner") {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                    } else if (!state.hasMore && state.threads.isNotEmpty()) {
                        item(key = "end", contentType = "footer") {
                            Text(
                                text = "End of the archive index",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.fillMaxWidth().padding(24.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private const val PREFETCH_DISTANCE = 8
