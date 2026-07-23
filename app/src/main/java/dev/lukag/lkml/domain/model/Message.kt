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

package dev.lukag.lkml.domain.model

/**
 * A single mail message, exactly as it exists in the archive.
 *
 * [messageId] is the RFC 5322 Message-ID with the angle brackets stripped. It is the
 * app's universal primary key: Room rows, LazyColumn item keys and lore.kernel.org URL
 * path segments are all derived from it, so it must stay canonical (never re-encoded on
 * the way in).
 *
 * [body] is the decoded text/plain part only. Rendering structure (quotes, diffs,
 * trailers) is *not* stored here — it is derived on demand by the body parser so that a
 * parser improvement does not require a cache migration.
 */
data class Message(
    val messageId: String,
    val threadRootId: String,
    val subject: String,
    val authorName: String,
    val authorEmail: String,
    val dateEpochMillis: Long,
    val inReplyTo: String?,
    val references: List<String>,
    val body: String,
) {
    /** `Name <email>`, collapsing to just the address when the display name is absent. */
    val authorDisplay: String
        get() = if (authorName.isBlank()) authorEmail else authorName

    /** First letter for avatar chips; falls back to `?` for pathological From headers. */
    val authorInitial: Char
        get() = authorDisplay.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar() ?: '?'
}

/**
 * A node in the reply tree.
 *
 * The tree is built once per thread off the main thread and then *flattened* for display
 * (see [FlatMessage]); Compose never walks a recursive structure during composition,
 * which keeps `LazyColumn` item lookup O(1).
 */
data class MessageNode(
    val message: Message,
    val children: List<MessageNode>,
    val depth: Int,
) {
    /** Total messages in this subtree, including this node. */
    val subtreeSize: Int by lazy { 1 + children.sumOf { it.subtreeSize } }
}

/**
 * A tree node projected into a flat list for [androidx.compose.foundation.lazy.LazyColumn].
 *
 * Collapsing a node removes its descendants from the flattened list rather than hiding
 * them, so collapsed subtrees cost nothing to scroll past.
 */
data class FlatMessage(
    val message: Message,
    val depth: Int,
    val parentId: String?,
    val childCount: Int,
    val descendantCount: Int,
    val isCollapsed: Boolean,
    /** True when this node has siblings after it at the same depth — drives the tree gutter. */
    val hasFollowingSibling: Boolean,
)
