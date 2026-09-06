package com.showhow.core

/** One level reading off the mic. */
data class Sample(val tMs: Long, val dbfs: Double)

/** A step is a slice of the single take. Slices tile the take exactly. */
data class StepRange(val index: Int, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * One recognised word placed on the take's timeline.
 *
 * Deliberately not [com.showhow.ai.Word]: that file imports android.graphics,
 * and core owes nothing to the AI layer. The ViewModel maps one to the other
 * in a single line.
 */
data class SpokenWord(
    val text: String,
    val startMs: Long,
    /**
     * When the word finished, for measuring the silence after it.
     *
     * Defaults to [startMs], which is the honest value for a recogniser that
     * reports no durations -- the gap then reads start-to-start and comes out a
     * little generous rather than invented. [readable] is the only caller that
     * needs it; the cutter and the confirmer place words, they do not measure
     * the space between them.
     */
    val endMs: Long = startMs,
)

/**
 * Second opinion on a candidate cut. The pause detector proposes; this decides
 * whether the expert actually said "phir" or "uske baad" anywhere near it.
 */
fun interface CutConfirmer {
    fun confirm(cutsMs: List<Long>): List<Long>
}

object PassThroughConfirmer : CutConfirmer {
    override fun confirm(cutsMs: List<Long>): List<Long> = cutsMs
}

/**
 * Keeps a candidate cut only if at least [minLinkWords] linking words were
 * spoken within [windowMs] either side of it.
 *
 * A pause alone is ambiguous -- an expert reaching for a spanner sounds exactly
 * like an expert finishing a step. A linking word is the cheap disambiguator,
 * and it costs no model beyond the recogniser we already run.
 *
 * The veto is off whenever there is nothing to vote with: no recogniser, no
 * word list, or the knob turned to 0. Silently dropping every cut would hand
 * back one enormous step, which is the exact failure the cutter exists to
 * prevent, so "no opinion" must mean "pass through", never "no".
 */
class LinkWordConfirmer(
    private val linkWords: List<String>,
    private val words: List<SpokenWord>,
    private val windowMs: Long = Policy.DEFAULT.confirmWindowMs,
    private val minLinkWords: Int = Policy.DEFAULT.confirmMinLinkWords,
) : CutConfirmer {

    override fun confirm(cutsMs: List<Long>): List<Long> {
        if (minLinkWords <= 0 || words.isEmpty() || linkWords.isEmpty()) return cutsMs
        return cutsMs.filter { hits(it) >= minLinkWords }
    }

    /**
     * Words near the cut are re-joined into one string before matching, because
     * "uske baad" ships in policy.json as a single entry but arrives from the
     * recogniser as two separate tokens.
     */
    private fun hits(cutMs: Long): Int {
        val near = words.asSequence()
            .filter { kotlin.math.abs(it.startMs - cutMs) <= windowMs }
            .joinToString(" ", prefix = " ", postfix = " ") { normalize(it.text) }
        return linkWords.sumOf { occurrences(near, " " + normalize(it) + " ") }
    }

    private fun normalize(s: String): String = s.trim().lowercase()

    private fun occurrences(haystack: String, needle: String): Int {
        if (needle.isBlank()) return 0
        var n = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            n++
            // Overlap the trailing space: " phir " then " ab " share a separator.
            i = haystack.indexOf(needle, i + needle.length - 1)
        }
        return n
    }
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

/**
 * Which photo belongs to which step.
 *
 * Snaps are taken at *live* boundaries while the take is running; ranges come
 * out of the *final* cut, after short utterances merge and the [Policy.maxSteps]
 * cap bites. There are therefore almost always more snaps than steps, so
 * pairing them off by position walks every photo onto the wrong step -- that is
 * defect #1. Pair them by time instead.
 *
 * Each photo goes to the step it fits best and each step keeps only its closest
 * photo, so no two steps share one and the extras simply lose.
 *
 * @param snapTimesMs when each photo was taken, ascending, on the take's clock.
 * @return one snap index per range, or -1 for a step that ends up with no photo.
 */
fun mapSnapsToSteps(snapTimesMs: List<Long>, ranges: List<StepRange>): List<Int> {
    val out = MutableList(ranges.size) { -1 }
    val bestDelta = LongArray(ranges.size) { Long.MAX_VALUE }
    // Offer each photo to the step it fits best, rather than letting each step
    // grab the nearest one left. A greedy step would swallow a photo that a
    // later step needed and leave the tail of the guide blank.
    for ((snap, tMs) in snapTimesMs.withIndex()) {
        var bestIndex = -1
        var delta = Long.MAX_VALUE
        for (r in ranges) {
            val d = Math.abs(tMs - r.startMs)
            if (d < delta) {
                delta = d
                bestIndex = r.index
            }
        }
        if (bestIndex >= 0 && delta < bestDelta[bestIndex]) {
            bestDelta[bestIndex] = delta
            out[bestIndex] = snap
        }
    }
    return out
}
