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

package dev.lukag.lkml.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Fts4
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.lukag.lkml.domain.model.MailingLists

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
    /**
     * The list this thread was discovered in, and the first place its mbox is fetched
     * from. The aggregate inbox lags behind per-list indexes and 404s for recent threads,
     * so it cannot be used unconditionally — see `ThreadRepositoryImpl.threadMboxSources`.
     */
    val sourceList: String = MailingLists.ALL,
)

/**
 * Membership of a thread in one list's feed.
 *
 * A join table rather than a column on [ThreadEntity], because the same thread genuinely
 * appears in several lists' indexes — a networking patch is on `netdev`, `lkml` and
 * `all` at once. A single `feedList` column would make those lists fight over the row,
 * with whichever refreshed last winning and the others losing the thread from their feed.
 *
 * [lastActivityEpochMillis] is duplicated here on purpose: it is the *per-list* ordering
 * key, which can differ from the thread's global last activity when a list only carried
 * part of a cross-posted discussion.
 */
@Entity(
    tableName = "feed_entries",
    primaryKeys = ["listSlug", "rootMessageId"],
    indices = [
        Index("listSlug", "lastActivityEpochMillis"),
        Index("rootMessageId"),
    ],
    foreignKeys = [
        ForeignKey(
            entity = ThreadEntity::class,
            parentColumns = ["rootMessageId"],
            childColumns = ["rootMessageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class FeedEntryEntity(
    val listSlug: String,
    val rootMessageId: String,
    val lastActivityEpochMillis: Long,
)

/** A mailing list from `manifest.js.gz`, plus the user's pin state. */
@Entity(
    tableName = "mailing_lists",
    indices = [Index("lastActivityEpochMillis"), Index("isFeatured")],
)
data class MailingListEntity(
    @PrimaryKey val slug: String,
    val title: String,
    val description: String?,
    val lastActivityEpochMillis: Long,
    val isFeatured: Boolean = false,
    val isPinned: Boolean = false,
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
 * A page of one list's topic index, remembered so the feed can resume paging after
 * process death and so a cold start can rebuild the order the user last saw.
 *
 * Keyed by list as well as page: each list pages independently, and sharing a cursor
 * across lists would resume `netdev` at `lkml`'s position.
 */
@Entity(tableName = "feed_pages", primaryKeys = ["listSlug", "pageIndex"])
data class FeedPageEntity(
    val listSlug: String,
    val pageIndex: Int,
    val cursor: String?,
    val nextCursor: String?,
    val fetchedAtEpochMillis: Long,
)
