package com.showhow.ui

import android.graphics.BitmapFactory
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.showhow.ai.Gesture
import com.showhow.capture.CameraController
import com.showhow.core.Mode
import com.showhow.data.Guide
import com.showhow.data.Step
import java.io.File
import kotlinx.coroutines.delay

/**
 * Running a guide. The screen the jury watches, and the only one with four
 * different shapes.
 *
 * The four come from [Mode], which the phone decides and the user never picks:
 *
 *   TAP    held, quiet, close     -- ordinary buttons
 *   TALK   flat on a counter      -- type legible from across the room
 *   HANDS  loud room, wet hands   -- big targets, and a palm moves the step
 *   EASY   the user's own setting -- the least on screen that still works
 *
 * The reason bar at the bottom is not decoration. It is the product's honesty
 * made visible: the phone says what it decided and why, in a sentence, every
 * time it changes its mind.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: ShowHowViewModel, guideId: String) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val guide = remember(guideId) { vm.guides.load(guideId) }
    val mode by vm.mode.collectAsStateWithLifecycle()
    val reason by vm.reason.collectAsStateWithLifecycle()
    val similarity by vm.sceneSimilarity.collectAsStateWithLifecycle()
    val boxes by vm.detections.collectAsStateWithLifecycle()
    val policy by vm.policy.collectAsStateWithLifecycle()

    var index by remember { mutableIntStateOf(0) }
    var cameraOn by remember { mutableStateOf(false) }
    var asking by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }

    val player = remember { ExoPlayer.Builder(context).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    if (guide == null || guide.steps.isEmpty()) {
        MissingGuide(guideId) { vm.go(Screen.Library) }
        return
    }

    val step = guide.steps[index.coerceIn(0, guide.steps.lastIndex)]
    val photo = remember(step.photo, guideId) { File(vm.guides.dir(guideId), step.photo) }

    fun goTo(i: Int) {
        index = i.coerceIn(0, guide.steps.lastIndex)
        holding = false
    }

    // The scene check compares the live camera to this step's photo. Told here
    // rather than in the ViewModel because the Player is what knows which step
    // a person is actually looking at.
    LaunchedEffect(step.photo, cameraOn) {
        vm.watchScene(if (cameraOn) photo else null)
    }
    DisposableEffect(Unit) { onDispose { vm.watchScene(null) } }

    // Hand signs are a second way in, never the only one: every one of these
    // has a button beside it that does the same thing.
    LaunchedEffect(Unit) {
        vm.gestures.collect { g ->
            when (g) {
                Gesture.OPEN_PALM -> goTo(index + 1)
                Gesture.FIST -> goTo(index - 1)
                // Read the step through index rather than closing over the one
                // that happened to be current when this collector started.
                Gesture.THUMB_UP -> playStep(
                    player,
                    vm.guides.dir(guideId),
                    vm.guides.takeFile(guideId),
                    guide.steps[index.coerceIn(0, guide.steps.lastIndex)],
                )
                else -> Unit
            }
        }
    }

    LaunchedEffect(step.startMs, guideId) {
        playStep(player, vm.guides.dir(guideId), vm.guides.takeFile(guideId), step)
    }

    // Playback position, and the pause after it before the next step.
    var progress by remember { mutableStateOf(0f) }
    var advanceInMs by remember { mutableStateOf(-1L) }
    LaunchedEffect(step.startMs, holding) {
        advanceInMs = -1L
        while (true) {
            if (holding) {
                delay(120)
                continue
            }
            val span = (step.endMs - step.startMs).coerceAtLeast(1)
            progress = (player.currentPosition.toFloat() / span).coerceIn(0f, 1f)
            if (player.playbackState == Player.STATE_ENDED || progress >= 1f) {
                if (advanceInMs < 0) advanceInMs = policy.autoAdvanceMs
                advanceInMs -= 120
                if (advanceInMs <= 0) {
                    if (index < guide.steps.lastIndex) goTo(index + 1) else advanceInMs = -1L
                }
            }
            delay(120)
        }
    }

    val easy = mode == Mode.EASY
    val big = mode == Mode.TALK || mode == Mode.EASY
    val accent = if (mode == Mode.TALK) Ink.teal else Ink.blue

    Box(Modifier.fillMaxSize().background(Ink.bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(22.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Step ${index + 1} of ${guide.steps.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.dim,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (cameraOn) {
                        Text("▣ ", color = Ink.green, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(
                        mode.name,
                        Modifier.clickable { cameraOn = !cameraOn },
                        color = if (cameraOn) Ink.green else Ink.teal,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            if (!cameraOn && !easy) {
                CameraOffBanner { cameraOn = true }
                Spacer(Modifier.height(12.dp))
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black),
            ) {
                if (cameraOn) {
                    LiveView(vm, owner)
                    DetectionOverlay(boxes)
                    ShouldLookLike(photo)
                } else {
                    StepPhoto(photo)
                }
            }

            // Advice, never a gate. Next below still works at any similarity.
            if (cameraOn && similarity > 0f && similarity < policy.sceneAdviseMinSimilarity) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "This does not look much like the photo yet (%.0f%%)".format(similarity * 100),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Ink.amber,
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                step.transcript.ifBlank { step.caption.ifBlank { step.title } },
                style = MaterialTheme.typography.headlineSmall,
                fontSize = if (big) 34.sp else 24.sp,
                lineHeight = if (big) 42.sp else 32.sp,
                color = Ink.text,
                textAlign = if (big) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )

            if (step.modeHint.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(step.modeHint, color = Ink.amber, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.weight(1f))

            Row(
                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GhostButton("Back", onClick = { goTo(index - 1) }, modifier = Modifier.weight(1f))
                WideButton(
                    if (index == guide.steps.lastIndex) "Done" else "Next",
                    onClick = {
                        if (index == guide.steps.lastIndex) vm.go(Screen.Library)
                        else goTo(index + 1)
                    },
                    modifier = Modifier.weight(1.6f),
                    color = accent,
                )
            }

            if (!easy) {
                AudioBar(
                    label = when {
                        holding -> "Held"
                        advanceInMs > 0 -> "Next step in ${(advanceInMs / 1000) + 1}s"
                        else -> "Playing your recording"
                    },
                    progress = progress,
                    position = "${index + 1} / ${guide.steps.size}",
                    accent = accent,
                    holding = holding,
                    onAgain = { playStep(player, vm.guides.dir(guideId), vm.guides.takeFile(guideId), step) },
                    onHold = {
                        holding = !holding
                        if (holding) player.pause() else player.play()
                    },
                    onAsk = { asking = true },
                )
            }

            ReasonBar(mode, reason)
        }

        if (asking) {
            AskSheet(
                vm = vm,
                guide = guide,
                stepNumber = index + 1,
                onGoTo = { i -> goTo(i); asking = false },
                onDismiss = { asking = false },
            )
        }
    }
}

/** The live camera, bound to the one analyzer everything else reads from. */
@Composable
private fun LiveView(vm: ShowHowViewModel, owner: androidx.lifecycle.LifecycleOwner) {
    val context = LocalContext.current
    val controller = remember { CameraController(context) }
    val preview = remember { controller.previewView() }
    DisposableEffect(Unit) {
        controller.bind(owner, preview, vm.frameAnalyzer)
        onDispose { controller.unbind() }
    }
    AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
}

