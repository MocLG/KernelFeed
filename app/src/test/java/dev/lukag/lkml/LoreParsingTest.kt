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

package dev.lukag.lkml

import dev.lukag.lkml.data.mbox.MboxParser
import dev.lukag.lkml.data.remote.LoreUrls
import dev.lukag.lkml.data.remote.parser.LoreHtmlParsers
import dev.lukag.lkml.domain.parser.ThreadTreeBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * Parser tests against **real** responses captured from lore.kernel.org, not synthetic
 * fixtures. Hand-written samples tend to encode the parser's own assumptions; these
 * catch the details that only production data has — public-inbox's line-broken `href`
 * attributes, mboxrd escaping, DKIM headers longer than most parsers' limits.
 */
class LoreParsingTest {

    private fun resource(name: String) =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }

    private fun text(name: String) = resource(name).bufferedReader().readText()

    @Test
    fun `parses the live topic index`() {
        val page = LoreHtmlParsers.parseTopicIndex(text("topic_index.html"))

        assertTrue("expected many topics, got ${page.topics.size}", page.topics.size > 50)
        assertNotNull("index must expose a next-page cursor", page.nextCursor)

        val first = page.topics.first()
        assertEquals("20260717084453.9521-1-tbogendoerfer@suse.de", first.rootMessageId)
        assertTrue(first.subject.contains("ice: rephrase LLDP filter fallback message"))
        assertEquals(2, first.messageCount)
        assertTrue("timestamp must parse", first.lastActivityEpochMillis > 0)

        // No topic should carry HTML or an unresolved entity into the UI.
        assertTrue(page.topics.none { '<' in it.subject })
        assertTrue(page.topics.none { "&#" in it.subject || "&amp;" in it.subject })
    }

    @Test
    fun `parses live search results with authors and paging`() {
        val page = LoreHtmlParsers.parseSearchResults(text("search_results.html"), currentOffset = 0)

        assertTrue("expected a full page of hits", page.results.size > 100)
        assertEquals(200, page.nextOffset)
        assertTrue(page.results.all { it.rootMessageId.isNotBlank() })
        assertTrue(page.results.any { !it.latestAuthor.isNullOrBlank() })
        assertTrue(page.results.none { it.rootMessageId.endsWith("/") })
    }

    @Test
    fun `parses a real gzipped thread mbox`() {
        val messages = GZIPInputStream(resource("thread.mbox.gz")).use {
            MboxParser.parseAll(it, threadRootId = "root")
        }

        assertEquals(2, messages.size)

        val root = messages.first()
        assertEquals("20260717084453.9521-1-tbogendoerfer@suse.de", root.messageId)
        assertEquals("Thomas Bogendoerfer", root.authorName)
        assertEquals("tbogendoerfer@suse.de", root.authorEmail)
        assertTrue(root.subject.startsWith("[PATCH net-next] ice:"))
        assertTrue("date must be decoded", root.dateEpochMillis > 0)
        assertTrue("body must contain the patch", root.body.contains("diff --git"))

        val reply = messages[1]
        assertEquals(root.messageId, reply.inReplyTo)
        assertEquals("Loktionov, Aleksandr", reply.authorName)
    }

    @Test
    fun `mbox messages rebuild into the correct reply tree`() {
        val messages = GZIPInputStream(resource("thread.mbox.gz")).use {
            MboxParser.parseAll(it, threadRootId = "root")
        }
        val roots = ThreadTreeBuilder.build(messages)

        assertEquals(1, roots.size)
        assertEquals(1, roots.single().children.size)
        assertEquals(2, ThreadTreeBuilder.flatten(roots, emptySet()).size)
    }

    @Test
    fun `bot challenge pages are recognised`() {
        val challenge = """<!doctype html><html lang="en"><head>""" +
            """<title>Making sure you&#39;re not a bot!</title>"""
        assertTrue(LoreHtmlParsers.isBotChallenge(challenge))
        assertTrue(!LoreHtmlParsers.isBotChallenge(text("topic_index.html")))
    }

    @Test
    fun `message ids survive url encoding`() {
        val awkward = "a/b?c#d%e+f@host.example.com"
        val url = LoreUrls.threadMbox("lkml", awkward)

        // Path-structural characters must be escaped, but `@` and `+` must not be —
        // public-inbox matches Message-IDs literally and rejects over-encoded ones.
        assertTrue(url.contains("%2F"))
        assertTrue(url.contains("%3F"))
        assertTrue(url.contains("%23"))
        assertTrue(url.contains("%25"))
        assertTrue(url.contains("+f@host.example.com"))
        assertTrue(url.endsWith("/t.mbox.gz"))
    }

    @Test
    fun `angle brackets are stripped from raw header message ids`() {
        assertEquals("x@y.example", LoreUrls.canonicalMessageId("<x@y.example>"))
        assertEquals("x@y.example", LoreUrls.canonicalMessageId("  x@y.example  "))
    }

    @Test
    fun `subject tags are split out of patch subjects`() {
        val tags = dev.lukag.lkml.domain.model.SubjectTags.parse(
            "Re: [PATCH v23 06/14] dmaengine: qcom: bam_dma: add support",
        )
        assertTrue(tags.isPatch)
        assertEquals(23, tags.version)
        assertEquals(6, tags.seriesIndex)
        assertEquals(14, tags.seriesTotal)
        assertEquals("v23 6/14", tags.seriesLabel)
        assertEquals("dmaengine: qcom: bam_dma: add support", tags.remainder)
    }

    @Test
    fun `cover letters are identified`() {
        val tags = dev.lukag.lkml.domain.model.SubjectTags.parse("[PATCH v3 00/12] mm: rework things")
        assertTrue(tags.isCoverLetter)
    }
}
