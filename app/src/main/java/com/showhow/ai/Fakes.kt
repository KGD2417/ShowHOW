package com.showhow.ai

import android.graphics.Bitmap
import java.io.File

/**
 * Canned answers, returned instantly, for the parts that have no real model yet.
 *
 * FakeAsr and FakeGestureSource used to live here. They are gone on purpose:
 * the production path runs VoskAsr and MediaPipeGestureSource, and falls back
 * to NoopAsr and NoGestures. A recogniser that invents nine Hindi words, or a
 * palm that waves itself every two seconds, is worse than one that says
 * nothing -- it looks like it works right up until the jury asks a question.
 */
class FakeCaptioner : Captioner {
    private val canned = listOf(
        "Hand on the lid, turning it anticlockwise",
        "Filter basket lifted out of the housing",
        "Rinsing the basket under running water",
        "Basket back in place, lid closed",
    )
    private var n = 0
    override suspend fun caption(jpg: File): String = canned[n++ % canned.size]
}

class FakeSceneCheck : SceneCheck {
    override fun compare(live: Bitmap, saved: Bitmap): Float = 0.86f
}

