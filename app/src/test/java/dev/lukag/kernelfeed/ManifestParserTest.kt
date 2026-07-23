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

import dev.lukag.kernelfeed.data.remote.parser.ManifestParser
import dev.lukag.kernelfeed.domain.model.MailingLists
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Against the real `manifest.js.gz` captured from lore.kernel.org. */
class ManifestParserTest {

    private fun manifest() =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("manifest.js.gz"))

    private val lists by lazy { ManifestParser.parseGzipped(manifest()) }

    @Test
    fun `parses the full catalogue`() {
        // 400 git-epoch entries fold down to ~353 lists, plus the injected aggregate.
        assertTrue("expected hundreds of lists, got ${lists.size}", lists.size > 300)
        assertTrue(lists.all { it.slug.isNotBlank() })
        // Slugs go into navigation routes verbatim, so none may need escaping.
        assertTrue(
            "all slugs must be route-safe",
            lists.all { Regex("""[A-Za-z0-9._-]+""").matches(it.slug) },
        )
    }

    @Test
    fun `folds git epochs into a single list`() {
        val rcu = lists.filter { it.slug == "rcu" }
        assertEquals("epochs must collapse to one row", 1, rcu.size)
        // The `[epoch N]` suffix is an artefact of the git layout, not part of the name.
        assertEquals("Linux RCU subsystem development", rcu.single().description)
        assertTrue(rcu.single().lastActivityEpochMillis > 0)
    }

    @Test
    fun `keeps the newest timestamp across epochs`() {
        val raw = """
            {
              "/x/git/0.git": {"modified": 1000, "description": "X list [epoch 0]"},
              "/x/git/1.git": {"modified": 5000, "description": "X list [epoch 1]"}
            }
        """.trimIndent()
        val x = ManifestParser.parse(raw).single { it.slug == "x" }
        assertEquals(5_000_000L, x.lastActivityEpochMillis)
        assertEquals("X list", x.description)
    }

    @Test
    fun `injects the aggregate inbox which the manifest omits`() {
        val all = lists.single { it.slug == MailingLists.ALL }
        assertEquals(MailingLists.ALL, lists.first().slug)
        assertTrue(all.isFeatured)
    }

    @Test
    fun `marks the curated lists as featured`() {
        val featured = lists.filter { it.isFeatured }.map { it.slug }.toSet()
        // Every curated slug must actually exist in the archive, or it would be a
        // shortcut to a 404.
        for (slug in MailingLists.FEATURED) {
            assertTrue("$slug is curated but missing from the catalogue", slug in featured)
        }
    }

    @Test
    fun `known lists carry usable descriptions`() {
        assertEquals("The Linux Kernel Mailing List", lists.single { it.slug == "lkml" }.description)
        assertEquals(
            "Linux kernel staging patches",
            lists.single { it.slug == "linux-staging" }.description,
        )
        assertNotNull(lists.single { it.slug == "netdev" }.description)
    }

    @Test
    fun `tolerates unknown and missing fields`() {
        val raw = """
            {
              "/y/git/0.git": {"owner": null, "fingerprint": "abc", "reference": null,
                               "modified": 42, "description": "Y", "future_field": 7},
              "/z/git/0.git": {}
            }
        """.trimIndent()
        val parsed = ManifestParser.parse(raw)
        assertEquals(42_000L, parsed.single { it.slug == "y" }.lastActivityEpochMillis)

        val z = parsed.single { it.slug == "z" }
        assertEquals(0L, z.lastActivityEpochMillis)
        assertNull(z.description)
        // Falls back to the slug so the row is never blank in the UI.
        assertEquals("z", z.title)
    }

    @Test
    fun `ignores entries that are not git epoch paths`() {
        val raw = """{"/not-an-epoch": {"modified": 1}, "/ok/git/0.git": {"modified": 2}}"""
        val parsed = ManifestParser.parse(raw).filter { it.slug != MailingLists.ALL }
        assertEquals(listOf("ok"), parsed.map { it.slug })
    }
}
