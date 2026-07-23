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

package dev.lukag.kernelfeed.domain.parser

import dev.lukag.kernelfeed.domain.model.DiffFile
import dev.lukag.kernelfeed.domain.model.DiffFileMode
import dev.lukag.kernelfeed.domain.model.DiffHunk
import dev.lukag.kernelfeed.domain.model.DiffLine
import dev.lukag.kernelfeed.domain.model.DiffLineKind

/**
 * Unified-diff parser for patches posted inline in mail bodies.
 *
 * The defining constraint is that a mail body is *not* a well-formed patch file: a diff
 * is embedded in prose, may be truncated mid-hunk, may be followed by a signature, and
 * the surrounding commentary frequently contains lines that start with `-` or `+`.
 *
 * The parser therefore never guesses where a hunk ends from line prefixes. It reads the
 * `@@ -a,b +c,d @@` counts and consumes exactly that many old/new lines. When the counts
 * run out, the hunk is over — so a reply that continues `- and another thing` right after
 * a patch is rendered as prose rather than swallowed as a deletion.
 *
 * All functions here are pure and allocation-conscious; callers run them off the main
 * thread via `ParseMessageBodyUseCase`.
 */
object DiffParser {

    private val HUNK_HEADER = Regex("""^@@+ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@+(?: (.*))?$""")
    private val DIFF_GIT = Regex("""^diff --git ("?[abciow]/.*"?) ("?[abciow]/.*"?)$""")
    private val DIFF_GIT_LOOSE = Regex("""^diff --git .+$""")

    /** Preamble keywords git emits between `diff --git` and the first `---`/`@@`. */
    private val PREAMBLE_PREFIXES = listOf(
        "index ", "new file mode ", "deleted file mode ", "old mode ", "new mode ",
        "similarity index ", "dissimilarity index ", "rename from ", "rename to ",
        "copy from ", "copy to ", "Binary files ", "GIT binary patch",
    )

    /**
     * True when [line] could begin a diff. Used by the body segmenter to decide when to
     * hand control over to [parseFileAt].
     *
     * A bare `--- path` only counts when the *next* line is a matching `+++ path`,
     * because `---` alone is also git's diffstat separator and a common prose divider.
     */
    fun looksLikeDiffStart(lines: List<String>, i: Int): Boolean {
        val line = lines[i]
        return when {
            DIFF_GIT_LOOSE.matches(line) -> true
            line.startsWith("--- ") && i + 1 < lines.size && lines[i + 1].startsWith("+++ ") -> true
            HUNK_HEADER.matches(line) -> true
            else -> false
        }
    }

    /**
     * Parses one file's diff beginning at [start].
     *
     * Returns the parsed [DiffFile] and the index of the first line *after* it, or null
     * when the region turned out not to be a diff after all (so the caller can fall back
     * to treating the line as prose).
     */
    fun parseFileAt(lines: List<String>, start: Int): Pair<DiffFile, Int>? {
        var i = start
        val preamble = mutableListOf<String>()
        var oldPath: String? = null
        var newPath: String? = null
        var mode = DiffFileMode.MODIFIED

        DIFF_GIT.find(lines[i])?.let { m ->
            oldPath = stripPathPrefix(m.groupValues[1])
            newPath = stripPathPrefix(m.groupValues[2])
        }
        if (DIFF_GIT_LOOSE.matches(lines[i])) {
            preamble += lines[i]
            i++
            // Consume metadata lines until the ---/+++ pair or the first hunk.
            while (i < lines.size && PREAMBLE_PREFIXES.any { lines[i].startsWith(it) }) {
                val l = lines[i]
                when {
                    l.startsWith("new file mode") -> mode = DiffFileMode.ADDED
                    l.startsWith("deleted file mode") -> mode = DiffFileMode.DELETED
                    l.startsWith("rename ") -> mode = DiffFileMode.RENAMED
                    l.startsWith("Binary files") || l.startsWith("GIT binary patch") ->
                        mode = DiffFileMode.BINARY
                }
                preamble += l
                i++
            }
        }

        // The ---/+++ path pair. Present for text diffs, absent for binary ones.
        if (i + 1 < lines.size && lines[i].startsWith("--- ") && lines[i + 1].startsWith("+++ ")) {
            val from = lines[i].removePrefix("--- ").trim()
            val to = lines[i + 1].removePrefix("+++ ").trim()
            if (from == "/dev/null") mode = DiffFileMode.ADDED
            if (to == "/dev/null") mode = DiffFileMode.DELETED
            oldPath = if (from == "/dev/null") null else stripPathPrefix(from)
            newPath = if (to == "/dev/null") null else stripPathPrefix(to)
            preamble += lines[i]
            preamble += lines[i + 1]
            i += 2
        }

        val hunks = mutableListOf<DiffHunk>()
        while (i < lines.size) {
            val parsed = parseHunkAt(lines, i) ?: break
            hunks += parsed.first
            i = parsed.second
        }

        // A "diff" with neither hunks nor a binary marker is a false positive.
        if (hunks.isEmpty() && mode != DiffFileMode.BINARY && preamble.isEmpty()) return null

        return DiffFile(oldPath, newPath, preamble, hunks, mode) to i
    }