/** The saved photo, tucked in the corner, so both can be seen at once. */
@Composable
private fun ShouldLookLike(photo: File) {
    val bmp = decode(photo, 4) ?: return
    Box(
        Modifier
            .padding(10.dp)
            .width(120.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Ink.line, RoundedCornerShape(8.dp)),
    ) {
        Image(
            bmp.asImageBitmap(),
            contentDescription = "what this step should look like",
            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
            contentScale = ContentScale.Crop,
        )
        Text(
            "should look like",
            Modifier.align(Alignment.BottomCenter).background(Ink.scrim).fillMaxWidth(),
            style = Mono,
            color = Ink.dim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StepPhoto(photo: File) {
    val bmp = decode(photo, 2)
    if (bmp == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No photo for this step", color = Ink.faint)
        }
    } else {
        Image(
            bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun CameraOffBanner(onTurnOn: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Ink.card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "Turn the camera on to see your work",
            color = Ink.dim,
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Turn on",
            Modifier.clickable(onClick = onTurnOn),
            color = Ink.blue,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun AudioBar(
    label: String,
    progress: Float,
    position: String,
    accent: Color,
    holding: Boolean,
    onAgain: () -> Unit,
    onHold: () -> Unit,
    onAsk: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Ink.card)
            .padding(14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Ink.text, style = MaterialTheme.typography.bodyMedium)
            Text(position, style = Mono, color = Ink.dim)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Ink.line)) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(accent),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RoundAction("↺", "Again", Ink.card, onAgain)
            RoundAction(if (holding) "▶" else "❚❚", if (holding) "Play" else "Hold", accent, onHold)
            RoundAction("?", "Ask", Ink.card, onAsk)
        }
    }
}

/**
 * The circular controls. Deliberately 64dp: HANDS mode exists for people with
 * wet or gloved hands, and a 48dp target is a miss for them.
 */
@Composable
private fun RoundAction(glyph: String, label: String, fill: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(fill)
                .border(1.dp, if (fill == Ink.card) Ink.line else Color.Transparent, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = if (fill == Ink.card) Ink.text else Color.White, fontSize = 20.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = Ink.dim)
    }
}

