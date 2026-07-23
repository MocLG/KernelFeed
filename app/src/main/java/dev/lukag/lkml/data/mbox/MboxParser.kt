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

package dev.lukag.lkml.data.mbox

import dev.lukag.lkml.data.remote.LoreUrls
import dev.lukag.lkml.domain.model.Message
import org.apache.james.mime4j.dom.Multipart
import org.apache.james.mime4j.dom.TextBody
import org.apache.james.mime4j.dom.address.Mailbox
import org.apache.james.mime4j.message.DefaultMessageBuilder
import org.apache.james.mime4j.stream.MimeConfig
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.InputStreamReader
import org.apache.james.mime4j.dom.Message as MimeMessage

/**
 * Streaming parser for the `t.mbox.gz` bodies lore serves for a whole thread.
 *
 * Two properties drive the design:
 *
 *  - **Bounded memory.** The archive is read line-by-line and only one message is held in
 *    memory at a time; each is handed to mime4j as soon as its terminating `From ` line
 *    appears. A 2000-message thread costs the same peak memory as a 2-message one.
 *    [parse] emits through a callback rather than returning a list so the repository can
 *    stream straight into a Room transaction.
 *
 *  - **mboxrd, not mbox.** lore serves the `mboxrd` variant (the archive literally opens
 *    `From mboxrd@z ...`), in which any body line matching `>*From ` was escaped with one
 *    extra `>` on the way in. Failing to unescape corrupts quoted text and — because
 *    `From ` is common at the start of a quoted reply — misparses reply structure.
 *
 * mime4j does the genuinely hard parts: RFC 2047 encoded-word headers (`=?UTF-8?q?…?=`),
 * quoted-printable and base64 transfer encodings, per-part charsets, and nested MIME.
 * Hand-rolling those is where naive mail parsers break on non-English author names.
 */
object MboxParser {

    /** Lenient limits: kernel mail carries very long DKIM/ARC headers and huge patches. */
    private val MIME_CONFIG: MimeConfig = MimeConfig.Builder()
        .setMaxLineLen(-1)
        .setMaxHeaderLen(-1)
        .setMaxHeaderCount(-1)
        .setMaxContentLen(-1)
        .setStrictParsing(false)
        .build()

    private val FROM_LINE = Regex("""^From \S+ .*$""")
    private val MBOXRD_ESCAPED = Regex("""^>+From """)
    private val ANGLE_ID = Regex("""<([^<>]+)>""")

    /** Content types whose payload is worth showing as text (patches often arrive attached). */
    private val TEXTUAL_TYPES = setOf(
        "text/plain", "text/x-patch", "text/x-diff", "text/x-c",
        "application/x-patch", "application/x-diff",
    )

    /**
     * Parses [input] (already gunzipped) and invokes [onMessage] once per message.
     *
     * [threadRootId] is stamped on every message so the thread can be queried as a unit;
     * the true reply structure is rebuilt later by `ThreadTreeBuilder` from the headers.
     *
     * Returns the number of messages successfully parsed. Individual malformed messages
     * are skipped rather than aborting the thread — a single bad message in a 500-message
     * archive should not cost the user the other 499.
     */
    fun parse(
        input: InputStream,
        threadRootId: String,
        onMessage: (Message) -> Unit,
    ): Int {
        val reader = BufferedReader(InputStreamReader(input, Charsets.ISO_8859_1), DEFAULT_BUFFER_SIZE)
        val builder = DefaultMessageBuilder().apply { setMimeEntityConfig(MIME_CONFIG) }

        var parsed = 0
        val current = StringBuilder(8 * 1024)
        var started = false

        fun flush() {
            if (!started || current.isEmpty()) return
            // ISO-8859-1 round-trips bytes 1:1, so this restores the exact octets mime4j
            // needs in order to apply the charset declared in the message's own headers.
            val bytes = current.toString().toByteArray(Charsets.ISO_8859_1)
            current.setLength(0)
            runCatching { builder.parseMessage(ByteArrayInputStream(bytes)) }
                .mapCatching { it.toDomain(threadRootId) }
                .onSuccess { msg -> if (msg != null) { onMessage(msg); parsed++ } }
        }

        reader.forEachLine { line ->
            if (FROM_LINE.matches(line) && (!started || current.isNotEmpty())) {
                flush()
                started = true
                return@forEachLine
            }
            if (started) {
                current.append(unescapeMboxrd(line)).append('\n')
            }
        }
        flush()
        return parsed
    }

    /** Convenience wrapper for callers that genuinely want the whole thread in memory. */
    fun parseAll(input: InputStream, threadRootId: String): List<Message> =
        buildList { parse(input, threadRootId) { add(it) } }

    /** mboxrd: `>From ` → `From `, `>>From ` → `>From `, and so on. */
    private fun unescapeMboxrd(line: String): String =
        if (MBOXRD_ESCAPED.containsMatchIn(line)) line.substring(1) else line

    private fun MimeMessage.toDomain(threadRootId: String): Message? {
        val messageId = LoreUrls.canonicalMessageId(messageId ?: return null)
        if (messageId.isBlank()) return null

        val fromMailbox = from?.firstOrNull()
        return Message(
            messageId = messageId,
            threadRootId = threadRootId,
            subject = subject.orEmpty().trim(),
            authorName = fromMailbox?.displayName()?.trim().orEmpty(),
            authorEmail = fromMailbox?.address.orEmpty().trim(),
            dateEpochMillis = date?.time ?: 0L,
            inReplyTo = header.getField("In-Reply-To")?.body?.let { extractIds(it).firstOrNull() },
            references = header.getField("References")?.body?.let { extractIds(it) }.orEmpty(),
            body = extractText().trim(),
        )
    }

    private fun Mailbox.displayName(): String = name ?: ""

    private fun extractIds(raw: String): List<String> =
        ANGLE_ID.findAll(raw).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()

    /**
     * Pulls readable text out of an arbitrary MIME tree.
     *
     * Prefers `text/plain`, but also surfaces `text/x-patch`-style attachments, because on
     * kernel lists the patch is frequently the attachment and dropping it would leave the
     * message looking empty. Multipart trees are walked depth-first; `multipart/alternative`
     * is collapsed to its plain-text branch.
     */
    private fun MimeMessage.extractText(): String {
        val out = StringBuilder()
        appendBody(this.body, mimeType.orEmpty(), out, isAlternative = false)
        return out.toString()
    }

    private fun appendBody(
        body: org.apache.james.mime4j.dom.Body?,
        mimeType: String,
        out: StringBuilder,
        isAlternative: Boolean,
    ) {
        when (body) {
            is TextBody -> {
                if (mimeType.lowercase() in TEXTUAL_TYPES || mimeType.startsWith("text/", true)) {
                    // text/html is only used when nothing better was offered.
                    if (!mimeType.equals("text/html", true) || out.isEmpty()) {
                        if (out.isNotEmpty()) out.append("\n\n")
                        runCatching { body.reader.use { it.readText() } }.onSuccess(out::append)
                    }
                }
            }

            is Multipart -> {
                val parts = body.bodyParts
                if (isAlternative) {
                    // Take the plain-text branch if there is one; otherwise fall through.
                    val plain = parts.firstOrNull { it.mimeType.equals("text/plain", true) }
                    if (plain != null) {
                        appendBody(plain.body, plain.mimeType.orEmpty(), out, false)
                        return
                    }
                }
                for (part in parts) {
                    val type = part.mimeType.orEmpty()
                    appendBody(part.body, type, out, type.equals("multipart/alternative", true))
                }
            }

            else -> Unit // Binary attachments carry nothing to render.
        }
    }
}
