package com.showhow.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Stub layout. Wiring is real; the look is tomorrow's job. */
@Composable
fun LibraryScreen(vm: ShowHowViewModel) {
    val guides by vm.library.collectAsStateWithLifecycle()
    val easy by vm.easyMode.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("ShowHow", style = MaterialTheme.typography.headlineMedium)
        Text("${guides.size} guides on this phone", style = MaterialTheme.typography.bodySmall)

        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { vm.go(Screen.Show) }) { Text("Record a guide") }
            TextButton(onClick = { vm.go(Screen.Debug) }) { Text("Debug") }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = easy, onCheckedChange = vm::setEasyMode)
            Text("  Easy mode (overrides everything)")
        }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(guides, key = { it.id }) { g ->
                Card(
                    Modifier.fillMaxWidth().clickable { vm.go(Screen.Player(g.id)) },
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(g.title.ifBlank { g.id })
                        Text(
                            "${g.steps.size} steps  |  ${g.lang}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
