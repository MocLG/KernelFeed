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

package dev.lukag.lkml.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Colours for diffs and quoted text.
 *
 * These sit *outside* the Material 3 scheme on purpose. Added/removed lines carry
 * semantics a user already knows from every other diff tool, and re-tinting them from a
 * wallpaper-derived dynamic palette would make a green-on-red patch render as, say,
 * lavender-on-mauve. Material dynamic colour governs the app's chrome; the diff keeps a
 * fixed, high-contrast palette so a patch always reads the same way.
 *
 * Both variants are checked against WCAG AA for body text on their own backgrounds.
 */
@Immutable
data class CodeColors(
    val addedFg: Color,
    val addedBg: Color,
    val removedFg: Color,
    val removedBg: Color,
    val contextFg: Color,
    val hunkFg: Color,
    val hunkBg: Color,
    val metaFg: Color,
    val pathFg: Color,
    val lineNumberFg: Color,
    val diffSurface: Color,
    val diffBorder: Color,
    /** Indexed by nesting level; deeper quotes cycle through the list. */
    val quoteAccents: List<Color>,
    val quoteFg: Color,
    val trailerFg: Color,
)

val LightCodeColors = CodeColors(
    addedFg = Color(0xFF116329),
    addedBg = Color(0xFFDAFBE1),
    removedFg = Color(0xFF82071E),
    removedBg = Color(0xFFFFEBE9),
    contextFg = Color(0xFF1F2328),
    hunkFg = Color(0xFF4B5563),
    hunkBg = Color(0xFFEFF2F5),
    metaFg = Color(0xFF6E7781),
    pathFg = Color(0xFF0550AE),
    lineNumberFg = Color(0xFFAFB8C1),
    diffSurface = Color(0xFFFAFBFC),
    diffBorder = Color(0xFFD8DEE4),
    quoteAccents = listOf(
        Color(0xFF6E7781),
        Color(0xFF0550AE),
        Color(0xFF8250DF),
        Color(0xFF9A6700),
    ),
    quoteFg = Color(0xFF57606A),
    trailerFg = Color(0xFF0550AE),
)

val DarkCodeColors = CodeColors(
    addedFg = Color(0xFF7EE787),
    addedBg = Color(0xFF12261E),
    removedFg = Color(0xFFFFA198),
    removedBg = Color(0xFF25171C),
    contextFg = Color(0xFFC9D1D9),
    hunkFg = Color(0xFF9DA7B3),
    hunkBg = Color(0xFF1C2128),
    metaFg = Color(0xFF8B949E),
    pathFg = Color(0xFF79C0FF),
    lineNumberFg = Color(0xFF6E7681),
    diffSurface = Color(0xFF0D1117),
    diffBorder = Color(0xFF30363D),
    quoteAccents = listOf(
        Color(0xFF8B949E),
        Color(0xFF79C0FF),
        Color(0xFFD2A8FF),
        Color(0xFFE3B341),
    ),
    quoteFg = Color(0xFF9DA7B3),
    trailerFg = Color(0xFF79C0FF),
)

val LocalCodeColors = staticCompositionLocalOf { LightCodeColors }
