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

package dev.lukag.lkml.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Identifies the client to lore.kernel.org.
 *
 * The User-Agent is load-bearing, not cosmetic. The host sits behind an nginx UA filter
 * and an Anubis proof-of-work interstitial, and they disagree about who is a bot:
 *
 *  - a UA containing `Mozilla` gets a JavaScript PoW challenge page, served as
 *    `HTTP 200 text/html` — a "successful" response that contains no archive data;
 *  - a UA of `curl/…` is rejected outright with `403`;
 *  - a plain, descriptive client UA is served normally on every endpoint.
 *
 * So the app must not impersonate a browser. Doing so would both break the app and be
 * exactly the behaviour the operator's bot protection is asking clients not to exhibit.
 * A contactable UA is also the etiquette public-inbox asks of automated clients.
 */
class UserAgentInterceptor(private val userAgent: String) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain.request().newBuilder()
                .header("User-Agent", userAgent)
                // Note: `Accept-Encoding` is deliberately *not* set here. OkHttp adds it
                // itself and then transparently decompresses the response — but only when
                // it owns the header. Setting it manually silently disables that, and the
                // HTML endpoints would hand the parsers raw gzip bytes.
                .build(),
        )
}

/** Raised when the archive answered with a bot interstitial instead of content. */
class BotChallengeException(url: String) :
    IOException("lore.kernel.org served a bot challenge for $url")

/**
 * Turns a bot interstitial into a real failure.
 *
 * The challenge arrives as `200 OK` with `Content-Type: text/html`, so without this check
 * it would flow into the mbox or topic parser and surface as a confusing "parse error"
 * far from the actual cause. Endpoints that legitimately return HTML (the topic index and
 * search) are distinguished by content, not by type: the interstitial is a small document
 * containing the Anubis marker, whereas real pages carry public-inbox markup.
 */
class BotChallengeInterceptor : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (!response.isSuccessful) return response

        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.startsWith("text/html")) return response

        // Peek rather than consume: the body must stay readable downstream.
        val peek = response.peekBody(PEEK_BYTES).string()
        if (CHALLENGE_MARKERS.any { peek.contains(it, ignoreCase = true) }) {
            response.close()
            throw BotChallengeException(chain.request().url.toString())
        }
        return response
    }

    private companion object {
        const val PEEK_BYTES = 8_192L
        val CHALLENGE_MARKERS = listOf(
            "Making sure you're not a bot",
            "Making sure you&#39;re not a bot",
            "/.within.website/",
            "anubis_challenge",
        )
    }
}
