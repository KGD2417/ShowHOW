package com.showhow.data

import kotlinx.serialization.Serializable

@Serializable
data class Step(
    val index: Int,
    val title: String = "",
    val caption: String = "",
    val startMs: Long = 0,
    val endMs: Long = 0,
    /** File name inside the guide folder, e.g. "s1.jpg". */
    val photo: String = "",
    /** What the expert actually said during this step. Empty when ASR is off. */
    val transcript: String = "",
    /**
     * A re-recorded clip for this step, e.g. "step2.wav". Empty means the step
     * is still a slice of the original take, which is the normal case.
     *
     * An override file rather than a rewrite of take.wav: splicing PCM into the
     * middle of a recording shifts every later step's timestamps, and a person
     * fixing one badly-worded step should not be able to break the four steps
     * after it.
     */
    val audio: String = "",
    /** Advice, never a gate. Nothing in the app blocks on this being set. */
    val warning: String? = null,
    /** e.g. "speech was unclear here, HANDS works better". Advice, never a gate. */
    val modeHint: String = "",
)

@Serializable
data class Guide(
    val id: String,
    val title: String = "",
    /** "hi" or "mr". */
    val lang: String = "hi",
    val createdAt: Long = 0,
    /** The single narration take every step slices out of. */
    val take: String = "take.wav",
    val steps: List<Step> = emptyList(),
)
