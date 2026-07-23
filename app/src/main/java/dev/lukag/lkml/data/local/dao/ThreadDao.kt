package dev.lukag.lkml.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import dev.lukag.lkml.data.local.entity.FeedPageEntity
import dev.lukag.lkml.data.local.entity.ThreadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ThreadDao {

    @Query("SELECT * FROM threads WHERE inFeed = 1 ORDER BY lastActivityEpochMillis DESC")
    fun observeFeed(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE isSaved = 1 ORDER BY cachedAtEpochMillis DESC")
    fun observeSaved(): Flow<List<ThreadEntity>>

    @Query("SELECT * FROM threads WHERE rootMessageId = :id")
    fun observeThread(id: String): Flow<ThreadEntity?>

    @Query("SELECT * FROM threads WHERE rootMessageId = :id")
    suspend fun getThread(id: String): ThreadEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(threads: List<ThreadEntity>): List<Long>

    /**
     * Upserts feed rows without clobbering local state.
     *
     * A plain REPLACE would reset [ThreadEntity.isSaved] and [ThreadEntity.isCached] every
     * time the feed refreshed — silently un-saving the user's threads and orphaning cached
     * messages. Insert-then-patch keeps server-owned columns and client-owned columns
     * strictly separate.
     */
    @Transaction
    suspend fun upsertFeed(threads: List<ThreadEntity>) {
        val inserted = insertIgnoring(threads)
        threads.forEachIndexed { index, thread ->
            if (inserted[index] == -1L) {
                updateFeedFields(
                    id = thread.rootMessageId,
                    subject = thread.subject,
                    lastActivity = thread.lastActivityEpochMillis,
                    messageCount = thread.messageCount,
                    latestAuthor = thread.latestAuthor,
                )
            }
        }
    }

    @Query(
        """
        UPDATE threads
           SET subject = :subject,
               lastActivityEpochMillis = MAX(lastActivityEpochMillis, :lastActivity),
               messageCount = MAX(messageCount, :messageCount),
               latestAuthor = COALESCE(:latestAuthor, latestAuthor),
               inFeed = 1
         WHERE rootMessageId = :id
        """,
    )
    suspend fun updateFeedFields(
        id: String,
        subject: String,
        lastActivity: Long,
        messageCount: Int,
        latestAuthor: String?,
    )

    /**
     * Adopts metadata recovered from a downloaded mbox.
     *
     * Deliberately does **not** touch `inFeed`, unlike [updateFeedFields]: a thread opened
     * from search results must not silently graft itself onto the feed list just because
     * its body was fetched.
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

    @Query("UPDATE threads SET inFeed = 0")
    suspend fun clearFeedFlag()

    /**
     * Evicts cached bodies for threads the user never saved.
     *
     * Only [ThreadEntity.isCached] rows are candidates and saved threads are excluded
     * unconditionally, so eviction can never take away content the user asked to keep.
     * The messages themselves go via the CASCADE on the foreign key.
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

    @Query("DELETE FROM threads WHERE isSaved = 0 AND inFeed = 0 AND isCached = 0")
    suspend fun pruneOrphanStubs(): Int

    @Query("SELECT COUNT(*) FROM threads WHERE isSaved = 1")
    fun observeSavedCount(): Flow<Int>

    @Query("SELECT * FROM threads WHERE isSaved = 1 AND (isCached = 0 OR cachedAtEpochMillis < :staleBefore)")
    suspend fun savedNeedingRefresh(staleBefore: Long): List<ThreadEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPage(page: FeedPageEntity)

    @Query("SELECT * FROM feed_pages ORDER BY pageIndex DESC LIMIT 1")
    suspend fun lastPage(): FeedPageEntity?

    @Query("DELETE FROM feed_pages")
    suspend fun clearPages()
}
