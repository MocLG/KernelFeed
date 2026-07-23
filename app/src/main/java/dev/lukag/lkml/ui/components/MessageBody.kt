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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.lukag.lkml.domain.model.BodyBlock
import dev.lukag.lkml.domain.model.Trailer
import dev.lukag.lkml.domain.model.TrailerKind
import dev.lukag.lkml.ui.theme.CodeTypography
import dev.lukag.lkml.ui.theme.LocalCodeColors

/**
 * Renders a parsed message body.
 *
 * The blocks arrive already segmented from `ParseMessageBodyUseCase`, so this function
 * only maps block types to styles — it performs no scanning, splitting or regex work,
 * which is what keeps scrolling a 100-message thread smooth.
 */
@Composable
fun MessageBody(
    blocks: List<BodyBlock>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        blocks.forEach { block ->
            when (block) {
                is BodyBlock.Prose -> ProseBlock(block.text)
                is BodyBlock.Quote -> QuoteBlock(block)
                is BodyBlock.Diff -> DiffBlock(block.file)
                is BodyBlock.DiffStat -> DiffStatBlock(block.lines, block.summary)
                is BodyBlock.Trailers -> TrailerBlock(block.entries)
                is BodyBlock.Signature -> SignatureBlock(block.text)
                is BodyBlock.CommitMeta -> CommitMetaBlock(block)
            }
        }
    }
}

/**
 * Prose is rendered monospaced and unwrapped inside a horizontal scroller.
 *
 * Mail on kernel lists is hard-wrapped at 72–80 columns by convention, and the text is
 * full of aligned ASCII — tables, register maps, stack traces. Re-flowing it to the screen
 * width destroys that alignment, so the original wrapping is preserved and the rare
 * over-wide line is reachable by panning.
 */
@Composable
private fun ProseBlock(text: String) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
        Text(
            text = text,
            style = CodeTypography.mono,
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
        )
    }
}

/**
 * Quoted text, collapsed by default beyond a few lines.
 *
 * Deep quoting is the dominant source of noise in mailing-list threads; showing a
 * two-line preview with a tap to expand keeps the actual reply visible without
 * discarding the context it answers.
 */
@Composable
private fun QuoteBlock(block: BodyBlock.Quote) {
    val colors = LocalCodeColors.current
    val accent = colors.quoteAccents[(block.depth - 1).coerceAtLeast(0) % colors.quoteAccents.size]
    val lines = remember(block.text) { block.text.lines() }
    val isLong = lines.size > COLLAPSE_THRESHOLD
    var expanded by rememberSaveable(block.text.hashCode()) { mutableStateOf(!isLong) }

    val shown = remember(lines, expanded) {
        if (expanded) block.text else lines.take(PREVIEW_LINES).joinToString("\n")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // IntrinsicSize.Min lets the accent bar match the text's height exactly,
            // however many lines are currently shown, without measuring it by hand.
            .height(IntrinsicSize.Min)
            .clickable(enabled = isLong) { expanded = !expanded },
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(accent, RoundedCornerShape(2.dp)),
        )
        Column(Modifier.padding(start = 8.dp)) {
            val scroll = rememberScrollState()
            Box(Modifier.fillMaxWidth().horizontalScroll(scroll)) {
                Text(
                    text = shown,
                    style = CodeTypography.quote,
                    color = colors.quoteFg,
                    softWrap = false,
                )
            }
            if (isLong) {
                Text(
                    text = if (expanded) "Hide quote" else "… ${lines.size - PREVIEW_LINES} more quoted lines",
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun TrailerBlock(entries: List<Trailer>) {
    val colors = LocalCodeColors.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        entries.forEach { trailer ->
            Row {
                Text(
                    text = "${trailer.key}: ",
                    style = CodeTypography.monoSmall,
                    color = when (trailer.kind) {
                        TrailerKind.SIGNED_OFF_BY -> colors.addedFg
                        TrailerKind.FIXES -> colors.removedFg
                        TrailerKind.OTHER -> colors.metaFg
                        else -> colors.trailerFg
                    },
                )
                Text(
                    text = trailer.value,
                    style = CodeTypography.monoSmall,
                    color = colors.metaFg,
                )
            }
        }
    }
}

@Composable
private fun SignatureBlock(text: String) {
    val colors = LocalCodeColors.current
    var expanded by rememberSaveable(text.hashCode()) { mutableStateOf(false) }
    Column(Modifier.clickable { expanded = !expanded }) {
        Text(
            text = "-- ",
            style = CodeTypography.monoSmall,
            color = colors.metaFg,
        )
        AnimatedVisibility(visible = expanded) {
            Text(
                text = text,
                style = CodeTypography.monoSmall,
                color = colors.metaFg,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun CommitMetaBlock(block: BodyBlock.CommitMeta) {
    val colors = LocalCodeColors.current
    Surface(
        color = colors.hunkBg,
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            block.commitHash?.let {
                Text(
                    text = "commit ${it.take(12)}",
                    style = CodeTypography.monoSmall,
                    color = colors.pathFg,
                )
            }
            block.fields.forEach { (k, v) ->
                Text("$k: $v", style = CodeTypography.monoSmall, color = colors.metaFg)
            }
        }
    }
}

private const val COLLAPSE_THRESHOLD = 6
private const val PREVIEW_LINES = 2
