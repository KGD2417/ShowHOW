package com.showhow.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The wait between "Done" and the review.
 *
 * Transcribing ninety seconds of Hindi on a five-year-old phone is real work
 * taking real seconds, and a spinner would waste them. This screen spends them
 * saying what the phone is doing and where it is doing it, which is the whole
 * pitch in five lines a person can read while they put the screwdriver down.
 */
@Composable
fun ProcessingScreen(vm: ShowHowViewModel) {
    val stage by vm.buildProgress.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The one piece of decoration in the app, and it is here because this
        // is the one screen where a person has nothing to do but wait. It
        // breathes at the rate of a slow exhale, which is the pace the wait
        // should feel like.
        val pulse = rememberInfiniteTransition(label = "orb")
        val scale by pulse.animateFloat(
            initialValue = 0.94f,
            targetValue = 1.04f,
            animationSpec = infiniteRepeatable(
                tween(2600, easing = FastOutSlowInEasing),
                RepeatMode.Reverse,
            ),
            label = "breath",
        )
        Box(Modifier.size(132.dp).scale(scale).orb())

        Spacer(Modifier.height(36.dp))
        Text(
            "PROCESSING",
            style = MaterialTheme.typography.labelMedium,
            color = Ink.faint,
            modifier = Modifier.padding(bottom = 24.dp),
        )

        Column(Modifier.fillMaxWidth().glass(GlassShape).padding(18.dp)) {
            for ((s, label) in STAGES) {
                StageRow(label, state(stage, s))
            }
        }

        Spacer(Modifier.height(48.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("▫", color = Ink.faint, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.size(8.dp))
            Text(
                "All of this is happening on your phone.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.faint,
            )
        }
    }
}

private val STAGES = listOf(
    BuildStage.TRANSCRIBING to "Writing down what you said",
    BuildStage.CUTTING to "Cutting into steps",
    BuildStage.PHOTOS to "Picking a photo for each step",
    BuildStage.CAPTIONS to "Describing the photos",
    BuildStage.COACHING to "Writing the steps up for a beginner",
    BuildStage.SAVING to "Saving",
)

private enum class RowState { DONE, NOW, WAITING }

private fun state(current: BuildStage, row: BuildStage): RowState = when {
    current == BuildStage.DONE -> RowState.DONE
    current.ordinal > row.ordinal -> RowState.DONE
    current == row -> RowState.NOW
    else -> RowState.WAITING
}

@Composable
private fun StageRow(label: String, state: RowState) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(26.dp)
                .then(
                    when (state) {
                        RowState.DONE -> Modifier.glassAccent(Ink.green, CircleShape, 10.dp)
                        RowState.NOW -> Modifier.glassAccent(Ink.blue, CircleShape, 14.dp)
                        RowState.WAITING -> Modifier.glass(CircleShape, tone = 0.6f)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (state == RowState.DONE) {
                Text("✓", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.size(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (state == RowState.NOW) FontWeight.Bold else FontWeight.Normal,
            color = when (state) {
                RowState.WAITING -> Ink.faint
                else -> Ink.text
            },
        )
    }
}
