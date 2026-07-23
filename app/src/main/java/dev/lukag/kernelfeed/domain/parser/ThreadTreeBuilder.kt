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

package dev.lukag.kernelfeed.domain.parser

import dev.lukag.kernelfeed.domain.model.FlatMessage
import dev.lukag.kernelfeed.domain.model.Message
import dev.lukag.kernelfeed.domain.model.MessageNode

/**
 * Builds the reply tree for a thread and flattens it for display.
 *
 * Real mailing-list data is messier than the RFC implies, so the builder is defensive:
 *
 *  - `In-Reply-To` is trusted first, then the *last* entry of `References` (nearest
 *    ancestor), then any earlier reference — mailers truncate `References` and some drop
 *    `In-Reply-To` entirely.
 *  - A parent that isn't in the fetched set (cross-posted reply, expired message) makes
 *    the message an additional root rather than dropping it.
 *  - Cycles are broken by a visited check during the parent walk; a malformed
 *    `In-Reply-To` loop would otherwise hang the parse.
 *
 * Runs in a use case on `Dispatchers.Default`; the UI receives finished lists.
 */
object ThreadTreeBuilder {

    fun build(messages: List<Message>): List<MessageNode> {
        if (messages.isEmpty()) return emptyList()

        // Later duplicates of a Message-ID are dropped; archives occasionally hold both a
        // list copy and a direct copy of the same mail.
        val byId = LinkedHashMap<String, Message>(messages.size)
        for (m in messages) byId.putIfAbsent(m.messageId, m)

        val parentOf = HashMap<String, String?>(byId.size)
        for (m in byId.values) parentOf[m.messageId] = resolveParent(m, byId)

        // Break any cycle by demoting the message that closes it to a root.
        for (id in parentOf.keys.toList()) {
            if (walksIntoCycle(id, parentOf)) parentOf[id] = null
        }

        val childIds = HashMap<String, MutableList<String>>()
        val rootIds = mutableListOf<String>()
        for (m in byId.values) {
            val p = parentOf[m.messageId]
            if (p == null) rootIds += m.messageId
            else childIds.getOrPut(p) { mutableListOf() } += m.messageId
        }

        val byDate = compareBy<String> { byId.getValue(it).dateEpochMillis }
            .thenBy { it }

        fun node(id: String, depth: Int): MessageNode {
            val kids = childIds[id].orEmpty().sortedWith(byDate).map { node(it, depth + 1) }
            return MessageNode(byId.getValue(id), kids, depth)
        }

        return rootIds.sortedWith(byDate).map { node(it, 0) }
    }

    private fun resolveParent(m: Message, byId: Map<String, Message>): String? {
        m.inReplyTo?.takeIf { it != m.messageId && byId.containsKey(it) }?.let { return it }
        // References run oldest → newest; the nearest present ancestor is the best parent.
        for (ref in m.references.asReversed()) {
            if (ref != m.messageId && byId.containsKey(ref)) return ref
        }
        return null
    }

    private fun walksIntoCycle(start: String, parentOf: Map<String, String?>): Boolean {
        val seen = HashSet<String>()
        var cur: String? = start
        while (cur != null) {
            if (!seen.add(cur)) return true
            cur = parentOf[cur]
        }
        return false
    }

    /**
     * Projects the tree into the list `LazyColumn` renders.
     *
     * Descendants of a collapsed node are omitted entirely rather than emitted-and-hidden,
     * so collapsing a 300-reply subtree actually removes 300 items from the list.
     */
    fun flatten(roots: List<MessageNode>, collapsed: Set<String>): List<FlatMessage> {
        val out = ArrayList<FlatMessage>()

        fun visit(node: MessageNode, parentId: String?, hasFollowingSibling: Boolean) {
            val isCollapsed = node.message.messageId in collapsed
            out += FlatMessage(
                message = node.message,
                depth = node.depth,
                parentId = parentId,
                childCount = node.children.size,
                descendantCount = node.subtreeSize - 1,
                isCollapsed = isCollapsed,
                hasFollowingSibling = hasFollowingSibling,
            )
            if (isCollapsed) return
            node.children.forEachIndexed { idx, child ->
                visit(child, node.message.messageId, idx < node.children.lastIndex)
            }
        }

        roots.forEachIndexed { idx, root -> visit(root, null, idx < roots.lastIndex) }
        return out
    }

    /** Every ancestor of [messageId], nearest first — powers jump-to-parent navigation. */
    fun ancestorsOf(roots: List<MessageNode>, messageId: String): List<Message> {
        val path = mutableListOf<Message>()

        fun search(node: MessageNode): Boolean {
            if (node.message.messageId == messageId) return true
            for (c in node.children) {
                if (search(c)) { path += node.message; return true }
            }
            return false
        }

        roots.forEach { if (search(it)) return path }
        return emptyList()
    }
}
