package com.showhow.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.showhow.ai.DetectionBox

/** The rounded chip the header rows are made of. */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Ink.dim,
    background: Color = Ink.cardHi,
) {
    Text(
        text,
        modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        color = color,
        style = MaterialTheme.typography.labelMedium,
    )
}

/**
 * The one wide action at the bottom of a screen.
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
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().height(60.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = onColor),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
    }
}

/** Same shape, quieter: the second choice in a pair. */
@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.height(60.dp),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Ink.line),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = Ink.text,
        ),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontSize = 18.sp)
    }
}

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
                    .clip(CircleShape)
                    .background(
                        when {
                            here -> Ink.green
                            done -> Ink.cardHi
                            else -> Color.Transparent
                        },
                    )
                    .border(
                        1.dp,
                        if (done || here) Color.Transparent else Ink.line,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${i + 1}",
                    color = when {
                        here -> Color.Black
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
fun DetectionOverlay(boxes: List<DetectionBox>, modifier: Modifier = Modifier) {
    if (boxes.isEmpty()) return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        for (b in boxes) {
            Box(
                Modifier
                    .offset(x = w * b.left.coerceIn(0f, 1f), y = h * b.top.coerceIn(0f, 1f))
                    .width(w * (b.right - b.left).coerceIn(0f, 1f))
                    .height(h * (b.bottom - b.top).coerceIn(0f, 1f))
                    .border(1.5.dp, Ink.green, RoundedCornerShape(2.dp)),
            ) {
                Text(
                    b.label + " " + "%.2f".format(b.score),
                    Modifier
                        .offset(y = (-9).dp)
                        .background(Ink.green)
                        .padding(horizontal = 4.dp, vertical = 1.dp),
                    color = Color.Black,
                    style = Mono,
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
 */
@Composable
fun Telemetry(rows: List<TelemetryRow>, modifier: Modifier = Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Ink.scrim)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.End,
    ) {
        for (r in rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.value, style = Mono, color = Ink.dim)
                Spacer(Modifier.width(6.dp))
                Text(r.label, style = Mono, color = Ink.text)
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
