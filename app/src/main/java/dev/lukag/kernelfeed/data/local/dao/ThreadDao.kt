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

package dev.lukag.kernelfeed.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.lukag.kernelfeed.data.local.entity.FeedEntryEntity
import dev.lukag.kernelfeed.data.local.entity.FeedPageEntity
import dev.lukag.kernelfeed.data.local.entity.ThreadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreadDao {

    /**
     * One list's feed, ordered by that list's own activity timestamps.
     *
     * The join is what makes a thread able to sit in several lists' feeds at once
     * without them overwriting each other.
     */
    @Query(
        """
        SELECT t.* FROM threads AS t
        JOIN feed_entries AS f ON f.rootMessageId = t.rootMessageId
        WHERE f.listSlug = :listSlug
        ORDER BY f.lastActivityEpochMillis DESC
        """,
    )
    fun observeFeed(listSlug: String): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE isSaved = 1 ORDER BY cachedAtEpochMillis DESC")
    fun observeSaved(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE rootMessageId = :id")
    fun observeThread(id: String): Flow<ThreadEntity?>

    @Query("SELECT * FROM threads WHERE rootMessageId = :id")
    suspend fun getThread(id: String): ThreadEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(threads: List<ThreadEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFeedEntries(entries: List<FeedEntryEntity>)

    @Query("DELETE FROM feed_entries WHERE listSlug = :listSlug")
    suspend fun clearFeed(listSlug: String)

    /**
     * Upserts feed rows without clobbering local state.
     *
     * A plain REPLACE would reset [ThreadEntity.isSaved] and [ThreadEntity.isCached] every
     * time the feed refreshed — silently un-saving the user's threads and orphaning cached
     * messages. Insert-then-patch keeps server-owned columns and client-owned columns
     * strictly separate.
     */
    @Transaction
    suspend fun upsertFeed(listSlug: String, threads: List<ThreadEntity>) {
        val inserted = insertIgnoring(threads)
        threads.forEachIndexed { index, thread ->
            if (inserted[index] == -1L) {
                updateThreadMeta(
                    id = thread.rootMessageId,
                    subject = thread.subject,
                    lastActivity = thread.lastActivityEpochMillis,
                    messageCount = thread.messageCount,
                    latestAuthor = thread.latestAuthor,
                )
            }
        }
        insertFeedEntries(
            threads.map {
                FeedEntryEntity(listSlug, it.rootMessageId, it.lastActivityEpochMillis)
            },
        )
    }

    /**
     * Adopts metadata recovered from a downloaded mbox or a feed page.
     *
     * Never touches feed membership: a thread opened from search must not graft itself
     * onto a list's feed just because its body was fetched.
     */
    @Query(
        """
        UPDATE threads
           SET subject = CASE WHEN subject = '' THEN :subject ELSE subject END,
               lastActivityEpochMillis = MAX(lastActivityEpochMillis, :lastActivity),
               messageCount = MAX(messageCount, :messageCount),
               latestAuthor = COALESCE(:latestAuthor, latestAuthor)
         WHERE rootMessageId = :id
        """,
    )
    suspend fun updateThreadMeta(
        id: String,
        subject: String,
        lastActivity: Long,
        messageCount: Int,
        latestAuthor: String?,
    )

    @Query("UPDATE threads SET isSaved = :saved WHERE rootMessageId = :id")
    suspend fun setSaved(id: String, saved: Boolean)

    @Query(
        """
        UPDATE threads
           SET isCached = 1,
               cachedAtEpochMillis = :now,
               messageCount = :count,
               latestAuthor = COALESCE(:latestAuthor, latestAuthor)
         WHERE rootMessageId = :id
        """,
    )
    suspend fun markCached(id: String, now: Long, count: Int, latestAuthor: String?)

    /**
     * Evicts cached bodies for threads the user never saved.
     *
     * Saved threads are excluded unconditionally, so eviction can never take away content
     * the user asked to keep. Messages go via the CASCADE on the foreign key.
     */
    @Query(
        """
        DELETE FROM threads
         WHERE isSaved = 0
           AND isCached = 1
           AND cachedAtEpochMillis < :before
        """,
    )
    suspend fun evictStaleCached(before: Long): Int

    /** Threads that are in no feed, unsaved and uncached carry no value; drop them. */
    @Query(
        """
        DELETE FROM threads
         WHERE isSaved = 0
           AND isCached = 0
           AND rootMessageId NOT IN (SELECT rootMessageId FROM feed_entries)
        """,
    )
    suspend fun pruneOrphanStubs(): Int

    @Query("SELECT COUNT(*) FROM threads WHERE isSaved = 1")
    fun observeSavedCount(): Flow<Int>

    @Query("SELECT * FROM threads WHERE isSaved = 1 AND (isCached = 0 OR cachedAtEpochMillis < :staleBefore)")
    suspend fun savedNeedingRefresh(staleBefore: Long): List<ThreadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPage(page: FeedPageEntity)

    @Query("SELECT * FROM feed_pages WHERE listSlug = :listSlug ORDER BY pageIndex DESC LIMIT 1")
    suspend fun lastPage(listSlug: String): FeedPageEntity?

    @Query("DELETE FROM feed_pages WHERE listSlug = :listSlug")
    suspend fun clearPages(listSlug: String)
}
