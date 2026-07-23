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

package dev.lukag.kernelfeed.domain.model

/**
 * One archived mailing list on lore.kernel.org.
 *
 * [slug] is the URL path segment (`lkml`, `linux-staging`, `netdev`) and the primary key
 * everywhere — it is what every archive URL is built from.
 */
data class MailingList(
    val slug: String,
    val title: String,
    val description: String?,
    /** Last time any epoch of this list's git archive changed. */
    val lastActivityEpochMillis: Long,
    /** Curated lists shown above the fold; see [MailingLists.FEATURED]. */
    val isFeatured: Boolean = false,
    /** User-pinned, which floats a list to the top of the catalogue. */
    val isPinned: Boolean = false,
) {
    /** Display name, falling back to the slug when the archive gives no description. */
    val displayName: String get() = title.ifBlank { slug }
}

object MailingLists {

    /**
     * The aggregate inbox.
     *
     * `all` is a real, browsable inbox on lore but is absent from `manifest.js.gz`
     * (the manifest lists git-backed archives, and `all` is a virtual union), so it is
     * injected by hand rather than discovered.
     */
    const val ALL = "all"

    const val LKML = "lkml"

    /**
     * Fallback source for thread bodies and permalinks.
     *
     * Not an unconditional source: `/all/` lags behind the per-list indexes and 404s for
     * recently-posted threads, so a thread is always tried on the list it came from
     * first. The aggregate is used when the originating list is unknown (search results)
     * or no longer carries the thread — and when it does resolve it returns a richer
     * result, unioning in replies that only went to a cross-posted list.
     */
    const val AGGREGATE = ALL

    val ALL_ENTRY = MailingList(
        slug = ALL,
        title = "All lists",
        description = "Every message archived on lore.kernel.org, combined",
        lastActivityEpochMillis = Long.MAX_VALUE,
        isFeatured = true,
    )

    /**
     * Curated shortcuts.
     *
     * The archive has ~350 lists, the great majority of which are narrow subsystem or CI
     * lists. Showing them alphabetically would bury the handful most people actually open,
     * so these are surfaced first and everything else stays one search away.
     */
    val FEATURED: List<String> = listOf(
        ALL,
        LKML,
        "netdev",
        "linux-mm",
        "stable",
        "bpf",
        "linux-arm-kernel",
        "linux-staging",
        "linux-fsdevel",
        "linux-block",
        "linux-scsi",
        "linux-usb",
        "linux-pci",
        "linux-wireless",
        "linux-media",
        "dri-devel",
        "io-uring",
        "linux-security-module",
        "linux-doc",
        "git",
    )
}
