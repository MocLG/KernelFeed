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

package dev.lukag.lkml.data.repository

import androidx.room.withTransaction
import dev.lukag.lkml.core.AppError
import dev.lukag.lkml.core.NetworkMonitor
import dev.lukag.lkml.core.Resource
import dev.lukag.lkml.data.local.LkmlDatabase
import dev.lukag.lkml.data.local.toDomain
import dev.lukag.lkml.data.local.toEntity
import dev.lukag.lkml.data.local.entity.FeedPageEntity
import dev.lukag.lkml.data.local.entity.ThreadEntity
import dev.lukag.lkml.data.mbox.MboxParser
import dev.lukag.lkml.data.remote.BotChallengeException
import dev.lukag.lkml.data.remote.LoreApi
import dev.lukag.lkml.data.remote.LoreUrls
import dev.lukag.lkml.data.remote.parser.LoreHtmlParsers
import dev.lukag.lkml.data.remote.parser.ManifestParser
import dev.lukag.lkml.di.IoDispatcher
import dev.lukag.lkml.domain.model.MailingList
import dev.lukag.lkml.domain.model.MailingLists
import dev.lukag.lkml.domain.model.Message
import dev.lukag.lkml.domain.model.ThreadSummary
import dev.lukag.lkml.domain.repository.SearchOutcome
import dev.lukag.lkml.domain.repository.SyncOutcome
import dev.lukag.lkml.domain.repository.ThreadRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody
import retrofit2.Response
import java.io.IOException
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

