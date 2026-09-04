package com.showhow.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** What the cutter produced, before it goes in the library. Stub visuals. */
@Composable
fun ReviewScreen(vm: ShowHowViewModel, guideId: String) {
    val guide = remember(guideId) { vm.guides.load(guideId) }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Review", style = MaterialTheme.typography.headlineSmall)
        if (guide == null) {
            Text("Guide $guideId is missing.")
        } else {
            Text(
                "${guide.steps.size} steps cut from one take",
                style = MaterialTheme.typography.bodySmall,
            )
            LazyColumn(
                Modifier.weight(1f).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(guide.steps) { _, s ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(s.title)
                            Text(s.caption, style = MaterialTheme.typography.bodySmall)
                            Text(
                                "${s.startMs} - ${s.endMs} ms   ${s.photo}",
                                style = MaterialTheme.typography.labelSmall,
                            )
                            // Warnings advise. Nothing here blocks the button below.
                            s.warning?.let { Text("Heads up: $it") }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.go(Screen.Player(guideId)) }) { Text("Play it") }
                TextButton(onClick = { vm.go(Screen.Library) }) { Text("Library") }
            }
        }
    }
}
