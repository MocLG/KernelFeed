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

package dev.lukag.lkml.data.local

import androidx.room.TypeConverter

/**
 * `References` is the only collection stored inline.
 *
 * Newline-joining rather than JSON: Message-IDs cannot contain a newline (RFC 5322 folds
 * them away), the encoding is unambiguous, and it avoids a JSON dependency plus a parse
 * on every row read in a list that can be thousands of rows long.
 */
object Converters {

    @TypeConverter
    @JvmStatic
    fun fromStringList(value: List<String>?): String = value.orEmpty().joinToString("\n")

    @TypeConverter
    @JvmStatic
    fun toStringList(value: String?): List<String> =
        value?.takeIf { it.isNotBlank() }?.split('\n')?.filter { it.isNotBlank() }.orEmpty()
}
