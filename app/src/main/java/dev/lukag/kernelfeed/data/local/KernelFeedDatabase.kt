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

package dev.lukag.kernelfeed.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.lukag.kernelfeed.data.local.dao.MailingListDao
import dev.lukag.kernelfeed.data.local.dao.MessageDao
import dev.lukag.kernelfeed.data.local.dao.ThreadDao
import dev.lukag.kernelfeed.data.local.entity.FeedEntryEntity
import dev.lukag.kernelfeed.data.local.entity.FeedPageEntity
import dev.lukag.kernelfeed.data.local.entity.MailingListEntity
import dev.lukag.kernelfeed.data.local.entity.MessageEntity
import dev.lukag.kernelfeed.data.local.entity.MessageFtsEntity
import dev.lukag.kernelfeed.data.local.entity.ThreadEntity

@Database(
    entities = [
        ThreadEntity::class,
        MessageEntity::class,
        MessageFtsEntity::class,
        FeedEntryEntity::class,
        FeedPageEntity::class,
        MailingListEntity::class,
    ],
    // v2 introduced per-list feeds (feed_entries, mailing_lists, list-keyed feed_pages).
    // No migration is supplied: the database is a cache of a public archive, so the
    // destructive fallback costs the user a refresh and nothing else.
    version = 2,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class KernelFeedDatabase : RoomDatabase() {
    abstract fun threadDao(): ThreadDao
    abstract fun messageDao(): MessageDao
    abstract fun mailingListDao(): MailingListDao

    companion object {
        const val NAME = "kernelfeed.db"
    }
}
