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

package dev.lukag.kernelfeed.domain.repository

import dev.lukag.kernelfeed.core.Resource
import dev.lukag.kernelfeed.domain.model.MailingList
import dev.lukag.kernelfeed.domain.model.Message
import dev.lukag.kernelfeed.domain.model.ThreadSummary
import kotlinx.coroutines.flow.Flow

/**
 * The single read/write surface over LKML data.
 *
 * Everything observable is a `Flow` backed by Room, never by the network. Network calls
 * are one-shot `suspend` functions whose only job is to write into Room; the UI then
 * updates because the database changed. That inversion is what makes the app local-first:
 * there is no code path where the screen depends on a request completing.
 *
 * Feed operations take a `listSlug`. Thread bodies do not: each thread remembers the
 * list it was discovered in and is fetched from there, falling back to the aggregate
 * inbox (see `MailingLists.AGGREGATE`).
 */
interface ThreadRepository {

    // ---- Catalogue -------------------------------------------------------------------

    fun observeLists(): Flow<List<MailingList>>

    fun searchLists(query: String): Flow<List<MailingList>>

    fun observeList(slug: String): Flow<MailingList?>

    /** Downloads `manifest.js.gz` and refreshes the catalogue. */
    suspend fun refreshLists(): Resource<Int>

    /** Populates the catalogue on first run if it is empty; a no-op afterwards. */
    suspend fun ensureListsLoaded(): Resource<Int>

    suspend fun setListPinned(slug: String, pinned: Boolean): Resource<Unit>

    // ---- Feeds -----------------------------------------------------------------------

    fun observeFeed(listSlug: String): Flow<List<ThreadSummary>>

    suspend fun refreshFeed(listSlug: String): Resource<Unit>

    /** Appends the next page. Returns false once the archive has no older page. */
    suspend fun loadMoreFeed(listSlug: String): Resource<Boolean>

    // ---- Threads ---------------------------------------------------------------------

    fun observeSaved(): Flow<List<ThreadSummary>>

    fun observeThread(rootMessageId: String): Flow<ThreadSummary?>

    fun observeMessages(rootMessageId: String): Flow<List<Message>>

    fun observeCachedBytes(): Flow<Long>

    /**
     * Downloads and parses the thread's mbox unless a fresh copy is already on disk.
     * Returns the number of messages available locally afterwards.
     */
    suspend fun ensureThreadCached(rootMessageId: String, force: Boolean = false): Resource<Int>

    suspend fun setSaved(rootMessageId: String, saved: Boolean): Resource<Unit>

    // ---- Search ----------------------------------------------------------------------

    suspend fun searchRemote(query: String, offset: Int): Resource<SearchOutcome>

    /** Full-text search over cached bodies; works with no connectivity. */
    suspend fun searchLocal(query: String): Resource<List<Message>>

    // ---- Maintenance -----------------------------------------------------------------

    /** Re-downloads saved threads that have gone stale and evicts unsaved cached ones. */
    suspend fun syncSavedThreads(): Resource<SyncOutcome>
}

data class SearchOutcome(val results: List<ThreadSummary>, val nextOffset: Int?)

data class SyncOutcome(val refreshed: Int, val failed: Int, val evicted: Int)
