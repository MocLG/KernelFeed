package dev.lukag.lkml.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.lukag.lkml.data.local.dao.MailingListDao
import dev.lukag.lkml.data.local.dao.MessageDao
import dev.lukag.lkml.data.local.dao.ThreadDao
import dev.lukag.lkml.data.local.entity.FeedEntryEntity
import dev.lukag.lkml.data.local.entity.FeedPageEntity
import dev.lukag.lkml.data.local.entity.MailingListEntity
import dev.lukag.lkml.data.local.entity.MessageEntity
import dev.lukag.lkml.data.local.entity.MessageFtsEntity
import dev.lukag.lkml.data.local.entity.ThreadEntity

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
abstract class LkmlDatabase : RoomDatabase() {
    abstract fun threadDao(): ThreadDao
    abstract fun messageDao(): MessageDao
    abstract fun mailingListDao(): MailingListDao

    companion object {
        const val NAME = "lkml.db"
    }
}
