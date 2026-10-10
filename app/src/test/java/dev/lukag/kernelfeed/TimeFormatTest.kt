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

import dev.lukag.kernelfeed.ui.util.formatRelative
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The label a row shows for a thing posted N minutes ago.
 *
 * Only the sub-day branches are asserted by exact string: they are pure arithmetic on the
 * delta. Older rows fall through to date formatting, which is deliberately rendered in the
 * device's own time zone and locale, so pinning a string there would only be testing
 * `java.time`.
 */
class TimeFormatTest {

    /** A fixed "now" so the assertions do not depend on when the suite runs. */
    private val now = 1_800_000_000_000L

    @Test
    fun `recent activity is phrased in the coarsest useful unit`() {
        assertEquals("just now", formatRelative(now - 20_000, now))
        assertEquals("3 min", formatRelative(now - 3 * 60_000, now))
        assertEquals("59 min", formatRelative(now - 59 * 60_000, now))
        assertEquals("1 h", formatRelative(now - 60 * 60_000, now))
        assertEquals("5 h", formatRelative(now - 5 * 60 * 60_000, now))
        assertEquals("23 h", formatRelative(now - 23 * 60 * 60_000, now))
    }

    @Test
    fun `units advance exactly at their boundary`() {
        // 59 s rounds down to "just now"; the next second must not.
        assertEquals("just now", formatRelative(now - 59_000, now))
        assertEquals("1 min", formatRelative(now - 60_000, now))
    }

    /**
     * Timestamps of `0` mean the source never supplied one. Showing "1970" — or worse,
     * "just now", since the delta would be enormous but the arithmetic still runs — would
     * be a lie about the data.
     */
    @Test
    fun `unknown timestamps are named, not guessed`() {
        assertEquals("unknown", formatRelative(0L, now))
        assertEquals("unknown", formatRelative(-1L, now))
    }
}
