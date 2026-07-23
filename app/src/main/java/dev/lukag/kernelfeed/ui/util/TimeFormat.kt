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

package dev.lukag.kernelfeed.ui.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Date formatters, rebuilt when the device locale changes.
 *
 * Caching `DateTimeFormatter`s in `val`s would freeze the locale at class-initialisation
 * time, so switching the system language would leave dates formatted in the old one until
 * the process restarted. The formatters are memoised against the locale that built them
 * and rebuilt on change — cheap, and correct across a locale switch.
 */
private class Formatters(val locale: Locale) {
    val timeOnly: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)
    val dateShort: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", locale)
    val dateFull: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    val absolute: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", locale)
}

@Volatile
private var cached: Formatters? = null

private fun formatters(): Formatters {
    val locale = Locale.getDefault()
    val current = cached
    if (current != null && current.locale == locale) return current
    return Formatters(locale).also { cached = it }
}

/**
 * Mailing-list-appropriate relative time.
 *
 * Precision falls off with age deliberately: on a list where a thread's *recency* is the
 * main signal of relevance, "12 min" and "3 h" carry information, while a message from
 * March is adequately described by its date. Timestamps of `0` mean the source never
 * supplied one and are shown as such rather than as 1970.
 */
fun formatRelative(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0L) return "unknown"
    val deltaSeconds = (now - epochMillis) / 1000
    val local = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    val f = formatters()

    return when {
        abs(deltaSeconds) < 60 -> "just now"
        deltaSeconds < 3600 -> "${deltaSeconds / 60} min"
        deltaSeconds < 86_400 -> "${deltaSeconds / 3600} h"
        deltaSeconds < 172_800 -> "yesterday ${local.format(f.timeOnly)}"
        deltaSeconds < 86_400 * 300 -> local.format(f.dateShort)
        else -> local.format(f.dateFull)
    }
}

fun formatAbsolute(epochMillis: Long): String =
    if (epochMillis <= 0L) {
        "date unknown"
    } else {
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(formatters().absolute)
    }
