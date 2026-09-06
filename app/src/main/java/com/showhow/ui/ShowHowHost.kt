package com.showhow.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/** The whole navigation graph. A `when`. */
@OptIn(UnstableApi::class)
@Composable
fun ShowHowHost(vm: ShowHowViewModel = viewModel()) {
    val screen by vm.screen.collectAsStateWithLifecycle()

    // The aurora is painted here and nowhere else, and it is painted *outside*
    // the inset so the field runs under the status bar and the gesture pill.
    // Every screen above it is glass over the same room.
    Box(Modifier.fillMaxSize().aurora()) {
        // targetSdk 36 means Android draws this app edge to edge whether it asks
        // to or not, so every screen would otherwise start underneath the status
        // bar and end underneath the gesture pill. One inset here beats five.
        Surface(
            Modifier.fillMaxSize().safeDrawingPadding(),
            color = Color.Transparent,
            contentColor = Ink.text,
        ) {
            when (val s = screen) {
                Screen.Library -> LibraryScreen(vm)
                Screen.Show -> ShowScreen(vm)
                Screen.Processing -> ProcessingScreen(vm)
                Screen.Debug -> DebugScreen(vm)
                is Screen.Review -> ReviewScreen(vm, s.guideId)
                is Screen.Player -> PlayerScreen(vm, s.guideId)
            }
        }
    }
}
