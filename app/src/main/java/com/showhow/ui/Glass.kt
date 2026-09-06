package com.showhow.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.util.Random

/**
 * The glass. One file, because a look that is not applied everywhere is not a
 * look -- it is an inconsistency with a nice name.
 *
 * Three ideas, and everything on screen is made of them:
 *
 *   [aurora]  the room the app sits in: a deep violet field with two soft light
 *             sources and a pair of window streaks across it. Painted once,
 *             behind every screen.
 *   [glass]   a pane floating in that room: a translucent fill, a specular
 *             hairline that is bright where light lands and dim where it does
 *             not, and a shadow underneath.
 *   [grain]   the thing that stops all of it looking like a gradient from 2014.
 *             Film grain, tiled, at a few percent.
 *
 * The grain matters more than it sounds. A flat gradient on an OLED phone bands
 * into visible steps; noise at 4% dithers those steps away and reads as texture
 * rather than as a defect.
 */

/** Corner radii. Apple-wide, not Material-tight -- glass has no sharp edges. */
val GlassShape = RoundedCornerShape(24.dp)
val GlassShapeSmall = RoundedCornerShape(16.dp)

/**
 * A tile of white noise with random alpha, built once and repeated forever.
 *
 * 160x160 is big enough that the tile seam is invisible at 4% alpha and small
 * enough (100 KB) that nobody notices it in the heap.
 */
private val grainBrush: ShaderBrush by lazy {
    val n = 160
    val rnd = Random(11)
    val px = IntArray(n * n) { (rnd.nextInt(256) shl 24) or 0x00FFFFFF }
    val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
    bmp.setPixels(px, 0, n, 0, 0, n, n)
    ShaderBrush(ImageShader(bmp.asImageBitmap(), TileMode.Repeated, TileMode.Repeated))
}

/** Film grain over whatever this modifier is attached to. */
fun Modifier.grain(alpha: Float = 0.05f): Modifier = drawWithContent {
    drawContent()
    drawRect(grainBrush, alpha = alpha)
}

/**
 * The backdrop for the whole app: violet field, two light sources, two streaks.
 *
 * Drawn behind everything once in [ShowHowHost] rather than per screen, so a
 * screen change is a change of the glass on top and never a change of the room.
 */
fun Modifier.aurora(): Modifier = drawWithCache {
    val w = size.width
    val h = size.height

    // Not two stops. Four, on a diagonal, so the field has a direction to it.
    val field = Brush.linearGradient(
        0.00f to Ink.bgTop,
        0.30f to Ink.bg,
        0.68f to Ink.bg,
        1.00f to Ink.bgDeep,
        start = Offset(w * 0.9f, 0f),
        end = Offset(w * 0.1f, h),
    )
    // High and right, the way the reference lights its glass.
    val key = Brush.radialGradient(
        0.00f to Ink.violet.copy(alpha = 0.34f),
        0.38f to Ink.violet.copy(alpha = 0.13f),
        0.72f to Ink.indigo.copy(alpha = 0.05f),
        1.00f to Color.Transparent,
        center = Offset(w * 0.88f, -h * 0.04f),
        radius = h * 0.66f,
    )
    // Low and left, cooler and weaker: the bounce, not a second lamp.
    val bounce = Brush.radialGradient(
        0.00f to Ink.magenta.copy(alpha = 0.16f),
        0.45f to Ink.indigo.copy(alpha = 0.08f),
        1.00f to Color.Transparent,
        center = Offset(-w * 0.15f, h * 0.92f),
        radius = h * 0.55f,
    )
    // Two soft bands falling across the frame. Narrow stops, low alpha: this is
    // light through a window, and if you can point at where it starts it is too
    // strong.
    val streaks = Brush.linearGradient(
        0.00f to Color.Transparent,
        0.26f to Color.White.copy(alpha = 0.000f),
        0.35f to Color.White.copy(alpha = 0.042f),
        0.44f to Color.White.copy(alpha = 0.000f),
        0.58f to Color.White.copy(alpha = 0.000f),
        0.66f to Color.White.copy(alpha = 0.026f),
        0.74f to Color.White.copy(alpha = 0.000f),
        1.00f to Color.Transparent,
        start = Offset(w * 1.15f, -h * 0.15f),
        end = Offset(-w * 0.25f, h * 1.15f),
    )

    onDrawBehind {
        drawRect(field)
        drawRect(key)
        drawRect(bounce)
        drawRect(streaks)
        drawRect(grainBrush, alpha = 0.055f)
    }
}

/**
 * A pane of glass.
 *
 * The whole trick is the hairline. A single flat border reads as a box with a
 * stroke; a border whose alpha runs bright -> dim -> half-bright reads as an
 * edge catching a light that exists somewhere off screen, which is what makes
 * the pane look like an object rather than a rectangle.
 *
 * There is deliberately no elevation. Android draws an elevation shadow behind
 * the layer, and a layer this translucent shows it through -- on the phone that
 * came out as a dark rectangle floating inside every card. The hairline is what
 * makes a pane read as an object here, and it does not need help.
 *
 * @param tone how present the pane is. 1.0 is a card, ~1.6 a control that has
 *   to hold its own over a camera viewfinder.
 */
