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

import dev.lukag.kernelfeed.domain.model.Message
import dev.lukag.kernelfeed.domain.parser.ThreadTreeBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadTreeBuilderTest {

    private fun msg(
        id: String,
        inReplyTo: String? = null,
        references: List<String> = emptyList(),
        date: Long = 0L,
    ) = Message(
        messageId = id,
        threadRootId = "root",
        subject = "s",
        authorName = "A",
        authorEmail = "a@example.com",
        dateEpochMillis = date,
        inReplyTo = inReplyTo,
        references = references,
        body = "",
    )

    @Test
    fun `builds a nested tree from In-Reply-To`() {
        val roots = ThreadTreeBuilder.build(
            listOf(
                msg("root", date = 1),
                msg("a", inReplyTo = "root", date = 2),
                msg("b", inReplyTo = "a", date = 3),
                msg("c", inReplyTo = "root", date = 4),
            ),
        )

        assertEquals(1, roots.size)
        val root = roots.single()
        assertEquals(2, root.children.size)
        assertEquals(4, root.subtreeSize)
        assertEquals(1, root.children.first().children.size)
        assertEquals(2, root.children.first().children.first().depth)
    }

    /** Mailers that drop In-Reply-To still carry References; the nearest one wins. */
    @Test
    fun `falls back to the last resolvable reference`() {
        val roots = ThreadTreeBuilder.build(
            listOf(
                msg("root", date = 1),
                msg("a", inReplyTo = "root", date = 2),
                msg("b", references = listOf("root", "a"), date = 3),
            ),
        )
        assertEquals("b", roots.single().children.single().children.single().message.messageId)
    }

    @Test
    fun `a message whose parent is missing becomes its own root`() {
        val roots = ThreadTreeBuilder.build(
            listOf(
                msg("root", date = 1),
                msg("orphan", inReplyTo = "not-in-archive", date = 2),
            ),
        )
        assertEquals(2, roots.size)
        assertTrue(roots.any { it.message.messageId == "orphan" })
    }

    /** A malformed reply loop must not hang the parse. */
    @Test
    fun `breaks reference cycles`() {
        val roots = ThreadTreeBuilder.build(
            listOf(
                msg("x", inReplyTo = "y", date = 1),
                msg("y", inReplyTo = "x", date = 2),
            ),
        )
        assertEquals(2, ThreadTreeBuilder.flatten(roots, emptySet()).size)
    }

    @Test
    fun `duplicate message ids are collapsed`() {
        val roots = ThreadTreeBuilder.build(listOf(msg("root", date = 1), msg("root", date = 5)))
        assertEquals(1, roots.size)
    }

    @Test
    fun `collapsing removes descendants from the flattened list`() {
        val roots = ThreadTreeBuilder.build(
            listOf(
                msg("root", date = 1),
                msg("a", inReplyTo = "root", date = 2),
                msg("b", inReplyTo = "a", date = 3),
                msg("c", inReplyTo = "root", date = 4),
            ),
        )

        assertEquals(4, ThreadTreeBuilder.flatten(roots, emptySet()).size)

        val collapsed = ThreadTreeBuilder.flatten(roots, setOf("a"))
        assertEquals(3, collapsed.size)
        assertTrue(collapsed.none { it.message.messageId == "b" })
        assertEquals(1, collapsed.first { it.message.messageId == "a" }.descendantCount)
    }

    @Test
    fun `ancestors are returned nearest first`() {
        val roots = ThreadTreeBuilder.build(
            listOf(
                msg("root", date = 1),
                msg("a", inReplyTo = "root", date = 2),
                msg("b", inReplyTo = "a", date = 3),
            ),
        )
        assertEquals(
            listOf("a", "root"),
            ThreadTreeBuilder.ancestorsOf(roots, "b").map { it.messageId },
        )
    }
}
