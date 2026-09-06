package com.showhow.core

/**
 * How much the bench in front of the learner looks like the step they are on.
 *
 * Three values, and none of them is a verdict on the person. There is
 * deliberately no BLOCKED: nothing in this app disables Next, hides Continue or
 * refuses to advance because a camera disagreed with a photograph. The expert
 * who recorded this guide finished the job, and a learner who is holding the
 * board at a different angle is not wrong.
 */
enum class StepCheck {
    /** The scene matches the step's photograph and the same things are in it. */
    CORRECT,

    /** Some of the evidence is there. Worth saying, not worth insisting on. */
    LIKELY_CORRECT,

    /**
     * Not enough to say either way -- the camera is off, the phone is still
     * moving, or nothing recognisable is in frame.
     *
     * The ordinary answer, and the only one that ever justifies asking the
     * coach. Everything above this was settled with arithmetic.
     */
    UNCERTAIN,
}

/**
 * What the check is given. All of it already exists elsewhere in the app.
 *
 * @param sceneSimilarity [SceneHash.similarity] between the live frame and the
 *   step's saved photograph, 0..1. Zero when nothing is being watched.
 * @param frameToFrameChange bits of a 64-bit dHash that changed since the last
 *   frame. A phone still swinging toward the bench changes in dozens of them.
 * @param expected detector labels from the step's photograph.
 * @param seen detector labels from the live camera right now.
 */
data class CheckInputs(
    val sceneSimilarity: Float,
    val frameToFrameChange: Int,
    val expected: List<String>,
    val seen: List<String>,
)

/**
 * How much this one frame agrees with the step, 0..1, or null when the frame
 * has nothing to say at all.
 *
 * A number rather than a verdict, and that is the whole change. A frame at 74%
 * of the expected boxes and a frame at 76% are the same bench photographed a
 * moment apart; a threshold applied to each of them separately turns that into
 * "can't tell" followed by "that looks right", which is the app looking broken
 * twice in a row. The verdict is made later, in [StepConfidence], out of many
 * of these.
 *
 * Nothing here reads a label as a component. The loaded detector knows "laptop"
 * and "keyboard" and has no idea what a RAM module is -- so this compares its
 * labels to its own earlier labels and draws no conclusion about parts.
 */
fun frameEvidence(i: CheckInputs, p: Policy = Policy.DEFAULT): Float? {
    // What the detector found, against what it found in the photograph,
    // counted. Where it has an opinion, it is the opinion: everyone's desk,
    // lighting and camera angle differ, and the objects are the part that
    // travels between benches.
    labelOverlap(i.expected, i.seen)?.let { return it.toFloat() }

    // No detector, or a photograph nothing was recognised in. Then, and only
    // then, structure and colour against the step's photograph decides -- a
    // bench that merely *looks* like the photograph while the parts are wrong
    // must never be rescued by this, and above it never is.
    if (i.sceneSimilarity <= 0f) return null
    return ramp(i.sceneSimilarity, p.checkLikelySimilarity, p.checkCorrectSimilarity)
}

/**
 * How much this frame's evidence is worth, 1 for a still phone down to 0 for
 * one still swinging.
 *
 * The old code threw the frame away above [Policy.checkSettledMaxChange], and
 * that is half of why the check could never make its mind up: a hand-held
 * phone over a workbench is never perfectly still, so most frames counted for
 * nothing and the few that got through decided alone. Faded instead -- a
 * slightly shaky frame is weak evidence, not no evidence, and only a frame
 * changing twice over the threshold is worth nothing at all.
 */
fun settleWeight(frameToFrameChange: Int, p: Policy = Policy.DEFAULT): Float {
    val steady = p.checkSettledMaxChange.toFloat()
    return ramp(frameToFrameChange.toFloat(), 2f * steady, steady)
}

/**
 * The running answer to "does this look like the step", over frames rather
 * than off one of them.
 *
 * Two failures it exists to remove, both reported from the bench:
 *
 *  - **It could never get confident enough.** Every frame was judged alone and
 *    any camera movement discarded it outright, so the evidence never added up
 *    and the page never turned.
 *  - **Then it would suddenly be certain.** One frame landing over a hard
 *    threshold was a verdict, so a hand passing across the bench read as the
 *    work being finished.
 *
 * An exponential average fixes both: confidence has to be *earned* over about
 * half a second of agreeing frames before it can reach CORRECT, and it survives
 * the odd bad frame instead of collapsing. The bands then have separate enter
 * and exit levels ([Schmitt]), so a value sitting on the line cannot flicker --
 * the same trick, and for the same reason, as [ModeEngine].
 *
 * Stateful, and therefore [reset] on every step change.
 */
class StepConfidence(private val p: Policy = Policy.DEFAULT) {

    /**
     * 0..1, and the number the screen should show.
     *
     * The raw scene similarity was on screen before this existed, next to a
     * verdict computed from something else. "84%" over "can't tell yet" is two
     * measurements pretending to be one, and the learner believes the one they
     * can read.
     */
    var value: Float = 0f
        private set

