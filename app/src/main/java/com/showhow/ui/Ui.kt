package com.showhow.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import com.showhow.ai.Detections
import java.io.File

/** The rounded chip the header rows are made of. Glass, like everything else. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Ink.dim,
    tone: Float = 1.15f,
) {
    Text(
        text,
        modifier
            .glass(CircleShape, tone = tone)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        color = color,
        style = MaterialTheme.typography.labelMedium,
    )
}

/** A pill that is a control. Same glass, plus a press. */
@Composable
fun GlassPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Ink.text,
    accent: Color? = null,
    tone: Float = 1.15f,
) {
    val shape = CircleShape
    Text(
        text,
        modifier
            .then(
                if (accent != null) Modifier.glassAccent(accent, shape, elevation = 12.dp)
                else Modifier.glass(shape, tone = tone),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        color = if (accent != null) Color.White else color,
        style = MaterialTheme.typography.labelLarge,
    )
}

/**
 * The one wide action at the bottom of a screen.
 *
 * Not a Material Button: a Button paints a flat container colour over its own
 * modifier, which is exactly the surface this look replaces. A Box that is
 * glass and clickable is the same control with the right skin.
 *
 * There is no `enabled` parameter and there will not be one. A control the user
 * cannot press is the app refusing to explain itself; if pressing it is a bad
 * idea, say so in a line of text above it and let them press it anyway.
 */
@Composable
fun WideButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Ink.blue,
    onColor: Color = Color.White,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(62.dp)
            .glassAccent(color, ButtonShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontSize = 18.sp, color = onColor)
    }
}

/** Same shape, quieter: the second choice in a pair. Clear glass, no colour. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .height(62.dp)
            .glass(ButtonShape, tone = 1.1f)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            Modifier.padding(horizontal = 20.dp),
            style = MaterialTheme.typography.titleMedium,
            fontSize = 18.sp,
            color = Ink.text,
        )
    }
}

/** Wide enough to read as a lozenge, tight enough to still be a button. */
private val ButtonShape = RoundedCornerShape(20.dp)

/**
 * The step timeline. Filled circles for steps that exist, green for the one
 * being talked about or watched now.
 */