fun Modifier.glass(
    shape: Shape = GlassShape,
    tone: Float = 1f,
): Modifier = run {
    // The tint tracks tone all the way up -- that is what makes a pane over a
    // viewfinder readable. The sheen and the hairline stop climbing at 1.5:
    // past that they stop reading as light on glass and start reading as a
    // white box.
    val lit = tone.coerceAtMost(1.5f)
    this
        .clip(shape)
        .background(Ink.glassTint.copy(alpha = (0.30f * tone).coerceAtMost(0.86f)))
        .background(
            Brush.linearGradient(
                0.00f to Color.White.copy(alpha = 0.140f * lit),
                0.07f to Color.White.copy(alpha = 0.062f * lit),
                0.52f to Color.White.copy(alpha = 0.020f * lit),
                0.88f to Color.White.copy(alpha = 0.030f * lit),
                1.00f to Color.White.copy(alpha = 0.060f * lit),
            ),
        )
        .grain(0.045f)
        .border(1.dp, glassEdge(lit), shape)
}

/** The specular hairline: lit top-left, dark through the middle, a catch at the foot. */
private fun glassEdge(tone: Float) = Brush.linearGradient(
    0.00f to Color.White.copy(alpha = 0.44f * tone),
    0.28f to Color.White.copy(alpha = 0.12f * tone),
    0.62f to Color.White.copy(alpha = 0.06f * tone),
    1.00f to Color.White.copy(alpha = 0.24f * tone),
)

/**
 * The edge without the fill: for anything that paints its own pixels -- a
 * camera preview, a photograph -- and only needs the frame around it to belong
 * to the same set of objects as everything else.
 */
fun Modifier.glassFrame(
    radius: Dp = 24.dp,
    elevation: Dp = 16.dp,
): Modifier = this
    .shadow(elevation, RoundedCornerShape(radius), ambientColor = Ink.shadow, spotColor = Ink.shadow)
    .clip(RoundedCornerShape(radius))
    // Stroked after the content, not before it. A camera preview fills the box
    // edge to edge and paints straight over a border drawn underneath it.
    .drawWithCache {
        val edge = glassEdge(1.2f)
        val w = 1.dp.toPx()
        val r = CornerRadius(radius.toPx())
        onDrawWithContent {
            drawContent()
            drawRoundRect(
                edge,
                topLeft = Offset(w / 2f, w / 2f),
                size = Size(size.width - w, size.height - w),
                cornerRadius = r,
                style = Stroke(w),
            )
        }
    }

/**
 * Glass with a colour in it -- the one action on a screen, or a live state
 * worth tinting. Same pane, lit from inside.
 */
fun Modifier.glassAccent(
    color: Color,
    shape: Shape = GlassShape,
    elevation: Dp = 18.dp,
): Modifier = this
    .shadow(elevation, shape, ambientColor = color.copy(alpha = 0.5f), spotColor = Ink.shadow)
    .clip(shape)
    .background(
        Brush.linearGradient(
            0.00f to color.copy(alpha = 0.96f),
            0.55f to color.copy(alpha = 0.82f),
            1.00f to color.copy(alpha = 0.64f),
        ),
    )
    .background(
        Brush.linearGradient(
            0.00f to Color.White.copy(alpha = 0.26f),
            0.10f to Color.White.copy(alpha = 0.08f),
            0.60f to Color.Transparent,
            1.00f to Color.White.copy(alpha = 0.05f),
        ),
    )
    .grain(0.035f)
    .border(
        1.dp,
        Brush.linearGradient(
            0.00f to Color.White.copy(alpha = 0.55f),
            0.45f to Color.White.copy(alpha = 0.10f),
            1.00f to Color.White.copy(alpha = 0.22f),
        ),
        shape,
    )

/** A pane with something in it. The 90% case. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShape,
    tone: Float = 1f,
    padding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.glass(shape, tone).padding(padding), content = content)
}

/** The same, when the caller wants to stack things itself. */
@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    shape: Shape = GlassShape,
    tone: Float = 1f,
    padding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, tone).padding(padding), content = content)
}

/**
 * The orb from the reference: a soft sphere lit from the top-left, blue into
 * magenta. It is the one piece of decoration in the product, and it earns its
 * place by being the thing a person looks at while the phone thinks.
 */
fun Modifier.orb(): Modifier = drawWithCache {
    val r = size.minDimension / 2f
    val c = Offset(size.width / 2f, size.height / 2f)
    val body = Brush.radialGradient(
        0.00f to Ink.orbHot,
        0.35f to Ink.magenta,
        0.72f to Ink.violet,
        1.00f to Ink.orbCool,
        center = Offset(c.x - r * 0.32f, c.y - r * 0.38f),
        radius = r * 1.5f,
    )
    val sheen = Brush.radialGradient(
        0.00f to Color.White.copy(alpha = 0.40f),
        0.55f to Color.Transparent,
        center = Offset(c.x - r * 0.42f, c.y - r * 0.50f),
        radius = r * 0.7f,
    )
    val halo = Brush.radialGradient(
        0.55f to Color.Transparent,
        0.80f to Ink.violet.copy(alpha = 0.22f),
        1.00f to Color.Transparent,
        center = c,
        radius = r * 1.9f,
    )
    onDrawBehind {
        drawCircle(halo, radius = r * 1.9f, center = c)
        drawCircle(body, radius = r, center = c)
        drawCircle(sheen, radius = r, center = c)
        drawCircle(grainBrush, radius = r, center = c, alpha = 0.05f)
    }
}
