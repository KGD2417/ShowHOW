package com.showhow.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

/**
 * Runs a saved guide: one photo and one clip of the take per step.
 * Hands-free advance is tomorrow's gesture source; the buttons are here so the
 * flow is walkable tonight.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(vm: ShowHowViewModel, guideId: String) {
    val context = LocalContext.current
    val guide = remember(guideId) { vm.guides.load(guideId) }
    var index by remember { mutableIntStateOf(0) }
    val player = remember { ExoPlayer.Builder(context).build() }

    DisposableEffect(Unit) { onDispose { player.release() } }

    if (guide == null || guide.steps.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Text("Nothing to play in $guideId.")
            TextButton(onClick = { vm.go(Screen.Library) }) { Text("Library") }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        val step = guide.steps[index.coerceIn(0, guide.steps.lastIndex)]

        Text(
            "Step ${index + 1} of ${guide.steps.size}",
            style = MaterialTheme.typography.labelMedium,
        )
        Text(step.title, style = MaterialTheme.typography.headlineSmall)

        val photo = remember(step.photo) { File(vm.guides.dir(guideId), step.photo) }
        val bmp = remember(photo.path) {
            if (photo.exists()) BitmapFactory.decodeFile(photo.path) else null
        }
        bmp?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = step.caption,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
        }
        Text(step.caption)
        step.warning?.let {
            // Advice, not a gate. There is no disabled button anywhere below.
            Text("Heads up: $it", style = MaterialTheme.typography.bodySmall)
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = {
                val take = vm.guides.takeFile(guideId)
                if (take.exists()) {
                    player.setMediaItem(
                        MediaItem.Builder()
                            .setUri(take.toURI().toString())
                            .setClippingConfiguration(
                                MediaItem.ClippingConfiguration.Builder()
                                    .setStartPositionMs(step.startMs)
                                    .setEndPositionMs(step.endMs)
                                    .build(),
                            )
                            .build(),
                    )
                    player.prepare()
                    player.play()
                }
            }) { Text("Hear it") }
            Button(
                onClick = { if (index > 0) index-- },
            ) { Text("Back") }
            Button(
                onClick = { if (index < guide.steps.lastIndex) index++ },
            ) { Text("Next") }
            TextButton(onClick = { vm.go(Screen.Library) }) { Text("Done") }
        }
    }
}
