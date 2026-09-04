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
    /** Advice, never a gate. Nothing in the app blocks on this being set. */
    val warning: String? = null,
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
