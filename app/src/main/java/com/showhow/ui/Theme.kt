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
 * The field is violet rather than neutral black: the app is made of glass
 * ([Modifier.glass]), and glass over pure grey has nothing to be translucent
 * *of*. The panes pick up the field behind them, which is the whole effect.
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
    /** The field. Three values, because [Modifier.aurora] grades between them. */
    val bg = Color(0xFF0A0715)
    val bgTop = Color(0xFF161036)
    val bgDeep = Color(0xFF050409)

    /** Opaque fallbacks, for the few places that sit over a camera, not the field. */
    val card = Color(0xFF171132)
    val cardHi = Color(0xFF241C46)

    /** Hairlines and inert tracks. Translucent, so they read against any pane. */
    val line = Color(0x2EFFFFFF)

    val text = Color(0xFFF3EFFF)
    val dim = Color(0xFFA9A1CB)
    val faint = Color(0xFF6F6791)

    val blue = Color(0xFF7C5CFF)
    val teal = Color(0xFF35D0C6)
    val green = Color(0xFF3DDC97)
    val amber = Color(0xFFF5B33C)
    val red = Color(0xFFFF5D73)

    /** The light in the room, and the glass it falls on. */
    val violet = Color(0xFF8B5CF6)
    val indigo = Color(0xFF5B6BFF)
    val magenta = Color(0xFFD946EF)
    val glassTint = Color(0xFF1A1338)
    val shadow = Color(0xFF06040F)

    /** The orb, hot centre to cool rim. */
    val orbHot = Color(0xFFFF8FD8)
    val orbCool = Color(0xFF2B3FD9)

    /** Over a viewfinder, so text stays readable on a white laptop lid. */
    val scrim = Color(0xE60A0715)
    val scrimSoft = Color(0xA60A0715)
}

/** The telemetry panel and every number that has to line up in a column. */
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp)

private val showHowType = Typography().let { t ->
    t.copy(
        // Tighter tracking than Material default: at display sizes the stock
        // letter spacing reads as a wordmark, not as a headline on glass.
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp,
        ),
        headlineSmall = t.headlineSmall.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.3).sp,
        ),
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
            // Transparent, so nothing Material draws paints over the aurora.
            background = Color.Transparent,
            onBackground = Ink.text,
            surface = Color.Transparent,
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
