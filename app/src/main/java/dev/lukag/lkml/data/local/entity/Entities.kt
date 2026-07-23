package dev.lukag.lkml.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A thread as listed in the feed.
 *
 * Rows exist in two states. A row created from the topic index is a *stub*: it has a
 * subject and an activity time but no messages. Opening it downloads the thread mbox and
 * flips [isCached]. That split is what lets the feed render instantly from disk after a
 * cold start while bodies are fetched lazily.
 *
 * [isSaved] is user intent and is never touched by eviction; [isCached] is a cache fact
 * and is. Keeping them separate is what makes "saved for offline" a real guarantee rather
 * than a hint.
 */
@Entity(
    tableName = "threads",
    indices = [
        Index("lastActivityEpochMillis"),
        Index("isSaved"),
    ],
)
data class ThreadEntity(
    @PrimaryKey val rootMessageId: String,
    val subject: String,
    val lastActivityEpochMillis: Long,
    val messageCount: Int,
    val latestAuthor: String?,
    val isSaved: Boolean = false,
    val isCached: Boolean = false,
    /** When the mbox was last downloaded; drives staleness checks and eviction order. */
    val cachedAtEpochMillis: Long = 0L,
    /** Present only for threads that came from the feed, so search hits don't pollute it. */
    val inFeed: Boolean = false,
)

/**
 * One archived message.
 *
 * The body is stored as decoded plain text, deliberately *not* as pre-parsed blocks:
 * block structure is a rendering concern derived on demand, so improving the diff parser
 * takes effect on already-cached threads instead of requiring a cache migration.
 */
@Entity(
    tableName = "messages",
    indices = [
        Index("threadRootId"),
        Index("dateEpochMillis"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ThreadEntity::class,
            parentColumns = ["rootMessageId"],
            childColumns = ["threadRootId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val threadRootId: String,
    val subject: String,
    val authorName: String,
    val authorEmail: String,
    val dateEpochMillis: Long,
    val inReplyTo: String?,
    /** Newline-joined; see `Converters`. Order is significant (oldest ancestor first). */
    val references: String,
    val body: String,
)

/**
 * Full-text index over cached message text.
 *
 * `contentEntity` makes this a shadow table over [MessageEntity], so the body text is not
 * duplicated on disk — an important detail when a saved patch series can run to megabytes.
 * This is what makes search work with the radio off.
 */
@Fts4(contentEntity = MessageEntity::class)
@Entity(tableName = "messages_fts")
data class MessageFtsEntity(
    @ColumnInfo(name = "subject") val subject: String,
    @ColumnInfo(name = "authorName") val authorName: String,
    @ColumnInfo(name = "body") val body: String,
)

/**
 * A page of the topic index, remembered so the feed can resume paging after process death
 * and so a cold start can rebuild the exact list order the user last saw.
 */
@Entity(tableName = "feed_pages")
data class FeedPageEntity(
    @PrimaryKey val pageIndex: Int,
    val cursor: String?,
    val nextCursor: String?,
    val fetchedAtEpochMillis: Long,
)
