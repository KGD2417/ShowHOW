package com.showhow.core

/** One level reading off the mic. */
data class Sample(val tMs: Long, val dbfs: Double)

/** A step is a slice of the single take. Slices tile the take exactly. */
data class StepRange(val index: Int, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Second opinion on a candidate cut. Tomorrow the recognizer will confirm a
 * boundary when the expert actually said "phir" or "uske baad"; tonight it
 * passes candidates straight through so the pause detector stands alone.
 */
fun interface CutConfirmer {
    fun confirm(cutsMs: List<Long>): List<Long>
}

object PassThroughConfirmer : CutConfirmer {
    override fun confirm(cutsMs: List<Long>): List<Long> = cutsMs
}

/**
 * Confirms cuts against the linking words for a language.
 *
 * ponytail: no-op until the ASR lands. The words are already carried in
 * [Policy] so wiring this up tomorrow is a body change, not an API change.
 */
class LinkWordConfirmer(
    @Suppress("unused") private val linkWords: List<String>,
) : CutConfirmer {
    override fun confirm(cutsMs: List<Long>): List<Long> = cutsMs
}

/**
 * Turns a stream of (timestamp, dBFS) into step boundaries.
 *
 * A pause detector and a word list. No video model, no ML anywhere in here --
 * that is the whole reason this class is twenty lines of arithmetic and runs
 * on a five year old phone.
 */
class StepCutter(
    private val policy: Policy = Policy.DEFAULT,
    private val confirmer: CutConfirmer = PassThroughConfirmer,
) {

    fun cut(samples: List<Sample>, durationMs: Long): List<StepRange> {
        val cuts = candidates(samples)
        val confirmed = confirmer.confirm(cuts)
            .filter { it > 0L && it < durationMs }
            .distinct()
            .sorted()
        return build(mergeShort(confirmed, durationMs), durationMs)
    }

    /** Midpoint of every silence run long enough to be a step boundary. */
    private fun candidates(samples: List<Sample>): List<Long> {
        val gate = AdaptiveGate(policy)
        val cuts = mutableListOf<Long>()
        var silenceStart = -1L
        var sawSpeech = false

        for (s in samples) {
            if (gate.update(s.dbfs)) {
                if (silenceStart >= 0L) {
                    val len = s.tMs - silenceStart
                    // Cut in the middle of the pause: the tail of one step and
                    // the run-up of the next both keep some air around them.
                    if (len >= policy.pauseMs && sawSpeech) cuts += silenceStart + len / 2
                    silenceStart = -1L
                }
                sawSpeech = true
            } else if (silenceStart < 0L) {
                silenceStart = s.tMs
            }
        }
        return cuts
    }

    /** Drop the boundary after any too-short segment, so it joins the next one. */
    private fun mergeShort(cuts: List<Long>, durationMs: Long): List<Long> {
        val kept = mutableListOf<Long>()
        var start = 0L
        for (cut in cuts) {
            if (cut - start >= policy.minUtteranceMs) {
                kept += cut
                start = cut
            }
        }
        // A short tail has no "next" to join, so it goes back into the previous.
        if (kept.isNotEmpty() && durationMs - kept.last() < policy.minUtteranceMs) {
            kept.removeAt(kept.lastIndex)
        }
        // Hard cap. Everything past the cap lands in the final step rather than
        // being thrown away -- the ranges still have to tile the take.
        return if (kept.size > policy.maxSteps - 1) kept.take(policy.maxSteps - 1) else kept
    }

    private fun build(cuts: List<Long>, durationMs: Long): List<StepRange> {
        val bounds = listOf(0L) + cuts + listOf(durationMs)
        return bounds.zipWithNext().mapIndexed { i, (a, b) -> StepRange(i, a, b) }
    }
}
