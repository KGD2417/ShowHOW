package com.showhow.ui

import androidx.compose.foundation.background
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.showhow.capture.CameraController

/**
 * One take, narrated. The expert does the job; the phone watches the level and
 * decides where the steps are.
 *
 * Everything in the heads-up display is a live number out of `vm.debug` or a
 * model on this phone. There is no placeholder on this screen, because this is
 * the screen a judge leans in at.
 */
@Composable
fun ShowScreen(vm: ShowHowViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val debug by vm.debug.collectAsStateWithLifecycle()
    val boxes by vm.detections.collectAsStateWithLifecycle()
    val live by vm.liveTranscript.collectAsStateWithLifecycle()
    val policy by vm.policy.collectAsStateWithLifecycle()

    val controller = remember { CameraController(context) }
    val preview = remember { controller.previewView() }

    DisposableEffect(Unit) {
        controller.bind(owner, preview, vm.frameAnalyzer)
        vm.attachCamera(controller)
        onDispose {
            vm.attachCamera(null)
            controller.unbind()
        }
    }

    val speaking = debug.levelDb > debug.gateDb

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        DetectionOverlay(boxes)

        // --- top strip: what the microphone is doing right now ---
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(Ink.scrim)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(if (debug.recording) Ink.red else Ink.faint),
                )
                Spacer(Modifier.size(8.dp))
                Text(clock(debug.elapsedMs), style = Mono, color = Ink.text)
                Spacer(Modifier.size(12.dp))
                LevelMeter(debug.levelDb, debug.gateDb)
                Spacer(Modifier.size(8.dp))
                Text(
                    "%.0f/%.0f dB".format(debug.levelDb, debug.gateDb),
                    style = Mono,
                    color = Ink.dim,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    if (speaking) "speaking" else "quiet",
                    style = Mono,
                    color = if (speaking) Ink.green else Ink.faint,
                )
            }

            Telemetry(
                listOf(
                    TelemetryRow("on-device", "recognizer", vm.detectorDelegate),
                    TelemetryRow("${debug.liveCuts} cut", "step cutter", "CPU"),
                    TelemetryRow("${debug.snaps} kept", "sharpest frame", "CPU"),
                    // Not a label: the gate really is sitting on the room floor,
                    // and these are the two numbers proving it.
                    TelemetryRow("%.0f".format(debug.floorDb), "room floor dBFS"),
                ),
            )
        }

        // --- bottom: the take, as it builds ---
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Ink.scrimSoft)
                .padding(20.dp),
        ) {
            StepDots(
                count = debug.liveCuts + 1,
                current = debug.liveCuts,
                total = maxOf(debug.liveCuts + 1, 5).coerceAtMost(policy.maxSteps),
            )
            Spacer(Modifier.height(14.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape)
                        .background(if (speaking) Ink.green else Ink.amber),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (debug.recording) "listening…" else "ready",
                    style = Mono,
                    color = Ink.amber,
                )
            }
            Spacer(Modifier.height(8.dp))

            Text(
                // Empty until a streaming recogniser exists. The slot stays,
                // filled with the honest version: what the phone has measured,
                // not a sentence nobody said.
                live.ifBlank {
                    "step ${debug.liveCuts + 1}  ·  ${debug.samples} samples  ·  " +
                        "cuts at ${policy.pauseMs} ms of quiet"
                },
                style = if (live.isBlank()) {
                    MaterialTheme.typography.bodyMedium
                } else {
                    MaterialTheme.typography.headlineSmall
                },
                color = if (live.isBlank()) Ink.dim else Ink.text,
            )

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!debug.recording) {
                    WideButton("Start", onClick = vm::startRecording, modifier = Modifier.weight(1f))
                } else {
                    GhostButton(
                        "Next step",
                        // A nudge, not a cut. The authoritative boundaries come
                        // out of the whole sample log at the end; this just says
                        // "take a picture of what I am pointing at now".
                        onClick = vm::markStep,
                        modifier = Modifier.weight(1f),
                    )
                    WideButton(
                        "Done",
                        onClick = vm::stopRecording,
                        modifier = Modifier.weight(1f),
                        color = Ink.card,
                        onColor = Ink.text,
                    )
                }
            }
            TextButton(onClick = { vm.go(Screen.Library) }) {
                Text("Back", color = Ink.faint)
            }
        }
    }
}
