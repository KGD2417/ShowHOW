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
                "Name it, check the steps, then save. Joining or splitting takes two taps.",
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.dim,
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
            "Save guide",
            onClick = {
                vm.saveEditing()
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
            Text(
                // What was actually said beats a caption a model guessed at, so
                // the transcript leads and the caption only fills a gap.
                step.transcript.ifBlank { step.caption.ifBlank { step.title } },
                style = MaterialTheme.typography.bodyLarge,
                color = Ink.text,
            )
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
                Row {
                    TextButton(onClick = onSplit) { Text("Split", color = Ink.blue) }
                    TextButton(onClick = onReRecord) {
                        Text(
                            if (recording) "Stop" else "Re-record",
                            color = if (recording) Ink.red else Ink.blue,
                        )
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
