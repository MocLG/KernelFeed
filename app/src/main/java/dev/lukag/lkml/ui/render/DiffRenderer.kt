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

package dev.lukag.lkml.ui.render

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import dev.lukag.lkml.domain.model.DiffFile
import dev.lukag.lkml.domain.model.DiffHunk
import dev.lukag.lkml.domain.model.DiffLineKind
import dev.lukag.lkml.ui.theme.CodeColors

/**
 * A diff file rendered into exactly two [AnnotatedString]s.
 *
 * The gutter and the code are separate strings so the gutter can stay pinned while the
 * code scrolls horizontally — long kernel lines routinely exceed a phone's width, and a
 * line-number column that scrolls off-screen is worse than none.
 *
 * Everything is pre-styled into two strings rather than emitted as one composable per
 * line. A 4,000-line patch would otherwise mean 4,000 `Text` nodes, which no amount of
 * lazy layout makes cheap; as two strings it is two text nodes and one layout pass.
 */
data class RenderedDiff(
    val gutter: AnnotatedString,
    val code: AnnotatedString,
    val lineCount: Int,
    val truncated: Boolean,
)

object DiffRenderer {

    /** Above this, a file is rendered truncated until the user asks for the rest. */
    const val DEFAULT_LINE_BUDGET = 400

    /**
     * Renders [file] with [colors].
     *
     * Pure and free of Android dependencies, so it is safe to call from
     * `Dispatchers.Default` — which callers do, via `rememberRenderedDiff`.
     */
    fun render(
        file: DiffFile,
        colors: CodeColors,
        lineBudget: Int = DEFAULT_LINE_BUDGET,
    ): RenderedDiff {
        // Pad every row to a common width so the row backgrounds form unbroken bars
        // instead of stopping ragged at each line's last character.
        val width = maxOf(file.widestLine(), 1)
        val gutterWidth = file.widestLineNumber()

        var emitted = 0
        var truncated = false

        val gutter = StringBuilder()
        val gutterStyles = mutableListOf<Triple<Int, Int, SpanStyle>>()
        val code = StringBuilder()
        val codeStyles = mutableListOf<Triple<Int, Int, SpanStyle>>()

        fun row(
            gutterText: String,
            codeText: String,
            gutterStyle: SpanStyle,
            codeStyle: SpanStyle,
        ) {
            val gStart = gutter.length
            gutter.append(gutterText.padStart(gutterWidth * 2 + 1)).append('\n')
            gutterStyles += Triple(gStart, gutter.length, gutterStyle)

            val cStart = code.length
            code.append(codeText.padEnd(width)).append('\n')
            codeStyles += Triple(cStart, code.length, codeStyle)
            emitted++
        }

        outer@ for (hunk in file.hunks) {
            row(
                gutterText = "",
                codeText = hunk.header,
                gutterStyle = SpanStyle(background = colors.hunkBg),
                codeStyle = SpanStyle(
                    color = colors.hunkFg,
                    background = colors.hunkBg,
                    fontWeight = FontWeight.Medium,
                ),
            )

            var oldNo = hunk.oldStart
            var newNo = hunk.newStart

            for (line in hunk.lines) {
                if (emitted >= lineBudget) { truncated = true; break@outer }
                when (line.kind) {
                    DiffLineKind.ADDED -> {
                        row(
                            gutterText = "${" ".repeat(gutterWidth)} ${newNo++}",
                            codeText = "+" + line.text,
                            gutterStyle = SpanStyle(color = colors.lineNumberFg, background = colors.addedBg),
                            codeStyle = SpanStyle(color = colors.addedFg, background = colors.addedBg),
                        )
                    }
                    DiffLineKind.REMOVED -> {
                        row(
                            gutterText = "${oldNo++} ${" ".repeat(gutterWidth)}",
                            codeText = "-" + line.text,
                            gutterStyle = SpanStyle(color = colors.lineNumberFg, background = colors.removedBg),
                            codeStyle = SpanStyle(color = colors.removedFg, background = colors.removedBg),
                        )
                    }
                    DiffLineKind.CONTEXT -> {
                        row(
                            gutterText = "${oldNo++} ${newNo++}",
                            codeText = " " + line.text,
                            gutterStyle = SpanStyle(color = colors.lineNumberFg),
                            codeStyle = SpanStyle(color = colors.contextFg),
                        )
                    }
                    DiffLineKind.NO_NEWLINE -> {
                        row(
                            gutterText = "",
                            codeText = "\\ " + line.text,
                            gutterStyle = SpanStyle(color = colors.lineNumberFg),
                            codeStyle = SpanStyle(color = colors.metaFg),
                        )
                    }
                }
            }
        }

        return RenderedDiff(
            gutter = buildAnnotated(gutter, gutterStyles),
            code = buildAnnotated(code, codeStyles),
            lineCount = emitted,
            truncated = truncated,
        )
    }

    /** Right-aligned gutter numbers need the width of the largest line number in the file. */
    private fun DiffFile.widestLineNumber(): Int =
        hunks.maxOfOrNull { h ->
            maxOf(h.oldStart + h.oldCount, h.newStart + h.newCount).toString().length
        } ?: 3

    private fun DiffFile.widestLine(): Int =
        hunks.maxOfOrNull { h ->
            maxOf(
                h.header.length,
                h.lines.maxOfOrNull { it.text.length + 1 } ?: 0,
            )
        } ?: 0

    private fun buildAnnotated(
        text: StringBuilder,
        styles: List<Triple<Int, Int, SpanStyle>>,
    ): AnnotatedString = AnnotatedString(
        text = text.toString(),
        spanStyles = styles.map { (start, end, style) ->
            AnnotatedString.Range(style, start, end)
        },
    )

    /** The `diff --git` / `index` preamble, rendered as dimmed metadata. */
    fun renderPreamble(file: DiffFile, colors: CodeColors): AnnotatedString = buildAnnotatedString {
        file.preamble.forEachIndexed { i, line ->
            withStyle(SpanStyle(color = colors.metaFg)) {
                append(line)
            }
            if (i != file.preamble.lastIndex) append('\n')
        }
    }

    /** `mm/slab.c | 4 ++--` with the plus/minus bar coloured. */
    fun renderDiffStatLine(line: String, colors: CodeColors): AnnotatedString = buildAnnotatedString {
        val bar = line.indexOfFirst { it == '+' || it == '-' }
        if (bar < 0) {
            withStyle(SpanStyle(color = colors.contextFg)) { append(line) }
            return@buildAnnotatedString
        }
        withStyle(SpanStyle(color = colors.contextFg)) { append(line.substring(0, bar)) }
        for (c in line.substring(bar)) {
            when (c) {
                '+' -> withStyle(SpanStyle(color = colors.addedFg)) { append(c) }
                '-' -> withStyle(SpanStyle(color = colors.removedFg)) { append(c) }
                else -> withStyle(SpanStyle(color = colors.metaFg)) { append(c) }
            }
        }
    }

    /** Hunk headers repeated in a jump list, e.g. for a per-file table of contents. */
    fun hunkLabel(hunk: DiffHunk): String =
        hunk.section.ifBlank { "@@ ${hunk.newStart}" }
}
