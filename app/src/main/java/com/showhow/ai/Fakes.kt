package com.showhow.ai

import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Canned answers, returned instantly, for the parts that have no real model yet.
 *
 * FakeAsr used to live here. It is gone on purpose: the production path now
 * runs VoskAsr and falls back to NoopAsr, because a recogniser that invents
 * nine Hindi words when the model is missing is worse than one that says
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

class FakeGestureSource : GestureSource {
    override fun start(): Flow<Gesture> = flow {
        // One palm every couple of seconds: enough to walk a demo forward.
        while (true) {
            kotlinx.coroutines.delay(2000)
            emit(Gesture.OPEN_PALM)
        }
    }
}

class FakeSceneCheck : SceneCheck {
    override fun compare(live: Bitmap, saved: Bitmap): Float = 0.86f
}

