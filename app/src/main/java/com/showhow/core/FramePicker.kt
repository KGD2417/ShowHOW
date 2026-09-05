package com.showhow.core

/**
 * One snapped frame, measured. Everything the picker is allowed to know.
 *
 * Plain numbers rather than a Bitmap, so the choosing is arithmetic that runs
 * on the JVM in microseconds and can be tested with frames that never existed.
 * The Android side measures; this side decides.
 *
 * @param snapIndex which snapN.jpg on disk, the thing finally returned.
 * @param tMs when the shutter fired, on the take's clock.
 * @param sharpness 0.0 flat .. 1.0 sharp, from [SceneHash.sharpness].
 * @param meanLuma 0 black .. 255 white, from [SceneHash.meanLuma].
 * @param dHash structure, from [SceneHash.dHash]. Used only to reject a frame
 *   that repeats the previous step's, never to judge one on its own.
 * @param detections how many boxes the object detector returned. Evidence that
 *   something recognisable is in shot, and nothing more -- what those things
 *   are is the detector's business and it only knows COCO classes.
 */
data class FrameStats(
    val snapIndex: Int,
    val tMs: Long,
    val sharpness: Double,
    val meanLuma: Double,
    val dHash: Long,
    val detections: Int,
)

/**
 * One photograph per step, or none.
 *
 * The step cutter finds boundaries from sound alone and must stay that way --
 * pure arithmetic over a level log, no model, provably correct off a phone.
 * This runs afterwards, over frames that already exist, and decides which of
 * them is worth showing. It changes no boundary and calls nothing that could.
 *
 * [mapSnapsToSteps] answers "which step does this photo belong to" by time, and
 * is still the answer when nothing has been measured. This answers a different
 * question -- "of the photos that belong to this step, which one is worth
 * looking at" -- and it is allowed to answer "none of them". A step with no
 * usable frame shows its instruction and the expert's voice, which is a
 * complete step; a blurred smear captioned "aiming for" is worse than nothing,
 * because the learner will try to match it.
 *
 * Frames are rejected outright for being unusable, then the survivors are
 * scored and the best one wins:
 *
 *   blurry     below [Policy.frameMinSharpness] -- a phone still in motion
 *   empty      outside the luma band -- a lens on a bench, a pocket, a worklight
 *   duplicate  too close to the frame the previous step already took
 *
 * The score prefers a sharp frame, one the detector found something in, and one
 * late in the step. Late matters most and is the reason this exists: a step's
 * photograph is meant to show what it looks like when it is *done*, and the
 * frame nearest the start of a step is a picture of the work not yet begun.
 *
 * Deterministic throughout. Same frames in, same choice out, every time -- ties
 * fall to the later frame, then the higher snap index, so there is no run where
 * two equally good frames swap places.
 */
fun pickFrames(
    frames: List<FrameStats>,
    ranges: List<StepRange>,
    policy: Policy = Policy.DEFAULT,
): List<Int?> {
    val out = MutableList<Int?>(ranges.size) { null }
    if (frames.isEmpty()) return out

    // The frame the previous step settled on, so a step cannot show the same
    // picture as the one before it. Null until some step has taken one: the
    // first step has nothing to be a duplicate of.
    var previousHash: Long? = null

    for (r in ranges) {
        val best = frames
            .asSequence()
            .filter { it.tMs >= r.startMs && it.tMs < r.endMs }
            .filter { it.sharpness >= policy.frameMinSharpness }
            .filter { it.meanLuma in policy.frameMinLuma..policy.frameMaxLuma }
            .filter { f ->
                val p = previousHash ?: return@filter true
                SceneHash.hamming(f.dHash, p) >= policy.frameMinHammingFromPrevious
            }
            // Ties fall to the later frame, and then to the higher snap index,
            // so the choice cannot depend on the order the list arrived in.
            .sortedWith(
                compareBy<FrameStats> { score(it, r, policy) }
                    .thenBy { it.tMs }
                    .thenBy { it.snapIndex },
            )
            .lastOrNull()

        if (best != null) {
            out[r.index] = best.snapIndex
            previousHash = best.dHash
        }
    }
    return out
}

/**
 * How worth showing a frame is, 0 .. 1-ish. Higher wins.
 *
 * Weights live in policy.json because the right balance is a property of the
 * job being filmed, not of this code: a bench repair holds still and rewards
 * sharpness, someone working under a car does not and rewards lateness.
 */
private fun score(f: FrameStats, r: StepRange, p: Policy): Double {
    val span = (r.endMs - r.startMs).coerceAtLeast(1)
    // 0 at the moment the step begins, 1 at the moment it ends.
    val lateness = ((f.tMs - r.startMs).toDouble() / span).coerceIn(0.0, 1.0)
    // Past a few boxes the detector is not telling us anything more about
    // whether the frame is worth looking at.
    val evidence = (f.detections.toDouble() / p.frameDetectionsForFullCredit).coerceIn(0.0, 1.0)
    return p.frameSharpnessWeight * f.sharpness +
        p.frameLatenessWeight * lateness +
        p.frameDetectionWeight * evidence
}