/**
 * What the phone decided and why, in the phone's own words.
 *
 * Never hidden and never empty. If a judge asks why the screen just changed,
 * the answer is already on it.
 */
@Composable
private fun ReasonBar(mode: Mode, reason: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(7.dp).clip(CircleShape).background(
                when (mode) {
                    Mode.HANDS -> Ink.green
                    Mode.TALK -> Ink.teal
                    Mode.EASY -> Ink.amber
                    Mode.TAP -> Ink.blue
                },
            ),
        )
        Spacer(Modifier.width(8.dp))
        Text(mode.name, style = Mono, color = Ink.text)
        Spacer(Modifier.width(8.dp))
        Text(reason.substringAfter("<-").trim(), style = Mono, color = Ink.faint, maxLines = 1)
    }
}

/**
 * Ask about a step.
 *
 * The answer is a step of this guide, found by matching the question against
 * what the expert said. There is no model here and nothing leaves the phone,
 * which is exactly why the sheet can promise that in a sentence and mean it.
 */
@Composable
private fun AskSheet(
    vm: ShowHowViewModel,
    guide: Guide,
    stepNumber: Int,
    onGoTo: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val answer = remember(query) { if (query.isBlank()) null else vm.ask(guide, query) }

    Box(Modifier.fillMaxSize().background(Ink.scrimSoft).clickable(onClick = onDismiss)) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .background(Ink.card)
                .padding(20.dp),
        ) {
            Text(
                "Ask about step $stepNumber",
                style = MaterialTheme.typography.titleLarge,
                color = Ink.text,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Answers come from what you recorded. Nothing is sent anywhere.",
                style = MaterialTheme.typography.bodySmall,
                color = Ink.dim,
            )
            Spacer(Modifier.height(14.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.5.dp, Ink.blue, RoundedCornerShape(10.dp))
                    .padding(14.dp),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    textStyle = TextStyle(color = Ink.text, fontSize = 18.sp),
                    cursorBrush = SolidColor(Ink.blue),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.isEmpty()) {
                    Text("Ask anything about this job.", color = Ink.faint, fontSize = 18.sp)
                }
            }

            Spacer(Modifier.height(16.dp))
            if (answer != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Ink.green, RoundedCornerShape(10.dp))
                        .padding(14.dp),
                ) {
                    Text(
                        "That is step ${answer.stepIndex + 1}. Here is what you said.",
                        color = Ink.dim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        answer.transcript,
                        color = Ink.text,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            "Go to step ${answer.stepIndex + 1}",
                            Modifier.clickable { onGoTo(answer.stepIndex) },
                            color = Ink.blue,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            } else if (query.isNotBlank()) {
                Text(
                    "Nothing in this guide mentions that.",
                    color = Ink.faint,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text("Close", color = Ink.dim) }
        }
    }
}

@Composable
private fun MissingGuide(guideId: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Ink.bg).padding(24.dp)) {
        Text("Nothing to play in $guideId.", color = Ink.text)
        TextButton(onClick = onBack) { Text("Library", color = Ink.blue) }
    }
}

@Composable
private fun decode(f: File, sample: Int) = remember(f.path) {
    if (f.exists()) {
        runCatching {
            BitmapFactory.decodeFile(
                f.path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            )
        }.getOrNull()
    } else {
        null
    }
}

/**
 * Play one step: its own re-recorded clip if it has one, otherwise the slice of
 * the original take that belongs to it.
 */
@OptIn(UnstableApi::class)
private fun playStep(player: ExoPlayer, folder: File, take: File, step: Step) {
    val override = if (step.audio.isNotBlank()) File(folder, step.audio) else null
    val source = override?.takeIf { it.exists() } ?: take
    if (!source.exists()) return
    val item = MediaItem.Builder().setUri(source.toURI().toString())
    if (override == null) {
        item.setClippingConfiguration(
            MediaItem.ClippingConfiguration.Builder()
                .setStartPositionMs(step.startMs)
                .setEndPositionMs(step.endMs)
                .build(),
        )
    }
    player.setMediaItem(item.build())
    player.prepare()
    player.play()
}
