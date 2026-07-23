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

package dev.lukag.kernelfeed

import dev.lukag.kernelfeed.data.remote.BotChallengeException
import dev.lukag.kernelfeed.data.remote.BotChallengeInterceptor
import dev.lukag.kernelfeed.data.remote.UserAgentInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class NetworkStackTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
        client = OkHttpClient.Builder()
            .addInterceptor(UserAgentInterceptor(USER_AGENT))
            .addInterceptor(BotChallengeInterceptor())
            .build()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun get(path: String = "/") =
        client.newCall(Request.Builder().url(server.url(path)).build()).execute()

    @Test
    fun `sends the configured non-browser user agent`() {
        server.enqueue(MockResponse().setBody("ok"))
        get().close()

        val sent = server.takeRequest().getHeader("User-Agent")
        assertEquals(USER_AGENT, sent)
        // Impersonating a browser triggers the archive's proof-of-work interstitial, and
        // a curl-shaped UA is rejected outright; neither must ever creep back in.
        assertTrue("UA must not look like a browser", sent?.contains("Mozilla") != true)
        assertTrue("UA must not look like curl", sent?.startsWith("curl") != true)
    }

    /**
     * Regression guard for a subtle failure: OkHttp only decompresses responses when it
     * added `Accept-Encoding` itself. If an interceptor sets that header, gzipped HTML
     * arrives at the parsers as binary and every page silently fails to parse.
     */
    @Test
    fun `leaves gzip negotiation to okhttp so responses are decompressed`() {
        val body = Buffer().also { sink ->
            GzipSink(sink).buffer().use { it.writeUtf8("<html>topic index</html>") }
        }
        server.enqueue(
            MockResponse()
                .setHeader("Content-Encoding", "gzip")
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(body),
        )

        val text = get().use { it.body!!.string() }
        assertEquals("<html>topic index</html>", text)
        assertEquals(null, server.takeRequest().getHeader("Accept-Encoding").takeIf { it != "gzip" })
    }

    @Test
    fun `turns a bot interstitial into a typed failure`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><head><title>Making sure you&#39;re not a bot!</title></head></html>"),
        )

        try {
            get().close()
            fail("expected the interstitial to be rejected")
        } catch (expected: BotChallengeException) {
            assertTrue(expected.message!!.contains("bot challenge"))
        }
    }

    @Test
    fun `passes real html through untouched`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><body><pre><a href=\"x@y/T/#t\">subject</a></pre></body></html>"),
        )
        assertTrue(get().use { it.body!!.string() }.contains("subject"))
    }

    /** Binary bodies must not be sniffed for the challenge marker. */
    @Test
    fun `does not inspect non-html responses`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/gzip")
                .setBody(Buffer().writeUtf8("Making sure you're not a bot")),
        )
        assertTrue(get().use { it.isSuccessful })
    }

    private companion object {
        const val USER_AGENT = "kernelfeed/1.0 (Android; +https://lore.kernel.org)"
    }
}
