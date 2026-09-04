package com.showhow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.showhow.capture.CameraController

/** Camera preview, one record button, a live level readout. Stub visuals. */
@Composable
fun ShowScreen(vm: ShowHowViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val debug by vm.debug.collectAsStateWithLifecycle()
    val controller = remember { CameraController(context) }
    val preview = remember { controller.previewView() }

    DisposableEffect(Unit) {
        controller.bind(owner, preview)
        vm.attachCamera(controller)
        onDispose {
            vm.attachCamera(null)
            controller.unbind()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { preview }, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.padding(16.dp)) {
            Text(
                "%.1f / %.1f dBFS   floor %.1f   cuts %d".format(
                    debug.levelDb, debug.gateDb, debug.floorDb, debug.liveCuts,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (debug.recording) "Recording  ${debug.elapsedMs / 1000}s" else "Ready",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!debug.recording) {
                    Button(onClick = vm::startRecording) { Text("Start") }
                } else {
                    Button(onClick = {
                        vm.stopRecording { id -> if (id != null) vm.go(Screen.Review(id)) }
                    }) { Text("Stop") }
                }
                TextButton(onClick = { vm.go(Screen.Library) }) { Text("Back") }
                TextButton(onClick = { vm.go(Screen.Debug) }) { Text("Debug") }
            }
        }
    }
}
