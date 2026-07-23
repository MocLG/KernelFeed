package dev.lukag.lkml.domain.model

/**
 * A thread as it appears in a list — the unit the feed and search screens deal in.
 *
 * Deliberately cheap: populated from lore's topic index (one HTTP round trip for ~100
 * threads) without touching message bodies. Bodies arrive only when the user opens the
 * thread, at which point the whole thread is fetched as a single `t.mbox.gz`.
 */
data class ThreadSummary(
    val rootMessageId: String,
    val subject: String,
    val lastActivityEpochMillis: Long,
    /** Reply count as reported by lore; `-1` when the index did not state one. */
    val messageCount: Int,
    val latestAuthor: String?,
    /** Set when the user pinned the thread for offline reading. */
    val isSaved: Boolean = false,
    /** Set once the full mbox has been downloaded and parsed into Room. */
    val isCached: Boolean = false,
    /** Which archive this thread came from; used to build its mbox and permalink URLs. */
    val sourceList: String = MailingLists.ALL,
) {
    /** Patch series and version, e.g. `[PATCH v3 04/12]`, parsed out of the subject. */
    val tags: SubjectTags get() = SubjectTags.parse(subject)

    /** Subject with the leading bracket tags removed, for a cleaner list row. */
    val cleanSubject: String get() = tags.remainder
}

/**
 * The bracketed prefix convention used on kernel lists, e.g.
 * `[PATCH v3 04/12] mm: fix the thing` or `[RFC PATCH] ...`.
 */
data class SubjectTags(
    val isPatch: Boolean,
    val isRfc: Boolean,
    val version: Int?,
    val seriesIndex: Int?,
    val seriesTotal: Int?,
    val labels: List<String>,
    val remainder: String,
) {
    /** `v3 4/12`, or null when there is nothing series-like to show. */
    val seriesLabel: String?
        get() {
            val v = version?.let { "v$it" }
            val n = if (seriesIndex != null && seriesTotal != null) "$seriesIndex/$seriesTotal" else null
            return listOfNotNull(v, n).takeIf { it.isNotEmpty() }?.joinToString(" ")
        }

    /** True for the `00/NN` cover letter of a series. */
    val isCoverLetter: Boolean get() = seriesIndex == 0 && seriesTotal != null

    companion object {
        private val BRACKET = Regex("""^\s*\[([^\[\]]*)]\s*""")
        private val VERSION = Regex("""^v(\d+)$""", RegexOption.IGNORE_CASE)
        private val SERIES = Regex("""^(\d+)\s*/\s*(\d+)$""")
        /** `Re:`, `RE:`, `Re[2]:`, and the `Aw:`/`Fwd:` variants seen on the list. */
        private val REPLY_PREFIX = Regex("""^\s*(re|aw|fwd|fw)(\[\d+])?\s*:\s*""", RegexOption.IGNORE_CASE)

        fun parse(subject: String): SubjectTags {
            var rest = subject
            // Strip any number of stacked reply prefixes before looking for tags.
            while (true) {
                val stripped = rest.replaceFirst(REPLY_PREFIX, "")
                if (stripped == rest) break
                rest = stripped
            }

            var isPatch = false
            var isRfc = false
            var version: Int? = null
            var index: Int? = null
            var total: Int? = null
            val labels = mutableListOf<String>()

            while (true) {
                val m = BRACKET.find(rest) ?: break
                rest = rest.removeRange(m.range)
                for (token in m.groupValues[1].split(' ', ',').map { it.trim() }.filter { it.isNotEmpty() }) {
                    when {
                        token.equals("PATCH", true) -> isPatch = true
                        token.equals("RFC", true) -> { isRfc = true; labels += "RFC" }
                        VERSION.matches(token) -> version = VERSION.find(token)!!.groupValues[1].toIntOrNull()
                        SERIES.matches(token) -> SERIES.find(token)!!.let {
                            index = it.groupValues[1].toIntOrNull()
                            total = it.groupValues[2].toIntOrNull()
                        }
                        else -> labels += token
                    }
                }
            }
            return SubjectTags(isPatch, isRfc, version, index, total, labels, rest.trim())
        }
    }
}
