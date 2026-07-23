package dev.lukag.lkml.data.remote.parser

import dev.lukag.lkml.domain.model.ThreadSummary
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One page of the topic index, plus the cursor needed to ask for the next one. */
data class TopicPage(val topics: List<ThreadSummary>, val nextCursor: String?)

/** One page of search results, plus whether the server offered a further page. */
data class SearchPage(val results: List<ThreadSummary>, val nextOffset: Int?)

/**
 * Parsers for public-inbox's HTML views.
 *
 * public-inbox emits machine-generated markup from a fixed template — anchors always
 * break the line before `href`, topic rows are always followed by a ` <date> UTC` line —
 * so targeted regexes are both sufficient and far cheaper than pulling in an HTML DOM
 * parser, which would cost a dependency and a full tree allocation per 36 KB page.
 *
 * These run on `Dispatchers.IO` inside the repository, never on the main thread.
 */
object LoreHtmlParsers {

    /**
     * A topic row: the thread-root anchor (`/T/#t`) followed by its activity line.
     * The message count is optional — single-message topics omit it.
     */
    private val TOPIC = Regex(
        """<a\s+href="([^"]+?)/T/#t">(.*?)</a>\s*\n\s*""" +
            """(\d{4}-\d{2}-\d{2})\s+(\d{1,2}:\d{2})\s+UTC\s*(?:\((\d+)\+?\s*messages?\))?""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** The `next (older)` pager link, whose `?t=` value is an opaque server cursor. */
    private val NEXT_CURSOR = Regex("""href="\?t=([^"&]+)"\s*\n?\s*rel=next""")

    /** A search hit: rank, permalink, subject, then an ` - by <author> @ <date>` line. */
    private val SEARCH_HIT = Regex(
        """(\d+)\.\s*<b><a\s+href="([^"]+?)/?">(.*?)</a></b>\s*\n\s*""" +
            """-\s*by\s+(.*?)\s+@\s+(\d{4}-\d{2}-\d{2})\s+(\d{1,2}:\d{2})\s+UTC""",
        RegexOption.DOT_MATCHES_ALL,
    )

    private val NEXT_OFFSET = Regex("""href="\?q=[^"]*?&(?:amp;)?o=(\d+)"""")

    private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm")

    fun parseTopicIndex(html: String): TopicPage {
        val topics = TOPIC.findAll(html).map { m ->
            ThreadSummary(
                rootMessageId = decodeEntities(m.groupValues[1]),
                subject = decodeEntities(stripTags(m.groupValues[2])),
                lastActivityEpochMillis = parseUtc(m.groupValues[3], m.groupValues[4]),
                // `(N+ messages)` counts the root too; its absence means a lone message.
                messageCount = m.groupValues[5].toIntOrNull() ?: 1,
                latestAuthor = null,
            )
        }.distinctBy { it.rootMessageId }.toList()

        return TopicPage(topics, NEXT_CURSOR.find(html)?.groupValues?.get(1))
    }

    fun parseSearchResults(html: String, currentOffset: Int): SearchPage {
        val hits = SEARCH_HIT.findAll(html).map { m ->
            ThreadSummary(
                rootMessageId = decodeEntities(m.groupValues[2]),
                subject = decodeEntities(stripTags(m.groupValues[3])),
                lastActivityEpochMillis = parseUtc(m.groupValues[5], m.groupValues[6]),
                messageCount = -1, // Search reports per-message hits, not thread sizes.
                latestAuthor = decodeEntities(m.groupValues[4]).trim(),
            )
        }.distinctBy { it.rootMessageId }.toList()

        val next = NEXT_OFFSET.findAll(html)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it > currentOffset }
            .minOrNull()

        return SearchPage(hits, next)
    }

    /** Detects the interstitial as a second line of defence behind the interceptor. */
    fun isBotChallenge(html: String): Boolean =
        html.contains("Making sure you", ignoreCase = true) &&
            html.contains("not a bot", ignoreCase = true)

    private fun parseUtc(date: String, time: String): Long =
        runCatching {
            LocalDateTime.parse("$date $time", DATE_FORMAT).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrDefault(0L)

    private val TAG = Regex("""<[^>]+>""")

    private fun stripTags(s: String) = TAG.replace(s, "")

    /**
     * public-inbox escapes subjects with a small, fixed set of entities (it emits numeric
     * references for anything exotic), so a full entity table is unnecessary.
     */
    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { sb.append(c); i++; continue }
            val end = s.indexOf(';', i + 1)
            if (end < 0 || end - i > 10) { sb.append(c); i++; continue }
            when (val entity = s.substring(i + 1, end)) {
                "amp" -> sb.append('&')
                "lt" -> sb.append('<')
                "gt" -> sb.append('>')
                "quot" -> sb.append('"')
                "apos" -> sb.append('\'')
                "nbsp" -> sb.append(' ')
                else -> {
                    val code = when {
                        entity.startsWith("#x") || entity.startsWith("#X") ->
                            entity.drop(2).toIntOrNull(16)
                        entity.startsWith("#") -> entity.drop(1).toIntOrNull()
                        else -> null
                    }
                    if (code != null && code in 1..0x10FFFF) sb.appendCodePoint(code)
                    else { sb.append(c); i++; continue }
                }
            }
            i = end + 1
        }
        return sb.toString()
    }
}
