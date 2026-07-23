package dev.lukag.lkml.ui.lists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lukag.lkml.domain.model.MailingList
import dev.lukag.lkml.domain.model.MailingLists
import dev.lukag.lkml.ui.components.EmptyState
import dev.lukag.lkml.ui.components.ErrorBanner
import dev.lukag.lkml.ui.components.ErrorState
import dev.lukag.lkml.ui.components.OfflineBanner
import dev.lukag.lkml.ui.util.formatRelative

/**
 * The catalogue of archived mailing lists — the app's entry point.
 *
 * lore hosts ~350 lists, most of them narrow subsystem or CI archives. Showing them
 * alphabetically would bury the dozen anyone actually opens, so the screen is ordered
 * pinned → featured → most recently active, with search covering the long tail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListCatalogScreen(
    onOpenList: (MailingList) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ListCatalogViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Find a list") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        if (state.isOffline) OfflineBanner()
        state.error?.let { error ->
            if (state.total > 0) {
                ErrorBanner(
                    error = error,
                    onRetry = viewModel::refresh,
                    onDismiss = viewModel::dismissError,
                )
            }
        }

        val error = state.error
        if (state.total == 0 && error != null && !state.isRefreshing) {
            ErrorState(error = error, onRetry = viewModel::refresh)
            return@Column
        }

        PullToRefreshBox(
            isRefreshing = state.isRefreshing,
            onRefresh = viewModel::refresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            when {
                state.isEmpty && state.isSearching -> EmptyState(
                    title = "No lists match",
                    subtitle = "Nothing in the archive is called “${state.query}”.",
                )

                state.isEmpty -> EmptyState(
                    title = "Loading the catalogue",
                    subtitle = "Pull down to fetch the list of archives from lore.kernel.org.",
                )

                else -> LazyColumn(Modifier.fillMaxSize()) {
                    if (state.pinned.isNotEmpty()) {
                        item(key = "hdr-pinned") { SectionHeader("Pinned") }
                        listSection(state.pinned, "pin", onOpenList, viewModel::togglePinned)
                    }
                    if (state.featured.isNotEmpty()) {
                        item(key = "hdr-featured") {
                            SectionHeader(if (state.isSearching) "Popular" else "Popular lists")
                        }
                        listSection(state.featured, "feat", onOpenList, viewModel::togglePinned)
                    }
                    if (state.others.isNotEmpty()) {
                        item(key = "hdr-others") {
                            SectionHeader(
                                if (state.isSearching) {
                                    "Other matches (${state.others.size})"
                                } else {
                                    "All archives (${state.others.size})"
                                },
                            )
                        }
                        listSection(state.others, "all", onOpenList, viewModel::togglePinned)
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.listSection(
    lists: List<MailingList>,
    keyPrefix: String,
    onOpenList: (MailingList) -> Unit,
    onTogglePin: (MailingList) -> Unit,
) {
    items(
        items = lists,
        // Prefixed because a list can legitimately appear in two sections while searching.
        key = { "$keyPrefix-${it.slug}" },
        contentType = { "mailing-list" },
    ) { list ->
        MailingListRow(
            list = list,
            onClick = { onOpenList(list) },
            onTogglePin = { onTogglePin(list) },
        )
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            thickness = 0.5.dp,
        )
    }
}

@Composable
private fun MailingListRow(
    list: MailingList,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val isAggregate = list.slug == MailingLists.ALL
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = list.slug,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isAggregate) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (isAggregate) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = MaterialTheme.shapes.extraSmall,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Text(
                            text = "everything",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                        )
                    }
                }
            }
            list.description?.takeIf { it != list.slug }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            // The aggregate carries a sentinel timestamp rather than a real one.
            if (!isAggregate && list.lastActivityEpochMillis > 0) {
                Text(
                    text = "active ${formatRelative(list.lastActivityEpochMillis)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        IconButton(onClick = onTogglePin) {
            Icon(
                imageVector = if (list.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                contentDescription = if (list.isPinned) "Unpin ${list.slug}" else "Pin ${list.slug}",
                modifier = Modifier.size(18.dp),
                tint = if (list.isPinned) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Small inline spinner used while the catalogue is first populated. */
@Composable
fun CatalogLoading() {
    Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
}
