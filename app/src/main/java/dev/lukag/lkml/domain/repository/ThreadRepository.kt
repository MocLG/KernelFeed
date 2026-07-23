package dev.lukag.lkml.domain.repository

import dev.lukag.lkml.core.Resource
import dev.lukag.lkml.domain.model.Message
import dev.lukag.lkml.domain.model.ThreadSummary
import kotlinx.coroutines.flow.Flow

/**
 * The single read/write surface over LKML data.
 *
 * Everything observable is a `Flow` backed by Room, never by the network. Network calls
 * are one-shot `suspend` functions whose only job is to write into Room; the UI then
 * updates because the database changed. That inversion is what makes the app local-first:
 * there is no code path where the screen depends on a request completing.
 */
interface ThreadRepository {

    fun observeFeed(): Flow<List<ThreadSummary>>

    fun observeSaved(): Flow<List<ThreadSummary>>

    fun observeThread(rootMessageId: String): Flow<ThreadSummary?>

    fun observeMessages(rootMessageId: String): Flow<List<Message>>

    fun observeCachedBytes(): Flow<Long>

    /** Replaces the feed with the newest page. */
    suspend fun refreshFeed(): Resource<Unit>

    /** Appends the next page. Returns false once the archive has no older page. */
    suspend fun loadMoreFeed(): Resource<Boolean>

    /**
     * Downloads and parses the thread's mbox unless a fresh copy is already on disk.
     * Returns the number of messages available locally afterwards.
     */
    suspend fun ensureThreadCached(rootMessageId: String, force: Boolean = false): Resource<Int>

    suspend fun setSaved(rootMessageId: String, saved: Boolean): Resource<Unit>

    suspend fun searchRemote(query: String, offset: Int): Resource<SearchOutcome>

    /** Full-text search over cached bodies; works with no connectivity. */
    suspend fun searchLocal(query: String): Resource<List<Message>>

    /** Re-downloads saved threads that have gone stale and evicts unsaved cached ones. */
    suspend fun syncSavedThreads(): Resource<SyncOutcome>
}

data class SearchOutcome(val results: List<ThreadSummary>, val nextOffset: Int?)

data class SyncOutcome(val refreshed: Int, val failed: Int, val evicted: Int)
