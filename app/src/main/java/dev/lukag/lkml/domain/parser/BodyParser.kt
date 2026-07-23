package dev.lukag.lkml.domain.parser

import dev.lukag.lkml.domain.model.BodyBlock
import dev.lukag.lkml.domain.model.Trailer

/**
 * Splits a plain-text mail body into [BodyBlock]s for rendering.
 *
 * Order of precedence matters and is deliberate:
 *
 *  1. **Signature** (`-- ` on its own line) terminates everything after it.
 *  2. **Quotes** win over diffs. A quoted patch (`> +foo`) is quoted material being
 *     discussed, not a patch to apply, and rendering it as a live diff would be wrong.
 *  3. **Diffs** win over prose, delegated to [DiffParser] which is count-driven.
 *  4. **Diffstat** is recognised only in its git-canonical position, after a lone `---`.
 *  5. **Trailers** are only grouped when they form a contiguous run, so a `Cc:` mentioned
 *     mid-sentence stays prose.
 *
 * Pure and single-pass over the line array; safe to call on [kotlinx.coroutines.Dispatchers.Default].
 */
object BodyParser {

    private val QUOTE = Regex("""^\s{0,3}(>+)\s?""")
    private val DIFFSTAT_ENTRY = Regex("""^\s+\S.*\s+\|\s+(\d+|Bin)\s*[+\-0-9 >|]*$""")
    private val DIFFSTAT_SUMMARY = Regex("""^\s*\d+ files? changed(,.*)?$""")
    private val TRAILER = Regex(
        """^(Signed-off-by|Reviewed-by|Acked-by|Tested-by|Reported-by|Suggested-by|Co-developed-by|Co-authored-by|Reported-and-tested-by|Reviewed-and-tested-by|Debugged-by|Analyzed-by|Originally-by|Requested-by|Cc|Fixes|Link|Closes|BugLink|Reference|Message-Id|Message-ID)\s*:\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val COMMIT_HASH = Regex("""^From ([0-9a-f]{7,40}) """)
    private val CUT_HERE = Regex("""^-{2,}\s*8<\s*-{2,}""")

    fun parse(body: String): List<BodyBlock> {
        if (body.isBlank()) return emptyList()
        val lines = body.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<BodyBlock>()
        var i = 0

        // `git format-patch` output pasted verbatim starts with a From <sha> line.
        COMMIT_HASH.find(lines.getOrElse(0) { "" })?.let { m ->
            blocks += BodyBlock.CommitMeta(m.groupValues[1], emptyList())
            i = 1
        }

        val prose = StringBuilder()

        fun flushProse() {
            val text = prose.toString().trim('\n')
            if (text.isNotBlank()) blocks += BodyBlock.Prose(text)
            prose.setLength(0)
        }

        while (i < lines.size) {
            val line = lines[i]

            // 1. Signature delimiter — everything after belongs to the signature.
            if (line == "-- " || line == "--") {
                flushProse()
                val sig = lines.subList(minOf(i + 1, lines.size), lines.size)
                    .joinToString("\n").trim('\n')
                if (sig.isNotBlank()) blocks += BodyBlock.Signature(sig)
                return blocks
            }

            // 2. Quote run — consume every consecutive quoted line at the same depth.
            val q = QUOTE.find(line)
            if (q != null) {
                flushProse()
                i = consumeQuote(lines, i, blocks)
                continue
            }

            // 3. Diffstat, only in its canonical position after a bare `---`.
            if ((line == "---" || CUT_HERE.matches(line)) && isDiffStatAhead(lines, i + 1)) {
                flushProse()
                i = consumeDiffStat(lines, i + 1, blocks)
                continue
            }

            // 4. Diff.
            if (DiffParser.looksLikeDiffStart(lines, i)) {
                val parsed = DiffParser.parseFileAt(lines, i)
                if (parsed != null) {
                    flushProse()
                    blocks += BodyBlock.Diff(parsed.first)
                    i = parsed.second
                    continue
                }
            }

            // 5. Trailer run.
            if (TRAILER.matches(line) && isTrailerRun(lines, i)) {
                flushProse()
                i = consumeTrailers(lines, i, blocks)
                continue
            }

            prose.append(line).append('\n')
            i++
        }

        flushProse()
        return blocks
    }

    private fun consumeQuote(lines: List<String>, start: Int, out: MutableList<BodyBlock>): Int {
        val depth = quoteDepth(lines[start])
        val text = StringBuilder()
        var i = start
        while (i < lines.size) {
            val d = quoteDepth(lines[i])
            // A blank line inside a quote block is usually written as a bare "" by mailers;
            // keep it only when the quote continues afterwards, so trailing gaps don't leak in.
            if (d == 0 && lines[i].isBlank() && quoteDepth(lines.getOrElse(i + 1) { "" }) == depth) {
                text.append('\n'); i++; continue
            }
            if (d != depth) break
            text.append(lines[i].replaceFirst(QUOTE, "")).append('\n')
            i++
        }
        val body = text.toString().trim('\n')
        if (body.isNotEmpty()) out += BodyBlock.Quote(depth, body)
        return if (i == start) start + 1 else i
    }

    private fun quoteDepth(line: String): Int = QUOTE.find(line)?.groupValues?.get(1)?.length ?: 0

    private fun isDiffStatAhead(lines: List<String>, from: Int): Boolean {
        var i = from
        var seen = 0
        while (i < lines.size && i < from + 4 && lines[i].isBlank()) i++
        while (i < lines.size && DIFFSTAT_ENTRY.matches(lines[i])) { seen++; i++ }
        return seen > 0 && i < lines.size && DIFFSTAT_SUMMARY.matches(lines[i])
    }

    private fun consumeDiffStat(lines: List<String>, from: Int, out: MutableList<BodyBlock>): Int {
        var i = from
        val entries = mutableListOf<String>()
        while (i < lines.size && lines[i].isBlank()) i++
        while (i < lines.size && DIFFSTAT_ENTRY.matches(lines[i])) { entries += lines[i]; i++ }
        val summary = if (i < lines.size && DIFFSTAT_SUMMARY.matches(lines[i])) lines[i++].trim() else null
        out += BodyBlock.DiffStat(entries, summary)
        return i
    }

    /** A trailer run must be at least one line and not be interrupted by prose. */
    private fun isTrailerRun(lines: List<String>, start: Int): Boolean {
        // Guard against a single `Link: ...` inside a sentence: require either the previous
        // line to be blank/a trailer, or the next line to also be a trailer.
        val prev = lines.getOrNull(start - 1)
        val next = lines.getOrNull(start + 1)
        return prev == null || prev.isBlank() || TRAILER.matches(prev) ||
            (next != null && TRAILER.matches(next))
    }

    private fun consumeTrailers(lines: List<String>, start: Int, out: MutableList<BodyBlock>): Int {
        var i = start
        val entries = mutableListOf<Trailer>()
        while (i < lines.size) {
            val m = TRAILER.find(lines[i])
            if (m != null) {
                entries += Trailer(m.groupValues[1], m.groupValues[2].trim())
                i++
            } else if (lines[i].isBlank() && i + 1 < lines.size && TRAILER.matches(lines[i + 1])) {
                i++ // Blank line between trailer groups keeps the run alive.
            } else {
                break
            }
        }
        if (entries.isNotEmpty()) out += BodyBlock.Trailers(entries)
        return if (i == start) start + 1 else i
    }
}
