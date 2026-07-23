package dev.lukag.lkml.data.remote

import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Streaming
import retrofit2.http.Url

/**
 * Raw HTTP surface for lore.kernel.org.
 *
 * Every method takes a fully-built absolute URL from [LoreUrls] rather than templated
 * paths — see that class for why. Bodies are returned undecoded so the mbox endpoint can
 * be consumed as a stream instead of materialising a multi-megabyte string.
 */
interface LoreApi {

    /** Topic index or search results — both are HTML from the same handler. */
    @GET
    suspend fun getHtml(@Url url: String): Response<ResponseBody>

    /**
     * Gzipped mboxrd for an entire thread.
     *
     * [Streaming] keeps OkHttp from buffering the whole body; the mbox parser pulls
     * through a `GZIPInputStream` so peak memory stays proportional to one message,
     * not to the thread.
     */
    @Streaming
    @GET
    suspend fun getStream(@Url url: String): Response<ResponseBody>
}
