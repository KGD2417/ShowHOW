package com.showhow.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.showhow.data.Step
import java.io.File

/**
 * Twenty seconds to fix the cuts, which is the promise the pitch makes.
 *
 * Everything on this screen is one tap: play a step to hear it, Split to cut
 * one in two, Join to fold one into the step above. Nothing here is ever
 * disabled -- a step that cannot usefully be split just splits into two short
 * ones, and that is the user's business, not the app's.
 */
@OptIn(UnstableApi::class)
@Composable
fun ReviewScreen(vm: ShowHowViewModel, guideId: String) {
    val context = LocalContext.current
    val guide by vm.editing.collectAsStateWithLifecycle()
    val reRecording by vm.reRecording.collectAsStateWithLifecycle()
    val player = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(Unit) { onDispose { player.release() } }

    val g = guide
    if (g == null || g.steps.isEmpty()) {
        Column(Modifier.fillMaxSize().background(Ink.bg).padding(24.dp)) {
            Text("Nothing was cut from that take.", color = Ink.text)
            Spacer(Modifier.height(8.dp))
            Text(
                "The room may have been too loud for the gate to find a pause.",
                color = Ink.dim,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { vm.go(Screen.Library) }) { Text("Library", color = Ink.blue) }
        }
        return
    }

    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(28.dp))
            // The name of the job, and the only place it can be set. It is what
            // the Library lists and what the coach is told the job is, so
            // leaving it as "New job" costs more than a blank line on screen.
            Box {
                BasicTextField(
                    value = g.title,
                    onValueChange = vm::setTitle,
                    textStyle = TextStyle(color = Ink.text, fontSize = 26.sp),
                    cursorBrush = SolidColor(Ink.blue),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (g.title.isBlank()) {
                    Text("Name this job", color = Ink.faint, fontSize = 26.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (g.verified) {
                    "Verified. Any edit puts it back to a draft until you verify again."
                } else {
                    "Draft. Name it, fix the steps, then verify - learners are given " +
                        "the verified version."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (g.verified) Ink.green else Ink.dim,
            )
            Spacer(Modifier.height(16.dp))

            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = 110.dp),
            ) {
                itemsIndexed(g.steps, key = { i, s -> "$i-${s.startMs}" }) { i, s ->
                    if (i > 0) JoinDivider { vm.joinSteps(i) }
                    StepCard(
                        n = i + 1,
                        step = s,
                        folder = vm.guides.dir(g.id),
                        recording = reRecording == i,
                        canMoveUp = i > 0,
                        canMoveDown = i < g.steps.lastIndex,
                        canDelete = g.steps.size > 1,
                        onEdit = { text -> vm.editStep(i, text) },
                        onMove = { delta -> vm.moveStep(i, delta) },
                        onDelete = { vm.deleteStep(i) },
                        onPlay = {
                            play(player, vm.guides.dir(g.id), vm.guides.takeFile(g.id), s)
                        },
                        onSplit = { vm.splitStep(i) },
                        onReRecord = {
                            if (reRecording == i) vm.stopReRecord() else vm.startReRecord(i)
                        },
                    )
                }
            }
        }

        WideButton(
            if (g.verified) "Verified - save changes" else "This is right - verify",
            onClick = {
                // Nothing may block this. A coach warning, a GENERAL provenance
                // or a step with no photograph are all a model's opinion, and a
                // model does not get a vote on whether an expert may sign off
                // their own work.
                vm.verifyEditing()
                vm.go(Screen.Library)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 20.dp, vertical = 24.dp),
        )
    }
}

