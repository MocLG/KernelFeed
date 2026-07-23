package dev.lukag.lkml.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import dev.lukag.lkml.data.local.dao.MessageDao
import dev.lukag.lkml.data.local.dao.ThreadDao
import dev.lukag.lkml.data.local.entity.FeedPageEntity
import dev.lukag.lkml.data.local.entity.MessageEntity
import dev.lukag.lkml.data.local.entity.MessageFtsEntity
import dev.lukag.lkml.data.local.entity.ThreadEntity

@Database(
    entities = [
        ThreadEntity::class,
        MessageEntity::class,
        MessageFtsEntity::class,
        FeedPageEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LkmlDatabase : RoomDatabase() {
    abstract fun threadDao(): ThreadDao
    abstract fun messageDao(): MessageDao

    companion object {
        const val NAME = "lkml.db"
    }
}
