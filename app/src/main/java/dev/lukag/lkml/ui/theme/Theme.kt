package dev.lukag.lkml.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

private val SeedLight = lightColorScheme(
    primary = Color(0xFF1F5E9E),
    secondary = Color(0xFF4E5F78),
    tertiary = Color(0xFF6B5778),
)

private val SeedDark = darkColorScheme(
    primary = Color(0xFF9FC9FF),
    secondary = Color(0xFFB6C7E3),
    tertiary = Color(0xFFD7BDE4),
)

/**
 * Monospace styles used for every piece of message content.
 *
 * `lineHeight` is set explicitly and `includeFontPadding` left at the platform default,
 * because diff rows must align to a constant grid — inconsistent row heights make added
 * and removed lines visually drift apart and are the fastest way to make a patch
 * unreadable on a narrow screen.
 */
object CodeTypography {
    val mono = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.sp,
    )
    val monoSmall = mono.copy(fontSize = 11.sp, lineHeight = 15.sp)
    val body = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 14.5.sp,
        lineHeight = 21.sp,
    )
    val quote = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 11.5.sp,
        lineHeight = 16.sp,
    )
}

private val AppTypography = Typography().let { base ->
    base.copy(
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = base.labelSmall.copy(textAlign = TextAlign.Start),
    )
}

@Composable
fun LkmlTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme = when {
        // Dynamic colour is Android 12+; below that the hand-picked seed schemes apply.
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> SeedDark
        else -> SeedLight
    }

    CompositionLocalProvider(
        LocalCodeColors provides if (darkTheme) DarkCodeColors else LightCodeColors,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = AppTypography,
            content = content,
        )
    }
}
