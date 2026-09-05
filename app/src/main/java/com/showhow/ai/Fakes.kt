package com.showhow.ai

import java.io.File

/**
 * The last canned answer in the app. Captions are deterministic until Gemma
 * lands, and Gemma is optional.
 *
 * FakeAsr, FakeGestureSource and FakeSceneCheck used to live here. They are
 * gone on purpose: speech, hand signs and the scene check are real, and where
 * a model is missing the production path falls back to NoopAsr or NoGestures.
 * A recogniser that invents nine Hindi words, or a palm that waves itself
 * every two seconds, is worse than one that says nothing -- it looks like it
 * works right up until the jury asks a question.
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