    private fun parseHunkAt(lines: List<String>, start: Int): Pair<DiffHunk, Int>? {
        val m = HUNK_HEADER.find(lines[start]) ?: return null
        val oldStart = m.groupValues[1].toIntOrNull() ?: return null
        val oldCount = m.groupValues[2].ifEmpty { "1" }.toIntOrNull() ?: return null
        val newStart = m.groupValues[3].toIntOrNull() ?: return null
        val newCount = m.groupValues[4].ifEmpty { "1" }.toIntOrNull() ?: return null
        val section = m.groupValues[5]

        var i = start + 1
        var oldLeft = oldCount
        var newLeft = newCount
        val body = ArrayList<DiffLine>(oldCount + newCount)

        while (i < lines.size && (oldLeft > 0 || newLeft > 0)) {
            val line = lines[i]
            when {
                line.startsWith("+") -> {
                    if (newLeft == 0) break
                    body += DiffLine(DiffLineKind.ADDED, line.substring(1)); newLeft--
                }
                line.startsWith("-") -> {
                    if (oldLeft == 0) break
                    body += DiffLine(DiffLineKind.REMOVED, line.substring(1)); oldLeft--
                }
                line.startsWith(" ") -> {
                    if (oldLeft == 0 || newLeft == 0) break
                    body += DiffLine(DiffLineKind.CONTEXT, line.substring(1)); oldLeft--; newLeft--
                }
                // `\ No newline at end of file` belongs to the hunk but consumes no count.
                line.startsWith("\\") -> body += DiffLine(DiffLineKind.NO_NEWLINE, line.substring(1).trim())
                // Mailers strip trailing whitespace, turning an empty context line into "".
                line.isEmpty() -> {
                    if (oldLeft == 0 || newLeft == 0) break
                    body += DiffLine(DiffLineKind.CONTEXT, ""); oldLeft--; newLeft--
                }
                else -> break // Anything else means the patch was truncated or corrupted.
            }
            i++
        }

        if (body.isEmpty()) return null
        return DiffHunk(oldStart, oldCount, newStart, newCount, section, body) to i
    }

    /** `a/mm/slab.c` → `mm/slab.c`; also unquotes git's C-style quoting of odd paths. */
    private fun stripPathPrefix(raw: String): String {
        var p = raw.trim()
        if (p.length >= 2 && p.startsWith('"') && p.endsWith('"')) {
            p = p.substring(1, p.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
        // Only strip a single-letter directory prefix, never a real leading directory.
        val slash = p.indexOf('/')
        if (slash == 1 && p[0] in "abciow") p = p.substring(2)
        // Trim the trailing tab+timestamp that plain `diff -u` appends.
        val tab = p.indexOf('\t')
        if (tab >= 0) p = p.substring(0, tab)
        return p
    }
}
