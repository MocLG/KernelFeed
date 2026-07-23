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

package dev.lukag.lkml.data.remote.parser

import dev.lukag.lkml.domain.model.MailingList
import dev.lukag.lkml.domain.model.MailingLists
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Parses public-inbox's `manifest.js.gz` into the list catalogue.
 *
 * The manifest is a flat object keyed by *git epoch path*, not by list:
 *
 * ```json
 * {
 *   "/rcu/git/0.git":   { "modified": 1784792506, "description": "Linux RCU subsystem development [epoch 0]" },
 *   "/rcu/git/1.git":   { "modified": 1784798471, "description": "Linux RCU subsystem development [epoch 1]" }
 * }
 * ```
 *
 * A long-lived list is split across several numbered epochs, so entries must be folded
 * down to one row per list: the newest `modified` across epochs wins, and the
 * `[epoch N]` suffix is stripped from the description. Treating each epoch as a separate
 * list would show `lkml` a dozen times.
 */
object ManifestParser {

    @Serializable
    private data class Entry(
        val modified: Long? = null,
        val description: String? = null,
    )

    private val json = Json {
        // The manifest carries fields the app has no use for (owner, fingerprint,
        // reference) and public-inbox is free to add more; ignoring them keeps a server
        // change from breaking the catalogue.
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val EPOCH_PATH = Regex("""^/(.+)/git/\d+\.git$""")
    private val EPOCH_SUFFIX = Regex("""\s*\[epoch \d+]\s*$""")

    /** Parses a gzipped manifest stream. */
    fun parseGzipped(input: InputStream): List<MailingList> =
        GZIPInputStream(input, BUFFER).use { parse(it.readBytes().decodeToString()) }

    fun parse(raw: String): List<MailingList> {
        val entries = json.decodeFromString<Map<String, Entry>>(raw)

        val folded = LinkedHashMap<String, MailingList>(entries.size)
        for ((path, entry) in entries) {
            val slug = EPOCH_PATH.find(path)?.groupValues?.get(1) ?: continue
            val description = entry.description
                ?.replace(EPOCH_SUFFIX, "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            // manifest timestamps are seconds since the epoch, not milliseconds.
            val lastActivity = (entry.modified ?: 0L) * 1000L

            val existing = folded[slug]
            if (existing == null) {
                folded[slug] = MailingList(
                    slug = slug,
                    title = description ?: slug,
                    description = description,
                    lastActivityEpochMillis = lastActivity,
                    isFeatured = slug in MailingLists.FEATURED,
                )
            } else {
                folded[slug] = existing.copy(
                    lastActivityEpochMillis = maxOf(existing.lastActivityEpochMillis, lastActivity),
                    title = existing.title.takeIf { it != slug } ?: (description ?: slug),
                    description = existing.description ?: description,
                )
            }
        }

        // `all` is browsable but absent from the manifest, so it is prepended by hand.
        return buildList(folded.size + 1) {
            add(MailingLists.ALL_ENTRY)
            addAll(folded.values.sortedByDescending { it.lastActivityEpochMillis })
        }
    }

    private const val BUFFER = 16 * 1024
}