    var check: StepCheck = StepCheck.UNCERTAIN
        private set

    private val correct = Schmitt(
        p.checkLabelOverlap,
        p.checkLabelOverlap - p.confidenceHysteresis,
    )
    private val likely = Schmitt(
        p.confidenceLikely,
        p.confidenceLikely - p.confidenceHysteresis,
    )

    /**
     * May the guide turn its own page right now?
     *
     * One value and not a condition spelled out at the call site, because the
     * screen and the page turn have to agree: a bar that says "that looks
     * right" over a guide that then sits there is the app contradicting
     * itself, and that is what a learner reads as broken.
     *
     * CORRECT is the whole bar now. It used to be CORRECT *plus* a second
     * similarity threshold on a different measurement, which is one of the two
     * ways the page turn could be unreachable on a bench that plainly matched.
     * [Policy.advanceOnMatchSimilarity] keeps its job as the on/off switch and
     * gives up its second one.
     */
    val mayAdvance: Boolean
        get() = p.advanceOnMatchSimilarity > 0f && check == StepCheck.CORRECT

    /** Feed one analysed frame. Returns the band to show right now. */
    fun update(i: CheckInputs): StepCheck {
        val weight = settleWeight(i.frameToFrameChange, p)
        val evidence = frameEvidence(i, p)
        value += if (evidence != null && weight > 0f) {
            // Weighted by how still the phone was, so a shaky frame nudges
            // where a steady one moves.
            p.confidenceRiseCoef * weight * (evidence - value)
        } else {
            // Camera off, phone mid-swing, nothing in shot. That is an absence
            // and not a disagreement, so confidence fades rather than being
            // scored zero -- and it fades slower than it builds, or a hand
            // reaching across the bench would undo ten good frames.
            p.confidenceFallCoef * (0f - value)
        }
        // An average only ever approaches its target, and a band set exactly at
        // that target is then unreachable: three of four expected boxes is
        // 0.75 on every frame forever, and 0.75 is the bar. Close enough is
        // arrival -- this is float convergence, not a tunable.
        if (evidence != null && weight > 0f && kotlin.math.abs(evidence - value) < ARRIVED) {
            value = evidence
        }
        value = value.coerceIn(0f, 1f)

        // Both, every frame, whichever wins: a Schmitt that is only asked
        // sometimes keeps a stale state and reports it later as news.
        val isCorrect = correct.update(value.toDouble())
        val isLikely = likely.update(value.toDouble())
        check = when {
            isCorrect -> StepCheck.CORRECT
            isLikely -> StepCheck.LIKELY_CORRECT
            else -> StepCheck.UNCERTAIN
        }
        return check
    }

    fun reset() {
        value = 0f
        check = StepCheck.UNCERTAIN
        correct.reset()
        likely.reset()
    }

    private companion object {
        const val ARRIVED = 0.005f
    }
}

/**
 * How much of what the photograph showed is in front of the camera now, 0..1,
 * or null when either side has nothing to say.
 *
 * **Counted, not just named.** Two philips heads in the photograph and one on
 * the bench is half the evidence, not all of it -- and "half" is the whole
 * difference between a panel with its screws out and a panel with one screw
 * out. A set comparison called those identical, which is how a step could be
 * satisfied by a laptop merely being on the desk.
 *
 * Null and not zero. An empty list means no detector, or nothing recognised,
 * and reporting that as "none of the expected things are here" would turn a
 * missing model into a warning about the learner's work.
 */
internal fun labelOverlap(expected: List<String>, seen: List<String>): Double? {
    val want = counts(expected)
    val have = counts(seen)
    if (want.isEmpty() || have.isEmpty()) return null
    val matched = want.entries.sumOf { (label, n) -> minOf(n, have[label] ?: 0) }
    return matched.toDouble() / want.values.sum()
}

/**
 * What is still missing from the bench, one entry per box short, or empty.
 *
 * For telling the learner what to do rather than handing them a percentage.
 * Same arithmetic as [labelOverlap]; a shortfall of two screws names the screw
 * twice, because "still looking for 2 philips screws" is the useful sentence.
 */
fun labelShortfall(expected: List<String>, seen: List<String>): List<String> {
    val want = counts(expected)
    val have = counts(seen)
    if (want.isEmpty()) return emptyList()
    return want.flatMap { (label, n) -> List((n - (have[label] ?: 0)).coerceAtLeast(0)) { label } }
}

private fun counts(labels: List<String>): Map<String, Int> =
    labels.map { it.trim().lowercase() }
        .filter { it.isNotEmpty() }
        .groupingBy { it }
        .eachCount()

/** 0 at [lo], 1 at [hi], a straight line between. [hi] below [lo] runs downhill. */
private fun ramp(v: Float, lo: Float, hi: Float): Float =
    if (lo == hi) (if (v >= hi) 1f else 0f) else ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
