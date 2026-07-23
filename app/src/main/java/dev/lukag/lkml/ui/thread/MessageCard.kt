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

package dev.lukag.lkml.ui.thread

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.UnfoldLess
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lukag.lkml.domain.model.BodyBlock
import dev.lukag.lkml.domain.model.FlatMessage
import dev.lukag.lkml.ui.components.MessageBody
import dev.lukag.lkml.ui.theme.LocalCodeColors
import dev.lukag.lkml.ui.util.formatRelative

/** Indent per reply level. Small, because kernel threads nest deeply. */
private val INDENT_STEP = 10.dp
private const val MAX_INDENT_LEVELS = 8

/**
 * One message in the thread tree.
 *
 * Indentation is capped at [MAX_INDENT_LEVELS]: LKML threads routinely nest 15+ deep, and
 * uncapped indentation would leave the deepest replies a few characters wide. Past the
 * cap the depth is shown numerically instead, so the structure is still legible.
 *
 * The body is only composed when the card is expanded, and parsing is requested lazily on
 * first expansion — so a thread of 300 messages composes 300 headers, not 300 patches.
 */
@Composable
fun MessageCard(
    item: FlatMessage,
    isExpanded: Boolean,
    blocks: List<BodyBlock>?,
    onToggleExpanded: () -> Unit,
    onToggleCollapsed: () -> Unit,
    onJumpToParent: () -> Unit,
    onRequestBody: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val codeColors = LocalCodeColors.current
    val message = item.message
    val effectiveDepth = item.depth.coerceAtMost(MAX_INDENT_LEVELS)

    LaunchedEffect(isExpanded, message.messageId) {
        if (isExpanded && blocks == null) onRequestBody()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(start = INDENT_STEP * effectiveDepth),
    ) {
        // Tree gutter: a tinted rail per depth level, giving the eye a continuous line
        // back to the parent without drawing an actual connector graph.
        if (item.depth > 0) {
            Box(
                Modifier
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(
                        codeColors.quoteAccents[(item.depth - 1) % codeColors.quoteAccents.size]
                            .copy(alpha = 0.45f),
                    ),
            )
        }

        Column(Modifier.weight(1f)) {
            MessageHeader(
                item = item,
                isExpanded = isExpanded,
                onToggleExpanded = onToggleExpanded,
                onToggleCollapsed = onToggleCollapsed,
                onJumpToParent = onJumpToParent,
            )

            AnimatedVisibility(visible = isExpanded) {
                Column(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    when {
                        blocks == null -> Text(
                            text = "Parsing…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        blocks.isEmpty() -> Text(
                            text = "(no text content)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> MessageBody(blocks)
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageHeader(
    item: FlatMessage,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onToggleCollapsed: () -> Unit,
    onJumpToParent: () -> Unit,
) {
    val message = item.message
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpanded)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        AuthorAvatar(message.authorInitial)

        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = message.authorDisplay,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = formatRelative(message.dateEpochMillis),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!isExpanded) {
                Text(
                    text = message.body.firstMeaningfulLine(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (item.depth > MAX_INDENT_LEVELS) {
                Text(
                    text = "depth ${item.depth}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (item.parentId != null) {
            IconButton(onClick = onJumpToParent, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.ArrowUpward,
                    contentDescription = "Jump to parent message",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (item.childCount > 0) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(onClick = onToggleCollapsed),
            ) {
                Text(
                    text = "${item.descendantCount}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                IconButton(onClick = onToggleCollapsed, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (item.isCollapsed) Icons.Default.UnfoldMore else Icons.Default.UnfoldLess,
                        contentDescription = if (item.isCollapsed) {
                            "Expand ${item.descendantCount} replies"
                        } else {
                            "Collapse ${item.descendantCount} replies"
                        },
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthorAvatar(initial: Char) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(26.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = initial.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

/**
 * Preview text for a collapsed message.
 *
 * Skips quoted lines and attribution lines ("On Mon, ... wrote:"), which are what a reply
 * usually opens with and which would otherwise make every collapsed row look identical.
 */
private fun String.firstMeaningfulLine(): String {
    for (raw in lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty()) continue
        if (line.startsWith(">")) continue
        if (line.endsWith("wrote:")) continue
        if (line.startsWith("On ") && line.contains(',')) continue
        return line.take(120)
    }
    return "(quoted text only)"
}

/** Collapsed-subtree placeholder shown in place of the hidden descendants. */
@Composable
fun CollapsedSubtreeRow(count: Int, onExpand: () -> Unit, depth: Int) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = INDENT_STEP * depth.coerceAtMost(MAX_INDENT_LEVELS) + 12.dp, end = 12.dp)
            .clickable(onClick = onExpand),
    ) {
        Text(
            text = "$count hidden ${if (count == 1) "reply" else "replies"} — tap to expand",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}
