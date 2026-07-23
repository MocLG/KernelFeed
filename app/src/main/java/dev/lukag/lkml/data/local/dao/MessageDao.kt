package dev.lukag.lkml.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.lukag.lkml.data.local.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    /**
     * Ordered by date so the tree builder receives a stable sequence; sibling ordering in
     * the rendered tree is decided there, not here.
     */
    @Query("SELECT * FROM messages WHERE threadRootId = :rootId ORDER BY dateEpochMillis ASC")
    fun observeThreadMessages(rootId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE threadRootId = :rootId ORDER BY dateEpochMillis ASC")
    suspend fun getThreadMessages(rootId: String): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE threadRootId = :rootId")
    suspend fun countForThread(rootId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<MessageEntity>)

    /**
     * Blocking counterpart used by the mbox ingest path.
     *
     * mime4j's parse callback is synchronous and cannot suspend, so streaming a thread
     * into the database requires a blocking write from inside it. Callers are already
     * confined to `Dispatchers.IO`; this must never be called from the main thread.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllBlocking(messages: List<MessageEntity>)

    @Query("DELETE FROM messages WHERE threadRootId = :rootId")
    suspend fun deleteForThread(rootId: String)

    /**
     * Offline full-text search across everything already on disk.
     *
     * Joins the FTS shadow table back to `messages` for the real columns, then to
     * `threads` so results can be shown with their thread context. Ranked by recency
     * rather than FTS rank: on a mailing list, "newest matching discussion" is almost
     * always what the reader means.
     */
    @Query(
        """
        SELECT m.* FROM messages AS m
        JOIN messages_fts AS f ON f.docid = m.rowid
        WHERE messages_fts MATCH :query
        ORDER BY m.dateEpochMillis DESC
        LIMIT :limit
        """,
    )
    suspend fun searchOffline(query: String, limit: Int = 100): List<MessageEntity>

    @Query("SELECT SUM(LENGTH(body)) FROM messages")
    fun observeCachedBytes(): Flow<Long?>
}
