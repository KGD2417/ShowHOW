package com.showhow.ui

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
import androidx.compose.ui.graphics.Brush
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
import com.showhow.ai.AnswerEvidence
import com.showhow.ai.Gesture
import com.showhow.capture.CameraController
import com.showhow.core.DwellLatch
import com.showhow.core.Mode
import com.showhow.core.StepCheck
import com.showhow.data.Guide
import com.showhow.data.Step
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    // The verified version when one exists, the working copy when it does not.
    // A learner must never be handed a half-finished edit of a guide an expert
    // has already checked.
    // Bumped when the detector re-reads the step photographs, so the screen
    // picks up captions that were written by a model this phone no longer runs.
    var recaptioned by remember(guideId) { mutableIntStateOf(0) }
    val guide = remember(guideId, recaptioned) { vm.guides.loadForLearner(guideId) }
    LaunchedEffect(guideId) { if (vm.refreshCaptions(guideId)) recaptioned++ }
    val mode by vm.mode.collectAsStateWithLifecycle()
    val reason by vm.reason.collectAsStateWithLifecycle()
    // The smoothed number the verdict is actually made on, not the raw scene
    // similarity that used to be shown beside it. Two measurements on one line
    // is how "84%" came to sit over "can't tell yet".
    val similarity by vm.confidence.collectAsStateWithLifecycle()
    val check by vm.stepCheck.collectAsStateWithLifecycle()
    val missing by vm.missingLabels.collectAsStateWithLifecycle()
    val detections by vm.detections.collectAsStateWithLifecycle()
    val policy by vm.policy.collectAsStateWithLifecycle()
    val readAloud by vm.readAloud.collectAsStateWithLifecycle()
    val listenLang by vm.listenLang.collectAsStateWithLifecycle()
    val translating by vm.translating.collectAsStateWithLifecycle()
    val mayAdvance by vm.mayAdvance.collectAsStateWithLifecycle()

    val scope = androidx.compose.runtime.rememberCoroutineScope()
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
    // The one picture of what this step should end up looking like, or null.
    // Null is an ordinary outcome, not a failure, and nothing below invents a
    // frame to fill the gap.
    val goal = remember(step.photo, guideId) { vm.guides.goalImage(guideId, step.photo) }

    fun goTo(i: Int) {
        index = i.coerceIn(0, guide.steps.lastIndex)
        holding = false
    }

    // The scene check compares the live camera to this step's photo. Told here
    // rather than in the ViewModel because the Player is what knows which step
    // a person is actually looking at.
    LaunchedEffect(step.photo, step.objects, step.caption, cameraOn) {
        // The step's own detector labels travel with the photograph, so the
        // cascade compares like with like: what the detector saw then against
        // what it sees now.
        vm.watchScene(
            if (cameraOn) goal else null,
            // The counted list where the guide has one. Splitting the caption
            // is the fallback for a guide written before it existed, and it
            // loses the repeats -- so two screws read as one until
            // refreshCaptions has been round.
            step.objects.ifEmpty {
                step.caption.split(",").map { it.trim() }.filter { it.isNotBlank() }
            },
        )
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

    // The coach's English instruction where there is one, the expert's own
    // words where there is not. Both are true; the rewritten one is followable.
    val spoken = step.instruction
        .ifBlank { step.transcript }
        .ifBlank { step.caption }
        .ifBlank { step.title }

    LaunchedEffect(step.startMs, guideId, readAloud, listenLang) {
        if (readAloud) {
            // The synthetic voice reads what the recogniser heard. Where it
            // misheard, the expert's own audio is one tap away and still right.
            player.pause()
            // In the learner's language when they asked for one, and in the
            // expert's when they did not. spokenIn falls back to the original
            // on any failure, so this is never silent.
            val heard = vm.spokenIn(guide, index, spoken)
            vm.speak(heard, listenLang.ifBlank { guide.lang })
        } else {
            vm.stopSpeaking()
            playStep(player, vm.guides.dir(guideId), vm.guides.takeFile(guideId), step)
        }
    }
    DisposableEffect(Unit) { onDispose { vm.stopSpeaking() } }

    // Does the bench in front of the learner look like the photograph of this
    // step finished? Latched, so a hand passing across it is not a page turn.
    val matchLatch = remember(guideId) { DwellLatch<Boolean>(policy.advanceOnMatchDwellMs) }
    var advancedOn by remember { mutableStateOf(-1L) }
    LaunchedEffect(step.startMs, holding, asking, index) {
        matchLatch.reset()
        // Never on the last step: there is nowhere to go, and a guide that
        // announces itself finished while the learner is still working is
        // worse than one that waits.
        if (policy.advanceOnMatchSimilarity <= 0f || index >= guide.steps.lastIndex) return@LaunchedEffect
        while (true) {
            // Held is the learner saying "wait", and the ask sheet is them
            // mid-question. Neither is a moment to turn the page.
            if (!holding && !asking) {
                // One decision, made in core off one frame's inputs. The
                // similarity number alone used to do this, and it hits its
                // threshold on a bench that is merely the same bench -- same
                // desk, same lighting, hand out of shot, nothing actually
                // done. mayAdvance wants the phone to have stopped moving,
                // the frame to reach the stricter correctness threshold, and
                // the detector's labels to agree where there are any to
                // disagree with.
                val fired = matchLatch.update(
                    android.os.SystemClock.elapsedRealtime(),
                    if (vm.mayAdvance.value) true else null,
                )
                if (fired != null && advancedOn != step.startMs) {
                    advancedOn = step.startMs
                    // Said in English: it is the app talking, not the expert.
                    vm.speak(MOVING_ON, "en")
                    goTo(index + 1)
                    return@LaunchedEffect
                }
            }
            delay(150)
        }
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
            // 0 means the step waits for a person, which is the default: a
            // repair step outlasts its narration by minutes.
            if (policy.autoAdvanceMs > 0 &&
                (player.playbackState == Player.STATE_ENDED || progress >= 1f)
            ) {
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

    Box(Modifier.fillMaxSize()) {
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
                    Text(
                        if (cameraOn) "▣  " + mode.name else mode.name,
                        Modifier
                            .glass(CircleShape, tone = 1.25f)
                            .clickable { cameraOn = !cameraOn }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
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

            // Nothing to show is nothing to show: with no camera and no goal
            // image this whole frame is skipped and the step is its instruction
            // and the expert's voice, which is a complete experience.
            if (cameraOn || goal != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(4f / 3f)
                        .glassFrame(24.dp)
                        .background(Color.Black),
                ) {
                    if (cameraOn) {
                        // What it looks like now, with the goal inset over it.
                        LiveView(vm, owner)
                        DetectionOverlay(detections)
                        goal?.let { GoalInset(it) }
                    } else {
                        // No camera, so the goal is the whole frame.
                        goal?.let { GoalImage(it) }
                    }
                }
            }

            // Advice, never a gate. Next below works at every one of these, and
            // there is deliberately no fourth value that would stop anyone.
            if (cameraOn) {
                Spacer(Modifier.height(8.dp))
                Text(
                    // The same percentage in every branch, because it is the
                    // number being judged and a learner who only sees it on
                    // one line reads the other lines as a different, hidden
                    // measurement. And where the picture matches but the
                    // detector is short of something, it says what -- "84%"
                    // and no page turn is the app looking broken; "84%, still
                    // looking for the screwdriver" is something to act on.
                    when {
                        check == StepCheck.UNCERTAIN ->
                            // Says which kind of nothing, because "hold still"
                            // and "this looks wrong" are different messages and
                            // only one of them is true here.
                            "Can't tell yet - hold the phone steady on your work"
                        check == StepCheck.CORRECT ->
                            "That looks like the photo for this step (%.0f%%)"
                                .format(similarity * 100)
                        missing.isNotEmpty() ->
                            "Nearly there (%.0f%%) - still looking for %s"
                                .format(similarity * 100, missing.take(2).joinToString(", "))
                        else ->
                            "Close to the photo for this step (%.0f%%)".format(similarity * 100)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (check) {
                        StepCheck.CORRECT -> Ink.green
                        StepCheck.LIKELY_CORRECT -> Ink.teal
                        StepCheck.UNCERTAIN -> Ink.dim
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                spoken,
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
                        translating -> "Putting it into ${LISTEN_NAMES[listenLang] ?: listenLang}"
                        advanceInMs > 0 -> "Next step in ${(advanceInMs / 1000) + 1}s"
                        // The same value the page turn reads, so the bar can
                        // never say "that looks right" about a bench the app is
                        // not willing to move on from.
                        mayAdvance -> "That looks right"

                        readAloud -> "Reading it out"
                        else -> "Playing your recording"
                    },
                    readAloud = readAloud,
                    onToggleVoice = { vm.setReadAloud(!readAloud) },
                    progress = progress,
                    position = "${index + 1} / ${guide.steps.size}",
                    accent = accent,
                    holding = holding,
                    listenLang = listenLang.ifBlank { guide.lang },
                    onCycleListenLang = {
                        val order = LISTEN_NAMES.keys.toList()
                        val now = listenLang.ifBlank { guide.lang }
                        vm.setListenLang(order[(order.indexOf(now) + 1) % order.size])
                    },
                    onAgain = {
                        if (readAloud) scope.launch {
                            vm.speak(vm.spokenIn(guide, index, spoken), listenLang.ifBlank { guide.lang })
                        }
                        else playStep(player, vm.guides.dir(guideId), vm.guides.takeFile(guideId), step)
                    },
                    onHold = {
                        holding = !holding
                        if (holding) player.pause() else player.play()
                    },
                    // One press: the sheet opens and the mic is already
                    // hot, because the learner's spare hand is holding a
                    // screwdriver and every extra tap is one they have to put
                    // it down for.
                    onAsk = {
                        asking = true
                        vm.startListening()
                    },
                )
            }

            ReasonBar(mode, reason)
        }

        if (asking) {
            AskSheet(
                vm = vm,
                guide = guide,
                stepNumber = index + 1,
                cameraOn = cameraOn,
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
private fun GoalInset(photo: File) {
    val bmp = decode(photo, 4) ?: return
    Box(
        Modifier
            .padding(10.dp)
            .width(120.dp)
            .glassFrame(14.dp, elevation = 12.dp),
    ) {
        Image(
            bmp.asImageBitmap(),
            contentDescription = "what this step should look like when it is done",
            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f),
            contentScale = ContentScale.Crop,
        )
        Text(
            "aiming for",
            Modifier.align(Alignment.BottomCenter).background(Ink.scrim).fillMaxWidth(),
            style = Mono,
            color = Ink.dim,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun GoalImage(photo: File) {
    val bmp = decode(photo, 2) ?: return
    Box(Modifier.fillMaxSize()) {
        Image(
            bmp.asImageBitmap(),
            contentDescription = "what this step should look like when it is done",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        // Says what the picture is for. Without it this reads as decoration;
        // with it, it is the thing to match your own work against.
        Text(
            "aiming for",
            Modifier.align(Alignment.BottomStart).background(Ink.scrim).padding(horizontal = 10.dp, vertical = 4.dp),
            style = Mono,
            color = Ink.dim,
        )
    }
}

@Composable
private fun CameraOffBanner(onTurnOn: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(GlassShapeSmall, tone = 0.9f)
            .padding(horizontal = 16.dp, vertical = 13.dp),
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
    readAloud: Boolean,
    listenLang: String,
    onCycleListenLang: () -> Unit,
    onAgain: () -> Unit,
    onHold: () -> Unit,
    onAsk: () -> Unit,
    onToggleVoice: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .glass(RoundedCornerShape(26.dp), tone = 1.15f)
            .padding(16.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Ink.text, style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // A choice, not a setting buried elsewhere: the expert's own
                // voice, or the phone reading what it heard.
                Text(
                    if (readAloud) "expert's voice" else "read it out",
                    Modifier.clickable(onClick = onToggleVoice),
                    color = accent,
                    style = MaterialTheme.typography.labelMedium,
                )
                // Only while the phone is doing the talking. Against the
                // expert's own recording the choice is meaningless -- that
                // audio is in the language it was spoken in and stays there.
                if (readAloud) {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        LISTEN_NAMES[listenLang] ?: listenLang,
                        Modifier
                            .glass(CircleShape, tone = 1.3f)
                            .clickable(onClick = onCycleListenLang)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        color = Ink.text,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(position, style = Mono, color = Ink.dim)
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(5.dp).clip(CircleShape).background(Ink.line)) {
            Box(
                Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(5.dp)
                    .clip(CircleShape)
                    // Lit at the head, so the bar reads as filling rather than
                    // as a block that happens to be a certain width.
                    .background(
                        Brush.horizontalGradient(
                            listOf(accent.copy(alpha = 0.55f), accent, Ink.magenta),
                        ),
                    ),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            RoundAction("↺", "Again", null, onAgain)
            RoundAction(if (holding) "▶" else "❚❚", if (holding) "Play" else "Hold", accent, onHold)
            RoundAction("?", "Ask", null, onAsk)
        }
    }
}

/**
 * The circular controls. Deliberately 64dp: HANDS mode exists for people with
 * wet or gloved hands, and a 48dp target is a miss for them.
 */
@Composable
private fun RoundAction(glyph: String, label: String, fill: Color?, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(64.dp)
                .then(
                    if (fill != null) Modifier.glassAccent(fill, CircleShape, elevation = 16.dp)
                    else Modifier.glass(CircleShape, tone = 1.25f),
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = if (fill == null) Ink.text else Color.White, fontSize = 20.sp)
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
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .glass(CircleShape, tone = 0.75f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
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
 * Ask about a step, out loud.
 *
 * Two answers come back and they are not the same kind of thing, so the sheet
 * shows them as two things:
 *
 *   the coach   an on-device Gemma that has read the whole guide. It can
 *               answer "which screwdriver", which no transcript contains --
 *               and that is exactly why anything it says beyond the guide is
 *               labelled. See [ShowHowViewModel.CoachAnswer].
 *   the guide   a token match over what the expert actually said, instant and
 *               incapable of inventing anything. It is the jump link, and it
 *               is the whole answer on a phone with no coach model.
 *
 * Nothing leaves the phone in either case, which is why that sentence stays.
 */
@Composable
private fun AskSheet(
    vm: ShowHowViewModel,
    guide: Guide,
    stepNumber: Int,
    /** Whether the coach will actually be handed a picture of the bench. */
    cameraOn: Boolean,
    onGoTo: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val query by vm.question.collectAsStateWithLifecycle()
    val listening by vm.listening.collectAsStateWithLifecycle()
    val coached by vm.coachAnswer.collectAsStateWithLifecycle()
    // The token match is instant, so it runs on every keystroke as it always
    // did. The coach costs seconds and runs only when asked.
    val found = remember(query) { if (query.isBlank()) null else vm.ask(guide, query) }

    // The sheet owns the mic and the pending answer: leaving must stop both, or
    // a learner who taps away has a hot mic and a model still thinking.
    DisposableEffect(Unit) { onDispose { vm.clearCoachAnswer() } }

    Box(Modifier.fillMaxSize().background(Ink.scrimSoft).clickable(onClick = onDismiss)) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .glass(
                    RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                    tone = 1.9f,
                )
                .padding(20.dp),
        ) {
            Text(
                "Ask about step $stepNumber",
                style = MaterialTheme.typography.titleLarge,
                color = Ink.text,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                // Says which of the two questions it can answer. With the
                // camera on it is handed this step's photo and the live frame
                // and can compare them; with it off it has the guide's words
                // and nothing else, and promising more would be a lie the
                // learner only finds out about in the answer.
                when {
                    !vm.coachPresent ->
                        "Answers come from what you recorded. Nothing is sent anywhere."
                    cameraOn ->
                        "It looks at your camera and the guide's photo of this " +
                            "step, on this phone. Nothing is sent anywhere."
                    else ->
                        "Answered from the guide's words on this phone. Turn the " +
                            "camera on and it can look at what you are holding too."
                },
                style = MaterialTheme.typography.bodySmall,
                color = Ink.dim,
            )
            Spacer(Modifier.height(14.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .glass(GlassShapeSmall, tone = 1.2f)
                    .border(1.5.dp, if (listening) Ink.green else Ink.blue, GlassShapeSmall)
                    .padding(14.dp),
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { vm.setQuestion(it) },
                    textStyle = TextStyle(color = Ink.text, fontSize = 18.sp),
                    cursorBrush = SolidColor(Ink.blue),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (query.isEmpty()) {
                    Text(
                        if (listening) "Listening..." else "Ask anything about this job.",
                        color = Ink.faint,
                        fontSize = 18.sp,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                // Speaking is the point -- the learner has a screwdriver in one
                // hand -- so it is the left, larger affordance. Typing stays for
                // a language with no model on the phone, and for a quiet room.
                Text(
                    if (listening) "Stop and ask" else "Speak the question",
                    Modifier.clickable {
                        if (listening) vm.stopListeningAndAsk(guide, stepNumber - 1)
                        else vm.startListening()
                    },
                    color = if (listening) Ink.green else Ink.blue,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (!listening && query.isNotBlank()) {
                    Text(
                        "Ask",
                        Modifier.clickable { vm.askCoach(guide, stepNumber - 1, query) },
                        color = Ink.blue,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            if (coached != null) {
                val a = coached!!
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glass(GlassShapeSmall, tone = 1.15f)
                        .border(1.dp, evidenceColour(a.evidence), GlassShapeSmall)
                        .padding(14.dp),
                ) {
                    Text(
                        when {
                            a.thinking -> "Thinking..."
                            a.text.isBlank() -> "The coach could not answer that."
                            // The labels that admit where an answer came from.
                            // Not a disclaimer: the difference between what the
                            // expert vouched for and what a model added, which
                            // a learner cannot see for themselves.
                            else -> evidenceLabel(a.evidence)
                        },
                        color = Ink.dim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (a.text.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(a.text, color = Ink.text, style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (found != null) {
                Text(
                    "The expert covered this in step ${found.stepIndex + 1}.",
                    color = Ink.dim,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "Go to step ${found.stepIndex + 1}",
                    Modifier.clickable { onGoTo(found.stepIndex) },
                    color = Ink.blue,
                    style = MaterialTheme.typography.labelLarge,
                )
            } else if (query.isNotBlank() && coached == null) {
                Text(
                    "Nothing in this guide mentions that." +
                        if (vm.coachPresent) " Ask the coach anyway." else "",
                    color = Ink.faint,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDismiss) { Text("Close", color = Ink.dim) }
        }
    }
}

/** What an answer rests on, in words a learner can act on. */
private fun evidenceLabel(e: AnswerEvidence): String = when (e) {
    AnswerEvidence.DIRECT_GUIDE_FACT -> "From this guide - the expert said this"
    AnswerEvidence.VISUAL_FACT -> "From what the camera can actually see"
    AnswerEvidence.GENERAL_KNOWLEDGE -> "General repair knowledge, not from this guide"
    AnswerEvidence.UNCERTAIN -> "Not covered here - check before you rely on this"
}

private fun evidenceColour(e: AnswerEvidence): Color = when (e) {
    AnswerEvidence.DIRECT_GUIDE_FACT -> Ink.green
    AnswerEvidence.VISUAL_FACT -> Ink.teal
    AnswerEvidence.GENERAL_KNOWLEDGE -> Ink.blue
    AnswerEvidence.UNCERTAIN -> Ink.amber
}

@Composable
private fun MissingGuide(guideId: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Nothing to play in $guideId.", color = Ink.text)
        TextButton(onClick = onBack) { Text("Library", color = Ink.blue) }
    }
}

@Composable
private fun decode(f: File, sample: Int) = remember(f.path) { decodeUpright(f, sample) }

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

/**
 * What the app says when the bench matches the step's photograph.
 *
 * Short on purpose: the learner has their hands in a laptop and is not
 * listening to a sentence. The next step's narration follows immediately.
 */
private const val MOVING_ON = "Great. Next step."

/**
 * The languages the synthetic voice will read a step in, and what to call them.
 *
 * The guide's own language is always in here, so cycling always comes home. A
 * language whose offline voice is not installed simply says nothing when
 * selected -- DeviceNarrator refuses network voices -- which is why the chip
 * shows what it is about to try rather than hiding the choice.
 */
private val LISTEN_NAMES = linkedMapOf("en" to "English", "hi" to "हिंदी")
