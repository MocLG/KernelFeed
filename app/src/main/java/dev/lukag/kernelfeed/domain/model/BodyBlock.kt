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

package dev.lukag.kernelfeed.domain.model

/**
 * A message body decomposed into renderable regions.
 *
 * Producing this is pure CPU work over strings, so it runs in a use case on
 * [kotlinx.coroutines.Dispatchers.Default] and is handed to the UI already finished.
 * Composables only map blocks to styles; they never scan text.
 */
sealed interface BodyBlock {

    /** Ordinary prose. */
    data class Prose(val text: String) : BodyBlock

    /**
     * Quoted material. [depth] is the number of leading `>` markers, so nested replies
     * can be tinted progressively and collapsed independently.
     */
    data class Quote(val depth: Int, val text: String) : BodyBlock

    /** The `---`-delimited summary git emits above a patch, e.g. ` mm/slab.c | 4 ++--`. */
    data class DiffStat(val lines: List<String>, val summary: String?) : BodyBlock

    /** One file's worth of unified diff. */
    data class Diff(val file: DiffFile) : BodyBlock

    /** `Signed-off-by:` and friends, grouped so they can be rendered as chips. */
    data class Trailers(val entries: List<Trailer>) : BodyBlock

    /** Everything after the `-- ` sig delimiter; collapsed by default. */
    data class Signature(val text: String) : BodyBlock

    /** Git metadata found in `git format-patch` output before the body. */
    data class CommitMeta(val commitHash: String?, val fields: List<Pair<String, String>>) : BodyBlock
}

/** A single `Key: value` trailer line. */
data class Trailer(val key: String, val value: String) {
    val kind: TrailerKind get() = TrailerKind.of(key)
}

enum class TrailerKind {
    SIGNED_OFF_BY, REVIEWED_BY, ACKED_BY, TESTED_BY, REPORTED_BY,
    CC, FIXES, LINK, CLOSES, SUGGESTED_BY, CO_DEVELOPED_BY, OTHER;

    companion object {
        fun of(key: String): TrailerKind = when (key.lowercase().replace('_', '-')) {
            "signed-off-by" -> SIGNED_OFF_BY
            "reviewed-by" -> REVIEWED_BY
            "acked-by" -> ACKED_BY
            "tested-by" -> TESTED_BY
            "reported-by" -> REPORTED_BY
            "suggested-by" -> SUGGESTED_BY
            "co-developed-by" -> CO_DEVELOPED_BY
            "cc" -> CC
            "fixes" -> FIXES
            "link" -> LINK
            "closes" -> CLOSES
            else -> OTHER
        }
    }
}

/** One file in a unified diff, with its `diff --git`/`index`/`---`/`+++` preamble. */
data class DiffFile(
    val oldPath: String?,
    val newPath: String?,
    val preamble: List<String>,
    val hunks: List<DiffHunk>,
    val mode: DiffFileMode,
) {
    /** Best single path to show in a header row. */
    val displayPath: String
        get() = when (mode) {
            DiffFileMode.DELETED -> oldPath
            else -> newPath ?: oldPath
        } ?: "(unknown)"

    val addedLines: Int get() = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.ADDED } }
    val removedLines: Int get() = hunks.sumOf { h -> h.lines.count { it.kind == DiffLineKind.REMOVED } }
}

enum class DiffFileMode { MODIFIED, ADDED, DELETED, RENAMED, BINARY }

/**
 * A `@@ -oldStart,oldCount +newStart,newCount @@ section` chunk.
 *
 * The counts are retained because they let the parser terminate a hunk *exactly* instead
 * of guessing from line prefixes — the difference between correctly rendering a patch
 * whose commentary happens to start with `-` and mangling it.
 */
data class DiffHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    /** The function/section context git prints after the closing `@@`. */
    val section: String,
    val lines: List<DiffLine>,
) {
    val header: String
        get() = buildString {
            append("@@ -").append(oldStart)
            if (oldCount != 1) append(',').append(oldCount)
            append(" +").append(newStart)
            if (newCount != 1) append(',').append(newCount)
            append(" @@")
            if (section.isNotEmpty()) append(' ').append(section)
        }
}

data class DiffLine(val kind: DiffLineKind, val text: String)

enum class DiffLineKind { CONTEXT, ADDED, REMOVED, NO_NEWLINE }