@Composable
fun StepDots(count: Int, current: Int, modifier: Modifier = Modifier, total: Int = count) {
    val slots = maxOf(total, 1)
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        for (i in 0 until slots) {
            val done = i < count
            val here = i == current
            Box(
                Modifier
                    .size(if (here) 34.dp else 28.dp)
                    .then(
                        when {
                            here -> Modifier.glassAccent(Ink.green, CircleShape, elevation = 12.dp)
                            done -> Modifier.glass(CircleShape, tone = 1.3f)
                            else -> Modifier.glass(CircleShape, tone = 0.55f)
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${i + 1}",
                    color = when {
                        here -> Color.White
                        done -> Ink.text
                        else -> Ink.faint
                    },
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            if (i < slots - 1) {
                Spacer(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(if (i < count - 1) Ink.dim else Ink.line),
                )
            }
        }
    }
}

/**
 * Five bars against the live gate.
 *
 * They fill between the gate and 0 dBFS rather than between a fixed floor and
 * 0, so what you see is literally the decision the step cutter is making: any
 * bar lit at all means this sample counts as speech in this room.
 */
@Composable
fun LevelMeter(levelDb: Double, gateDb: Double, modifier: Modifier = Modifier) {
    val span = (0.0 - gateDb).coerceAtLeast(1.0)
    val lit = (((levelDb - gateDb) / span) * BARS).toInt().coerceIn(0, BARS)
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        for (i in 0 until BARS) {
            Box(
                Modifier
                    .padding(end = 2.dp)
                    .width(3.dp)
                    .height((6 + i * 3).dp)
                    .background(if (i < lit) Ink.green else Ink.line, RoundedCornerShape(1.dp)),
            )
        }
    }
}

private const val BARS = 5

/**
 * Boxes over the viewfinder for what the phone can actually see.
 *
 * Everything drawn here came out of a model on this device, with the score that
 * model returned. An empty list draws nothing: there is no placeholder box,
 * because a box with a number beside it is a claim.
 */
@Composable
fun DetectionOverlay(detections: Detections, modifier: Modifier = Modifier) {
    if (detections.boxes.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val w = maxWidth
        val h = maxHeight

        // PreviewView is FILL_CENTER: it scales the frame to *cover* the view
        // and throws away the overflow. Mapping 0..1 of the frame onto 0..1 of
        // the view ignores that crop, which is why a 4:3 frame on a 9:20 screen
        // put the box for a person on top of a laptop. Redo the same cover-fit
        // here so a box lands where the thing it names actually is.
        val viewAspect = (w / h).coerceAtLeast(0.01f)
        val frameAspect = detections.frameAspect.coerceAtLeast(0.01f)
        val shownW: Dp
        val shownH: Dp
        if (frameAspect > viewAspect) {
            shownH = h
            shownW = h * frameAspect
        } else {
            shownW = w
            shownH = w / frameAspect
        }
        val offX = (w - shownW) / 2
        val offY = (h - shownH) / 2

        for (b in detections.boxes) {
            val left = offX + shownW * b.left
            val top = offY + shownH * b.top
            val boxW = shownW * (b.right - b.left).coerceAtLeast(0f)
            val boxH = shownH * (b.bottom - b.top).coerceAtLeast(0f)
            Box(
                Modifier
                    .offset(x = left, y = top)
                    .width(boxW)
                    .height(boxH)
                    .border(1.5.dp, Ink.green, RoundedCornerShape(2.dp)),
            ) {
                Text(
                    b.label + " " + "%.2f".format(b.score),
                    Modifier
                        .background(Ink.green)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    color = Color.Black,
                    style = Mono,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}

/** One line of the telemetry panel: a live number, what it is, and where it ran. */
data class TelemetryRow(val value: String, val label: String, val delegate: String? = null)

/**
 * The monospace panel in the corner of the viewfinder.
 *
 * It exists to answer the only question a judge really asks about an offline
 * app, which is whether any of this is running here, so every line is a live
 * number or a named delegate and never a label on its own.
 *
 * Every line is one line. On the demo phone the labels wrapped -- "recognizer"
 * came out as "recog / nizer" -- which turned the thing that proves the app is
 * serious into the thing that makes it look unfinished.
 */
@Composable
fun Telemetry(rows: List<TelemetryRow>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .glass(RoundedCornerShape(14.dp), tone = 2.4f)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        for (r in rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.value, style = Mono, color = Ink.dim, maxLines = 1, softWrap = false)
                Spacer(Modifier.width(6.dp))
                Text(r.label, style = Mono, color = Ink.text, maxLines = 1, softWrap = false)
                r.delegate?.let {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        it,
                        Modifier
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (it == "CPU") Ink.line else Ink.red)
                            .padding(horizontal = 4.dp),
                        style = Mono,
                        color = Ink.text,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/** mm:ss, the only clock format this app has. */
fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(s / 60, s % 60)
}

/**
 * Decode a photo the right way up.
 *
 * CameraX records which way the phone was held as EXIF metadata rather than by
 * rotating the pixels, and BitmapFactory ignores EXIF -- so every step photo
 * taken in portrait came back on its side. ImageDecoder honours it.
 *
 * Software allocation is not optional: the scene check reads pixels back out
 * with getPixels, and a hardware bitmap cannot do that.
 *
 * @param sample how much to downscale by. The photos are already capped at
 *   1280x960 and nothing here is shown larger than a phone.
 */
fun decodeUpright(file: File, sample: Int): Bitmap? {
    if (!file.exists()) return null
    return runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
            decoder.setTargetSampleSize(sample.coerceAtLeast(1))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()
}
