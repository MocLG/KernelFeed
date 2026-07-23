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

package dev.lukag.kernelfeed.data.remote

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
