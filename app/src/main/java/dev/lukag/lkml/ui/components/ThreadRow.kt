package dev.lukag.lkml.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lukag.lkml.domain.model.ThreadSummary
import dev.lukag.lkml.ui.theme.LocalCodeColors
import dev.lukag.lkml.ui.util.formatRelative

/**
 * A thread in a list.
 *
 * The subject is split into its bracket tags and its actual text so the patch metadata
 * (`[PATCH v3 04/12]`) becomes a compact chip rather than eating the first line and a
 * half of every row — on a mailing list where most subjects start with one, that
 * recovers a large share of the screen.
 */
@Composable
fun ThreadRow(
    thread: ThreadSummary,
    onClick: () -> Unit,
    onToggleSaved: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Subject parsing is regex work: memoise it so scrolling never re-runs it.
    val tags = remember(thread.subject) { thread.tags }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (tags.isPatch) {
                    PatchChip(
                        label = tags.seriesLabel?.let { "PATCH $it" } ?: "PATCH",
                        highlight = tags.isCoverLetter,
                    )
                }
                if (tags.isRfc) PatchChip(label = "RFC", highlight = false)
                tags.labels.filter { it != "RFC" }.take(2).forEach { PatchChip(it, false) }
            }

            Text(
                text = tags.remainder.ifBlank { thread.subject },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                modifier = Modifier.padding(top = if (tags.isPatch || tags.isRfc) 4.dp else 0.dp),
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    text = formatRelative(thread.lastActivityEpochMillis),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (thread.messageCount > 1) {
                    Text(
                        text = "${thread.messageCount} messages",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                thread.latestAuthor?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (thread.isCached) {
                    Icon(
                        Icons.Default.OfflinePin,
                        contentDescription = "Available offline",
                        modifier = Modifier.size(14.dp),
                        tint = LocalCodeColors.current.addedFg,
                    )
                }
            }
        }

        IconButton(onClick = onToggleSaved) {
            Icon(
                imageVector = if (thread.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                contentDescription = if (thread.isSaved) "Remove from saved" else "Save for offline",
                tint = if (thread.isSaved) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PatchChip(label: String, highlight: Boolean) {
    val container = if (highlight) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    Surface(
        color = container,
        shape = MaterialTheme.shapes.extraSmall,
        contentColor = if (highlight) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.Unspecified,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
        )
    }
}
