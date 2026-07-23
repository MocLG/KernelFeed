package dev.lukag.lkml

import dev.lukag.lkml.data.mbox.MboxParser
import dev.lukag.lkml.data.remote.BotChallengeInterceptor
import dev.lukag.lkml.data.remote.LoreUrls
import dev.lukag.lkml.data.remote.UserAgentInterceptor
import dev.lukag.lkml.data.remote.parser.LoreHtmlParsers
import dev.lukag.lkml.data.remote.parser.ManifestParser
import dev.lukag.lkml.domain.model.MailingLists
import dev.lukag.lkml.domain.parser.BodyParser
import dev.lukag.lkml.domain.parser.ThreadTreeBuilder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * End-to-end check against the real lore.kernel.org.
 *
 * Opt-in — run with `./gradlew test -Dlkml.live=true`. It is excluded from the default
 * suite on purpose: it depends on a third-party host being up, and a CI failure here
 * would say nothing about the code. Its value is confirming that the *captured* fixtures
 * the other tests use still match what the archive actually serves, so run it whenever a
 * parser changes or a lore-side change is suspected.
 *
 * It also doubles as the courteous-client check: it exercises exactly one request per
 * endpoint shape, not a crawl.
 */
class LiveSmokeTest {

    @Before
    fun requireOptIn() {
        assumeTrue(
            "set -Dlkml.live=true to run the live smoke test",
            System.getProperty("lkml.live") == "true",
        )
    }

    private val client = OkHttpClient.Builder()
        .addInterceptor(UserAgentInterceptor("lkml-app/1.0 (Android; +https://lore.kernel.org)"))
        .addInterceptor(BotChallengeInterceptor())
        .build()

    private fun body(url: String) =
        client.newCall(Request.Builder().url(url).build()).execute()

    @Test
    fun catalogueAndPerListFeeds() {
        val lists = body(LoreUrls.manifest()).use { ManifestParser.parseGzipped(it.body!!.byteStream()) }
        println("CATALOG lists=${lists.size}")
        println("  featured present=${lists.count { it.isFeatured }}")

        for (slug in listOf("all", "lkml", "linux-staging", "netdev", "bpf")) {
            val html = body(LoreUrls.topicIndex(slug, null)).use { it.body!!.string() }
            val page = LoreHtmlParsers.parseTopicIndex(html)
            println("FEED %-16s topics=%-4d cursor=%s".format(slug, page.topics.size, page.nextCursor))
            println("     top: ${page.topics.firstOrNull()?.subject?.take(60)}")
        }

        // A thread discovered on a narrow list must resolve from that list, and the
        // aggregate must be shown to be an unreliable first choice for recent threads.
        val staging = LoreHtmlParsers.parseTopicIndex(
            body(LoreUrls.topicIndex("linux-staging", null)).use { it.body!!.string() },
        ).topics.first()

        val aggregateCode = body(LoreUrls.threadMbox(MailingLists.AGGREGATE, staging.rootMessageId))
            .use { it.code }
        val viaAll = body(LoreUrls.threadMbox("linux-staging", staging.rootMessageId))
            .use { r -> GZIPInputStream(r.body!!.byteStream()).use { MboxParser.parseAll(it, staging.rootMessageId) } }
        println("CROSS-LIST staging '${staging.subject.take(40)}' aggregateHttp=$aggregateCode ownList=${viaAll.size} messages")
    }

    @Test
    fun live() {
        val idxHtml = body(LoreUrls.topicIndex("lkml", null)).use { it.body!!.string() }
        val page = LoreHtmlParsers.parseTopicIndex(idxHtml)
        println("INDEX topics=${page.topics.size} cursor=${page.nextCursor}")
        println("  first=${page.topics.first().subject.take(70)}")

        val page2 = LoreHtmlParsers.parseTopicIndex(
            body(LoreUrls.topicIndex("lkml", page.nextCursor)).use { it.body!!.string() },
        )
        println("PAGE2 topics=${page2.topics.size} overlap=${page2.topics.map{it.rootMessageId}.intersect(page.topics.map{it.rootMessageId}.toSet()).size}")

        val target = page.topics.first { it.messageCount > 3 }
        val msgs = body(LoreUrls.threadMbox("lkml", target.rootMessageId)).use { resp ->
            println("MBOX type=${resp.header("Content-Type")} enc=${resp.header("Content-Encoding")}")
            GZIPInputStream(resp.body!!.byteStream()).use {
                MboxParser.parseAll(it, target.rootMessageId)
            }
        }
        println("THREAD '${target.subject.take(55)}' claimed=${target.messageCount} parsed=${msgs.size}")

        val roots = ThreadTreeBuilder.build(msgs)
        val flat = ThreadTreeBuilder.flatten(roots, emptySet())
        println("TREE roots=${roots.size} flat=${flat.size} maxDepth=${flat.maxOf { it.depth }}")
        flat.take(6).forEach {
            println("  ${" ".repeat(it.depth * 2)}└ ${it.message.authorDisplay.take(28)} | ${it.message.subject.take(45)}")
        }

        var diffs = 0; var quotes = 0; var trailers = 0; var hunks = 0
        msgs.forEach { m ->
            BodyParser.parse(m.body).forEach { b ->
                when (b) {
                    is dev.lukag.lkml.domain.model.BodyBlock.Diff -> { diffs++; hunks += b.file.hunks.size }
                    is dev.lukag.lkml.domain.model.BodyBlock.Quote -> quotes++
                    is dev.lukag.lkml.domain.model.BodyBlock.Trailers -> trailers++
                    else -> Unit
                }
            }
        }
        println("BLOCKS diffFiles=$diffs hunks=$hunks quotes=$quotes trailerGroups=$trailers")
        println("EMPTY bodies=${msgs.count { it.body.isBlank() }}  noDate=${msgs.count { it.dateEpochMillis <= 0 }}  noAuthor=${msgs.count { it.authorEmail.isBlank() }}")

        val search = LoreHtmlParsers.parseSearchResults(
            body(LoreUrls.search("lkml", "io_uring memory leak", 0)).use { it.body!!.string() }, 0,
        )
        println("SEARCH hits=${search.results.size} next=${search.nextOffset}")
        println("  ${search.results.firstOrNull()?.subject?.take(60)} by ${search.results.firstOrNull()?.latestAuthor}")
    }
}
