package com.showhow.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * One dark palette, because every screen in this product is looked at over a
 * workbench, a stove or an open laptop -- places with a bright thing in the
 * middle of the frame. A paper-white app next to a viewfinder is two different
 * exposures fighting each other.
 *
 * Colour carries meaning here and is not decoration:
 *
 *   [Ink.blue]   an action the user takes
 *   [Ink.teal]   the same action while the phone is being talked to, not touched
 *   [Ink.green]  something the phone actually measured -- a detection, a step
 *                that really happened, a stage that really finished
 *   [Ink.amber]  the phone is listening
 *   [Ink.red]    recording
 *
 * Nothing is ever greyed out to mean "you may not". Grey means "not now",
 * never "not allowed" -- there is no disabled control in this app.
 */
object Ink {
    val bg = Color(0xFF0B0B0D)
    val card = Color(0xFF17171A)
    val cardHi = Color(0xFF202025)
    val line = Color(0xFF2C2C32)
    val text = Color(0xFFF4F4F6)
    val dim = Color(0xFF9B9BA3)
    val faint = Color(0xFF6C6C74)

    val blue = Color(0xFF2F6BFF)
    val teal = Color(0xFF14B8A6)
    val green = Color(0xFF22C55E)
    val amber = Color(0xFFF59E0B)
    val red = Color(0xFFEF4444)

    /** Over a viewfinder, so text stays readable on a white laptop lid. */
    val scrim = Color(0xE60B0B0D)
    val scrimSoft = Color(0x990B0B0D)
}

/** The telemetry panel and every number that has to line up in a column. */
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)

private val showHowType = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
}

/**
 * Dark only, on purpose. A light mode would be a second set of contrast
 * decisions to check on a phone we get to hold for about twenty minutes.
 */
@Composable
fun ShowHowTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink.blue,
            onPrimary = Color.White,
            secondary = Ink.teal,
            onSecondary = Color.Black,
            background = Ink.bg,
            onBackground = Ink.text,
            surface = Ink.bg,
            onSurface = Ink.text,
            surfaceVariant = Ink.card,
            onSurfaceVariant = Ink.dim,
            outline = Ink.line,
            error = Ink.red,
        ),
        typography = showHowType,
        content = content,
    )
}