@Singleton
class ThreadRepositoryImpl @Inject constructor(
    private val api: LoreApi,
    private val db: LkmlDatabase,
    private val networkMonitor: NetworkMonitor,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ThreadRepository {

    private val threadDao = db.threadDao()
    private val messageDao = db.messageDao()
    private val listDao = db.mailingListDao()

    // ---- Catalogue -------------------------------------------------------------------

    override fun observeLists(): Flow<List<MailingList>> =
        listDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun searchLists(query: String): Flow<List<MailingList>> =
        listDao.search(query.trim()).map { rows -> rows.map { it.toDomain() } }

    override fun observeList(slug: String): Flow<MailingList?> =
        listDao.observeList(slug).map { it?.toDomain() }

    override suspend fun refreshLists(): Resource<Int> = ioResult {
        val response = api.getStream(LoreUrls.manifest())
        val lists = response.requireBody("manifest").byteStream().use {
            ManifestParser.parseGzipped(it)
        }
        if (lists.isEmpty()) throw ParseFailure("manifest contained no lists")
        listDao.upsertCatalog(lists.map { it.toEntity() })
        lists.size
    }

    override suspend fun ensureListsLoaded(): Resource<Int> {
        val existing = withContext(io) { listDao.count() }
        // The catalogue changes on the order of weeks; refetching it on every launch
        // would spend a request to learn nothing.
        return if (existing > 0) Resource.Success(existing) else refreshLists()
    }

    override suspend fun setListPinned(slug: String, pinned: Boolean): Resource<Unit> =
        ioResult { listDao.setPinned(slug, pinned) }

    // ---- Reads: always from Room, never gated on the network. -------------------------

    override fun observeFeed(listSlug: String): Flow<List<ThreadSummary>> =
        threadDao.observeFeed(listSlug).map { rows -> rows.map { it.toDomain() } }

    override fun observeSaved(): Flow<List<ThreadSummary>> =
        threadDao.observeSaved().map { rows -> rows.map { it.toDomain() } }

    override fun observeThread(rootMessageId: String): Flow<ThreadSummary?> =
        threadDao.observeThread(rootMessageId).map { it?.toDomain() }

    override fun observeMessages(rootMessageId: String): Flow<List<Message>> =
        messageDao.observeThreadMessages(rootMessageId).map { rows -> rows.map { it.toDomain() } }

    override fun observeCachedBytes(): Flow<Long> =
        messageDao.observeCachedBytes().map { it ?: 0L }

    // ---- Feed ------------------------------------------------------------------------

    override suspend fun refreshFeed(listSlug: String): Resource<Unit> = ioResult {
        // Fetch before opening the transaction: holding a write lock across a network
        // round trip would block every reader for the duration of the request.
        val page = fetchTopicPage(listSlug, cursor = null)
        db.withTransaction {
            threadDao.clearFeed(listSlug)
            threadDao.clearPages(listSlug)
            threadDao.upsertFeed(
                listSlug,
                page.topics.map { it.copy(sourceList = listSlug).toEntity() },
            )
            threadDao.upsertPage(
                FeedPageEntity(listSlug, 0, null, page.nextCursor, System.currentTimeMillis()),
            )
            threadDao.pruneOrphanStubs()
        }
    }

    override suspend fun loadMoreFeed(listSlug: String): Resource<Boolean> = ioResult {
        val last = threadDao.lastPage(listSlug)
        val cursor = last?.nextCursor
        if (last != null && cursor == null) return@ioResult false // Archive exhausted.

        val page = fetchTopicPage(listSlug, cursor)
        threadDao.upsertFeed(
            listSlug,
            page.topics.map { it.copy(sourceList = listSlug).toEntity() },
        )
        threadDao.upsertPage(
            FeedPageEntity(
                listSlug = listSlug,
                pageIndex = (last?.pageIndex ?: -1) + 1,
                cursor = cursor,
                nextCursor = page.nextCursor,
                fetchedAtEpochMillis = System.currentTimeMillis(),
            ),
        )
        page.nextCursor != null && page.topics.isNotEmpty()
    }

    private suspend fun fetchTopicPage(listSlug: String, cursor: String?) =
        LoreHtmlParsers.parseTopicIndex(readHtml(LoreUrls.topicIndex(listSlug, cursor)))

    // ---- Thread bodies ---------------------------------------------------------------

    /**
     * Fetches the whole thread as one gzipped mbox and streams it into Room.
     *
     * One request per thread — not per message — is the central performance decision here.
     * lore serves a complete 14-message thread in about 9 KB compressed; fetching messages
     * individually would mean 14 round trips for the same bytes, and on mobile latency the
     * round trips dominate. It also makes offline saving trivial: the thing already
     * downloaded *is* the offline artefact.
     *
     * Messages are inserted in batches while parsing continues, so a large series starts
     * appearing on screen before the download has finished.
     */
    override suspend fun ensureThreadCached(rootMessageId: String, force: Boolean): Resource<Int> =
        ioResult {
            val existing = threadDao.getThread(rootMessageId)
            val onDisk = messageDao.countForThread(rootMessageId)
            val fresh = existing != null && existing.isCached &&
                System.currentTimeMillis() - existing.cachedAtEpochMillis < READ_CACHE_TTL_MILLIS

            if (!force && fresh && onDisk > 0) return@ioResult onDisk

            // The messages table has a FK onto threads, so the parent row must exist first.
            if (existing == null) {
                threadDao.insertIgnoring(
                    listOf(
                        ThreadEntity(
                            rootMessageId = rootMessageId,
                            subject = "",
                            lastActivityEpochMillis = 0L,
                            messageCount = 0,
                            latestAuthor = null,
                            sourceList = MailingLists.ALL,
                        ),
                    ),
                )
            }

            val body = fetchThreadMbox(rootMessageId, existing?.sourceList)

            var count = 0
            var newestDate = Long.MIN_VALUE
            var newestAuthor: String? = null
            val batch = ArrayList<Message>(BATCH_SIZE)

            fun flush() {
                if (batch.isEmpty()) return
                messageDao.insertAllBlocking(batch.map { it.toEntity() })
                batch.clear()
            }

            // The response is a .gz *file*, so OkHttp's transparent decompression does not
            // apply (that only covers Content-Encoding). Decompress explicitly, streaming.
            body.byteStream().use { raw ->
                GZIPInputStream(raw, GZIP_BUFFER).use { gz ->
                    MboxParser.parse(gz, rootMessageId) { msg ->
                        count++
                        if (msg.dateEpochMillis > newestDate) {
                            newestDate = msg.dateEpochMillis
                            newestAuthor = msg.authorDisplay
                        }
                        batch += msg
                        // Writing as we go keeps peak memory at one batch rather than one
                        // thread, and lets the UI start rendering mid-download.
                        if (batch.size >= BATCH_SIZE) flush()
                    }
                    flush()
                }
            }

            if (count == 0) throw ParseFailure("thread mbox contained no messages")

            threadDao.markCached(
                id = rootMessageId,
                now = System.currentTimeMillis(),
                count = count,
                latestAuthor = newestAuthor,
            )
            // A stub created above has an empty subject; adopt the real one from the mbox.
            if (existing?.subject.isNullOrBlank()) {
                val root = messageDao.getThreadMessages(rootMessageId)
                    .minByOrNull { it.dateEpochMillis }
                if (root != null) {
                    threadDao.updateThreadMeta(
                        id = rootMessageId,
                        subject = root.subject,
                        lastActivity = if (newestDate > Long.MIN_VALUE) newestDate else root.dateEpochMillis,
                        messageCount = count,
                        latestAuthor = newestAuthor,
                    )
                }
            }
            count
        }

    override suspend fun setSaved(rootMessageId: String, saved: Boolean): Resource<Unit> =
        ioResult { threadDao.setSaved(rootMessageId, saved) }

    // ---- Search ----------------------------------------------------------------------

    override suspend fun searchRemote(query: String, offset: Int): Resource<SearchOutcome> =
        ioResult {
            val html = readHtml(LoreUrls.search(MailingLists.AGGREGATE, query, offset))
            val page = LoreHtmlParsers.parseSearchResults(html, offset)
            // Search hits are recorded as bare thread rows with no feed membership, so
            // tapping one has a row to attach messages to without leaking into any feed.
            threadDao.insertIgnoring(
                page.results.map { it.copy(sourceList = MailingLists.ALL).toEntity() },
            )
            SearchOutcome(page.results, page.nextOffset)
        }

    override suspend fun searchLocal(query: String): Resource<List<Message>> = ioResult {
        val sanitised = toFtsQuery(query)
        if (sanitised.isBlank()) emptyList()
        else messageDao.searchOffline(sanitised).map { it.toDomain() }
    }

    /**
     * FTS4 `MATCH` treats `-`, `"`, `*`, `:` and friends as operators, and kernel searches
     * are full of them (`io_uring`, `mm/slab.c`, `-next`). Each term is quoted so it is
     * taken literally, with a trailing `*` for prefix matching on the final term.
     */
    private fun toFtsQuery(raw: String): String =
        raw.split(Regex("""\s+"""))
            .map { it.replace("\"", "").trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ") { "\"$it\"" }
            .let { if (it.isEmpty()) it else "$it*" }

    // ---- Background sync -------------------------------------------------------------

    override suspend fun syncSavedThreads(): Resource<SyncOutcome> = ioResult {
        val stale = threadDao.savedNeedingRefresh(
            System.currentTimeMillis() - SAVED_REFRESH_TTL_MILLIS,
        )
        var refreshed = 0
        var failed = 0
        for (thread in stale) {
            when (ensureThreadCached(thread.rootMessageId, force = true)) {
                is Resource.Success -> refreshed++
                else -> failed++
            }
        }
        val evicted = threadDao.evictStaleCached(System.currentTimeMillis() - EVICT_AFTER_MILLIS)
        SyncOutcome(refreshed, failed, evicted)
    }

    // ---- Plumbing --------------------------------------------------------------------

    /**
     * Fetches a thread's mbox, trying the list it came from before the aggregate.
     *
     * The aggregate inbox cannot be used unconditionally: `/all/` lags behind the
     * per-list indexes and returns 404 for recently-posted threads — measured against
     * live data, 4 of 9 sampled lists' newest threads were missing from it while every
     * one resolved on its own list. Conversely, when `/all/` *does* have a thread it
     * returns a richer result, because it unions in replies that only went to a
     * cross-posted list (an lkml thread measured 172 KB via `/all/` against 53 KB via
     * `/lkml/`).
     *
     * So the source list is tried first for reliability, and the aggregate second as a
     * fallback for threads whose originating list is unknown or no longer carries them.
     * Only 404 advances to the next candidate; any other failure propagates, because
     * retrying a timeout against a second host path just doubles the wait.
     */
    private suspend fun fetchThreadMbox(
        rootMessageId: String,
        sourceList: String?,
    ): ResponseBody {
        val candidates = listOfNotNull(sourceList, MailingLists.ALL).distinct()
        for ((index, list) in candidates.withIndex()) {
            val response = api.getStream(LoreUrls.threadMbox(list, rootMessageId))
            if (response.isSuccessful) {
                return response.body() ?: throw ParseFailure("empty thread mbox response")
            }
            response.errorBody()?.close()
            val isLast = index == candidates.lastIndex
            if (response.code() != 404 || isLast) {
                throw HttpFailure(response.code(), response.message())
            }
        }
        throw ParseFailure("no archive carries thread $rootMessageId")
    }

    private suspend fun readHtml(url: String): String {
        val response = api.getHtml(url)
        val text = response.requireBody("page").string()
        if (LoreHtmlParsers.isBotChallenge(text)) throw BotChallengeException(url)
        return text
    }

    private fun Response<ResponseBody>.requireBody(what: String): ResponseBody {
        if (!isSuccessful) throw HttpFailure(code(), message())
        return body() ?: throw ParseFailure("empty $what response")
    }

    /**
     * Runs [block] on the IO dispatcher and maps failures onto [AppError].
     *
     * Connectivity is checked *after* the failure rather than before the call: a
     * pre-flight check races with the actual request and would misreport a request that
     * failed for a different reason while the radio happened to be down.
     */
    private suspend fun <T> ioResult(block: suspend () -> T): Resource<T> = withContext(io) {
        try {
            Resource.Success(block())
        } catch (e: BotChallengeException) {
            Resource.Error(AppError.BotChallenge)
        } catch (e: HttpFailure) {
            Resource.Error(AppError.Http(e.code, e.reason))
        } catch (e: ParseFailure) {
            Resource.Error(AppError.Parse(e.message ?: "unexpected response"))
        } catch (e: IOException) {
            if (networkMonitor.currentlyOnline()) {
                Resource.Error(AppError.Network(e.message))
            } else {
                Resource.Offline(null)
            }
        } catch (e: android.database.SQLException) {
            Resource.Error(AppError.Storage(e.message))
        } catch (e: Exception) {
            Resource.Error(AppError.Unknown(e.message))
        }
    }

    private class HttpFailure(val code: Int, val reason: String?) :
        IOException("HTTP $code $reason")

    private class ParseFailure(message: String) : IOException(message)

    companion object {
        private const val BATCH_SIZE = 40
        private const val GZIP_BUFFER = 16 * 1024

        /**
         * How long an opened thread is served from disk without re-downloading.
         *
         * This is *not* what makes reopening instant — the thread screen observes Room, so
         * cached messages paint immediately whatever this value is. It only decides
         * whether a background refetch is also worth a request. At 24 hours, reopening a
         * thread you read today costs no network at all; the trade-off is that replies
         * arriving inside that window are not picked up until the user pulls refresh.
         */
        private val READ_CACHE_TTL_MILLIS = 24.hours.inWholeMilliseconds

        /**
         * Staleness threshold for *saved* threads in background sync.
         *
         * Kept short and separate from [READ_CACHE_TTL_MILLIS]: threads the user pinned
         * should track new replies closely, and folding the two together would have
         * quietly slowed saved-thread sync to once a day.
         */
        private val SAVED_REFRESH_TTL_MILLIS = 6.hours.inWholeMilliseconds

        /** Unsaved cached threads are discarded after this; saved ones never are. */
        private val EVICT_AFTER_MILLIS = 14.days.inWholeMilliseconds
    }
}