@Composable
private fun StepCard(
    n: Int,
    step: Step,
    folder: File,
    recording: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    canDelete: Boolean,
    onEdit: (String) -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    onPlay: () -> Unit,
    onSplit: () -> Unit,
    onReRecord: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.card)
            .border(1.dp, Ink.line, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(
            "$n",
            Modifier.width(20.dp),
            style = MaterialTheme.typography.labelLarge,
            color = Ink.faint,
        )
        Thumbnail(folder, step.photo)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            // The line a learner will read, and the expert's to correct. Typing
            // here writes Step.instruction and never the transcript: the
            // transcript is the record of what was said out loud and stays it.
            val shown = step.instruction.ifBlank { step.transcript.ifBlank { step.caption } }
            Box {
                BasicTextField(
                    value = shown,
                    onValueChange = onEdit,
                    textStyle = TextStyle(color = Ink.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(Ink.blue),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (shown.isBlank()) {
                    Text("Say what happens in this step", color = Ink.faint, fontSize = 16.sp)
                }
            }
            // What the expert actually said, kept in view beneath their edit, so
            // a correction can be checked against the original instead of
            // replacing it silently.
            if (step.instruction.isNotBlank() && step.transcript.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "you said: " + step.transcript,
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.faint,
                )
            }
            if (step.aside) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "marked as an aside, not part of the job",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.dim,
                )
            }
            step.warning?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(4.dp))
                // Advice, never a gate. It does not stop verification.
                Text(it, style = MaterialTheme.typography.labelSmall, color = Ink.amber)
            }
            if (recording) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Recording this step. Say it again, then tap Stop.",
                    style = MaterialTheme.typography.labelMedium,
                    color = Ink.red,
                )
            } else if (step.audio.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "re-recorded",
                    style = MaterialTheme.typography.labelSmall,
                    color = Ink.green,
                )
            }
            if (step.modeHint.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                // Advice, never a gate. Every control below still works.
                Text(step.modeHint, style = MaterialTheme.typography.labelSmall, color = Ink.amber)
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    Modifier.clickable(onClick = onPlay),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("▶", color = Ink.text)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        seconds(step.endMs - step.startMs),
                        style = Mono,
                        color = Ink.dim,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canMoveUp) {
                        TextButton(onClick = { onMove(-1) }) { Text("^", color = Ink.dim) }
                    }
                    if (canMoveDown) {
                        TextButton(onClick = { onMove(1) }) { Text("v", color = Ink.dim) }
                    }
                    TextButton(onClick = onSplit) { Text("Split", color = Ink.blue) }
                    TextButton(onClick = onReRecord) {
                        Text(
                            if (recording) "Stop" else "Re-record",
                            color = if (recording) Ink.red else Ink.blue,
                        )
                    }
                    // Never offered for the last step: a guide with no steps is
                    // one the Player refuses to open.
                    if (canDelete) {
                        TextButton(onClick = onDelete) { Text("Delete", color = Ink.red) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(folder: File, name: String) {
    val bmp = remember(folder.path, name) {
        if (name.isBlank()) null else decodeUpright(File(folder, name), 8)
    }
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).background(Ink.cardHi),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text("—", color = Ink.faint)
        }
    }
}

/** The control that folds this step into the one above it. */
@Composable
private fun JoinDivider(onJoin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(1.dp).background(Ink.line))
        Text(
            "↳ Join",
            Modifier
                .clickable(onClick = onJoin)
                .padding(horizontal = 14.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = Ink.dim,
        )
        Box(Modifier.weight(1f).height(1.dp).background(Ink.line))
    }
}

/** A re-recorded clip if the step has one, otherwise its slice of the take. */
@OptIn(UnstableApi::class)
private fun play(player: ExoPlayer, folder: File, take: File, step: Step) {
    val override = if (step.audio.isNotBlank()) File(folder, step.audio) else null
    val source = override?.takeIf { it.exists() } ?: take
    if (!source.exists()) return
    val item = androidx.media3.common.MediaItem.Builder().setUri(source.toURI().toString())
    if (override == null) {
        item.setClippingConfiguration(
            androidx.media3.common.MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(step.startMs)
                .setEndPositionMs(step.endMs)
                .build(),
        )
    }
    player.setMediaItem(item.build())
    player.prepare()
    player.play()
}

private fun seconds(ms: Long): String = "0:%02d".format((ms / 1000).coerceAtLeast(0))
