package com.showhow.ai

import android.graphics.Bitmap
import java.io.File
import kotlinx.coroutines.flow.Flow

/** One recognised word with its place in the take. */
data class Word(val text: String, val startMs: Long, val endMs: Long, val confidence: Float = 1f)

enum class Gesture { NONE, OPEN_PALM, FIST, THUMB_UP, POINT, SWIPE_LEFT, SWIPE_RIGHT }

interface Asr {
    /**
     * @param lang "en", "hi" or "mr". Not a hint and not a preference: a Vosk
     *   model speaks one language and returns the nearest words it owns for
     *   anything else, so the wrong value here produces confident nonsense
     *   rather than an error or an empty list.
     */
    suspend fun transcribe(wav: File, lang: String): List<Word>

    /**
     * True when [transcribe] returns real per-word clocks.
     *
     * The system recogniser returns sentences and no timings. Rather than let
     * it invent them -- which would feed the step cutter confident nonsense --
     * callers check this and transcribe each step separately once the cuts are
     * already decided.
     */
    val hasWordTimings: Boolean get() = true
}

interface Captioner {
    suspend fun caption(jpg: File): String
}

interface GestureSource {
    fun start(): Flow<Gesture>
}

interface SceneCheck {
    /** 0.0 nothing alike .. 1.0 identical. Advisory. Never blocks anything. */
    fun compare(live: Bitmap, saved: Bitmap): Float
}

/**
 * The models the guide pipeline pulls from, in one place.
 *
 * Hand signs are deliberately not in here. They are a Flow the Player collects
 * for as long as it is on screen, not something the build pipeline asks a
 * question of, and carrying them here meant a field that was set on every
 * construction and read by nothing.
 */
data class AiStack(
    val asr: Asr,
    val captioner: Captioner,
    val sceneCheck: SceneCheck,
)
