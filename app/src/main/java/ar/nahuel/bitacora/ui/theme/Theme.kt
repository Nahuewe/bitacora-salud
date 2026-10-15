package ar.nahuel.bitacora.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

@Immutable
data class Ink(
    val paper: Color,
    val ink: Color,
    val inkSoft: Color,
    val rule: Color,
    val ruleStrong: Color,
    val barMuted: Color,
    val signal: Color,
    val good: Color,
)

private val LightInk = Ink(
    paper = Color(0xFFF4F1EA),
    ink = Color(0xFF1C1B19),
    inkSoft = Color(0xFF5E5A52),
    rule = Color(0xFFD9D2C4),
    ruleStrong = Color(0xFF1C1B19),
    barMuted = Color(0xFFB7AF9F),
    signal = Color(0xFFB13A0A),
    good = Color(0xFF2F6B3A),
)

private val DarkInk = Ink(
    paper = Color(0xFF161514),
    ink = Color(0xFFECE7DD),
    inkSoft = Color(0xFFA39E93),
    rule = Color(0xFF34312D),
    ruleStrong = Color(0xFFECE7DD),
    barMuted = Color(0xFF5A554D),
    signal = Color(0xFFF08A4B),
    good = Color(0xFF8CC29A),
)

val LocalInk = staticCompositionLocalOf { LightInk }

object Type {
    private val figuresFamily = FontFamily.Monospace

    val masthead = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Black, fontSize = 13.sp, letterSpacing = 2.4.sp)
    val sectionLabel = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.6.sp)
    val headline = TextStyle(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal, fontSize = 30.sp, lineHeight = 34.sp)
    val noteTitle = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
    val body = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 21.sp)
    val small = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp)
    val figure = TextStyle(fontFamily = figuresFamily, fontSize = 14.sp, fontFeatureSettings = "tnum")
    val figureSmall = TextStyle(fontFamily = figuresFamily, fontSize = 12.sp, fontFeatureSettings = "tnum")
    val figureLarge = TextStyle(fontFamily = figuresFamily, fontWeight = FontWeight.Bold, fontSize = 26.sp, fontFeatureSettings = "tnum")
}

@Composable
fun BitacoraTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ink = if (dark) DarkInk else LightInk
    val scheme = if (dark) {
        darkColorScheme(
            primary = ink.ink, onPrimary = ink.paper, background = ink.paper, onBackground = ink.ink,
            surface = ink.paper, onSurface = ink.ink, onSurfaceVariant = ink.inkSoft, outline = ink.rule, error = ink.signal,
        )
    } else {
        lightColorScheme(
            primary = ink.ink, onPrimary = ink.paper, background = ink.paper, onBackground = ink.ink,
            surface = ink.paper, onSurface = ink.ink, onSurfaceVariant = ink.inkSoft, outline = ink.rule, error = ink.signal,
        )
    }
    MaterialTheme(colorScheme = scheme, typography = Typography(bodyLarge = Type.body)) {
        CompositionLocalProvider(LocalInk provides ink, content = content)
    }
}
