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

package dev.lukag.lkml.data.remote

/**
 * URL construction for public-inbox instances.
 *
 * Message-IDs are used verbatim as path segments by public-inbox, but they legally
 * contain characters that are reserved in a URL path (`/`, `?`, `#`, `%`, `[`, `]`).
 * Retrofit's default `@Path` encoder is too aggressive — it escapes `@` and `+`, which
 * public-inbox then fails to match — so URLs are built here and passed with `@Url`.
 */
object LoreUrls {

    const val BASE = "https://lore.kernel.org/"

    /** Path characters that are safe to leave as-is inside a single segment. */
    private const val SAFE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" +
        "-._~!$&'()*+,;=:@"

    fun encodeSegment(raw: String): String = buildString(raw.length + 8) {
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            if (c in SAFE) append(c) else append('%').append("%02X".format(b))
        }
    }

    /** Strips the angle brackets that appear on Message-IDs in raw mail headers. */
    fun canonicalMessageId(raw: String): String = raw.trim().removePrefix("<").removeSuffix(">").trim()

    /** The topic index: newest threads, or older ones via the `?t=` cursor from `rel=next`. */
    fun topicIndex(list: String, cursor: String?): String =
        "$BASE$list/" + if (cursor.isNullOrBlank()) "" else "?t=$cursor"

    /**
     * Search. The plain index ignores `?o=`, but search honours it in steps of the
     * server page size (200), which is why paging differs between the two screens.
     */
    fun search(list: String, query: String, offset: Int): String {
        val q = encodeQuery(query)
        return "$BASE$list/?q=$q" + if (offset > 0) "&o=$offset" else ""
    }

    /** The whole thread as gzipped mboxrd — the offline unit. */
    fun threadMbox(list: String, rootMessageId: String): String =
        "$BASE$list/${encodeSegment(canonicalMessageId(rootMessageId))}/t.mbox.gz"

    /** A single message as raw RFC 5322. */
    fun rawMessage(list: String, messageId: String): String =
        "$BASE$list/${encodeSegment(canonicalMessageId(messageId))}/raw"

    /** Web permalink, for share sheets and "open in browser". */
    fun permalink(list: String, messageId: String): String =
        "$BASE$list/${encodeSegment(canonicalMessageId(messageId))}/"

    fun newAtom(list: String): String = "$BASE$list/new.atom"

    /**
     * The public-inbox catalogue of every archived list, gzipped JSON.
     *
     * Served as `application/gzip`, so it needs explicit decompression rather than
     * OkHttp's transport-level handling.
     */
    fun manifest(): String = "${BASE}manifest.js.gz"

    private fun encodeQuery(raw: String): String = buildString(raw.length + 8) {
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            when {
                c in SAFE && c != '+' && c != '&' && c != '=' -> append(c)
                c == ' ' -> append('+')
                else -> append('%').append("%02X".format(b))
            }
        }
    }
}
