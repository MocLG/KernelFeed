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

package dev.lukag.lkml.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.lukag.lkml.domain.model.DiffFile
import dev.lukag.lkml.domain.model.DiffFileMode
import dev.lukag.lkml.ui.render.DiffRenderer
import dev.lukag.lkml.ui.render.RenderedDiff
import dev.lukag.lkml.ui.theme.CodeTypography
import dev.lukag.lkml.ui.theme.LocalCodeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders a rendered diff off the main thread.
 *
 * `produceState` keyed on the file, palette and budget means the (potentially large)
 * string building happens on `Dispatchers.Default` and its result is cached across
 * recompositions and scroll passes. The composable itself never touches diff text.
 */
@Composable
private fun rememberRenderedDiff(
    file: DiffFile,
    lineBudget: Int,
): State<RenderedDiff?> {
    val colors = LocalCodeColors.current
    return produceState<RenderedDiff?>(initialValue = null, file, colors, lineBudget) {
        value = withContext(Dispatchers.Default) {
            DiffRenderer.render(file, colors, lineBudget)
        }
    }
}

@Composable
fun DiffBlock(
    file: DiffFile,
    modifier: Modifier = Modifier,
) {
    val codeColors = LocalCodeColors.current
    var expanded by rememberSaveable(file.displayPath) { mutableStateOf(true) }
    var budget by rememberSaveable(file.displayPath) {
        mutableIntStateOf(DiffRenderer.DEFAULT_LINE_BUDGET)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, codeColors.diffBorder, RoundedCornerShape(8.dp))
            .background(codeColors.diffSurface, RoundedCornerShape(8.dp)),
    ) {
        DiffFileHeader(
            file = file,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )

        AnimatedVisibility(visible = expanded) {
            Column {
                val rendered by rememberRenderedDiff(file, budget)
                val diff = rendered
                if (diff == null) {
                    // One frame at most; a placeholder row keeps the card from jumping.
                    Text(
                        text = "…",
                        style = CodeTypography.mono,
                        color = codeColors.metaFg,
                        modifier = Modifier.padding(8.dp),
                    )
                } else {
                    DiffBody(diff)
                    if (diff.truncated) {
                        TextButton(onClick = { budget += DiffRenderer.DEFAULT_LINE_BUDGET * 4 }) {
                            Text("Show more of this file")
                        }
                    }
                }
            }
        }
    }
}

/**
 * The gutter is a sibling of the scrollable code, not part of it, so line numbers stay
 * visible while the user pans a long line horizontally.
 */
@Composable
private fun DiffBody(diff: RenderedDiff) {
    val colors = LocalCodeColors.current
    val scroll = rememberScrollState()

    Row(Modifier.fillMaxWidth()) {
        Text(
            text = diff.gutter,
            style = CodeTypography.monoSmall,
            softWrap = false,
            modifier = Modifier
                .background(colors.diffSurface)
                .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
        )
        Box(
            Modifier
                .weight(1f)
                .horizontalScroll(scroll),
        ) {
            Text(
                text = diff.code,
                style = CodeTypography.mono,
                softWrap = false,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp, end = 8.dp),
            )
        }
    }
}

@Composable
private fun DiffFileHeader(
    file: DiffFile,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val colors = LocalCodeColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(colors.hunkBg, RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = if (expanded) "Collapse file" else "Expand file",
            tint = colors.metaFg,
            modifier = Modifier.padding(end = 2.dp),
        )
        Text(
            text = file.displayPath,
            style = CodeTypography.monoSmall,
            color = colors.pathFg,
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        DiffStatBadge(file)
    }
}

@Composable
private fun DiffStatBadge(file: DiffFile) {
    val colors = LocalCodeColors.current
    val label = remember(file) {
        when (file.mode) {
            DiffFileMode.BINARY -> AnnotatedString("binary")
            DiffFileMode.ADDED -> AnnotatedString("new  +${file.addedLines}")
            DiffFileMode.DELETED -> AnnotatedString("del  −${file.removedLines}")
            else -> AnnotatedString("+${file.addedLines} −${file.removedLines}")
        }
    }
    Text(
        text = label,
        style = CodeTypography.monoSmall,
        color = when (file.mode) {
            DiffFileMode.DELETED -> colors.removedFg
            DiffFileMode.ADDED -> colors.addedFg
            else -> colors.metaFg
        },
    )
}

@Composable
fun DiffStatBlock(lines: List<String>, summary: String?, modifier: Modifier = Modifier) {
    val colors = LocalCodeColors.current
    val rendered = remember(lines, colors) {
        lines.map { DiffRenderer.renderDiffStatLine(it, colors) }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, colors.diffBorder, RoundedCornerShape(8.dp))
            .padding(8.dp),
    ) {
        val scroll = rememberScrollState()
        Column(Modifier.horizontalScroll(scroll)) {
            rendered.forEach { line ->
                Text(line, style = CodeTypography.monoSmall, softWrap = false)
            }
        }
        if (summary != null) {
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = colors.metaFg,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
