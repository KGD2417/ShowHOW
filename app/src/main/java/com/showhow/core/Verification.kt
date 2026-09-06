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

    /** One of those two, not both. Worth saying, not worth insisting on. */
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
 * What the cascade is given. All of it already exists elsewhere in the app.
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
 * Decide how well the current view matches the step, without waking a model.
 *
 * A cascade, cheapest first, and the expensive rung is deliberately never
 * reached from here:
 *
 *   1. **Has the scene settled?** A frame taken mid-swing tells nobody
 *      anything, and comparing one is how an app ends up saying "this looks
 *      wrong" at a bench that is perfectly correct.
 *   2. **Does it look like the photograph?** [SceneHash] -- a dHash and a
 *      colour histogram, a couple of milliseconds, no model file.
 *   3. **Are the same things in it?** The detector's labels for this frame
 *      against its labels for the step's photograph. Ordinary object classes,
 *      compared with each other, which is the one comparison they are
 *      trustworthy for.
 *   4. **Otherwise UNCERTAIN**, and stop.
 *
 * Rung four is where a language model *could* be asked, and this function does
 * not ask it. A 2B model woken on every camera frame would drain the phone and
 * lock the UI for seconds at a time; the coach is invoked when a learner taps
 * Ask, by a person who wants an answer, and never by a frame arriving.
 *
 * Nothing here reads a label as a component. The loaded detector knows "laptop"
 * and "keyboard" and has no idea what a RAM module is -- so this compares its
 * labels to its own earlier labels and draws no conclusion about parts.
 */
fun checkStep(i: CheckInputs, p: Policy = Policy.DEFAULT): StepCheck {
    // 1. Still moving. Nothing to compare yet, and a wrong answer now is worse
    //    than none, because the learner is still lifting the phone.
    if (i.frameToFrameChange > p.checkSettledMaxChange) return StepCheck.UNCERTAIN

    // Nothing is being watched at all: camera off, or no saved photograph to
    // compare against. Not a disagreement -- an absence.
    if (i.sceneSimilarity <= 0f && i.seen.isEmpty()) return StepCheck.UNCERTAIN

    // 2. What the detector found, against what it found in the photograph,
    //    counted. This is the rung that decides, and it is first now.
    val overlap = labelOverlap(i.expected, i.seen)

    // 3. Structure and colour against the step's photograph. Corroboration
    //    only -- see the note below on why it cannot decide anything.
    val looksRight = i.sceneSimilarity >= p.checkCorrectSimilarity
    val looksPlausible = i.sceneSimilarity >= p.checkLikelySimilarity

    return when {
        // The objects agree. The work is in front of the camera, wherever the
        // camera happens to be standing.
        overlap != null && overlap >= p.checkLabelOverlap -> StepCheck.CORRECT
        // Some of it is there. Worth saying, not worth turning a page on.
        overlap != null && overlap > 0.0 -> StepCheck.LIKELY_CORRECT
        // The objects say nothing at all -- no detector, or a photograph
        // nothing was recognised in. The bench comparison is then the only
        // signal there is, and one signal is never CORRECT.
        overlap == null && looksRight -> StepCheck.LIKELY_CORRECT
        overlap == null && looksPlausible -> StepCheck.LIKELY_CORRECT
        // The objects are not there. A bench that still *looks* like the
        // photograph while the parts are wrong is the case this must not call
        // correct, so the scene comparison does not rescue it.
        else -> StepCheck.UNCERTAIN
    }
}

/**
 * May the guide turn its own page right now?
 *
 * One function and not a condition spelled out at the call site, because the
 * screen and the page turn have to agree: a bar that says "that looks right"
 * over a guide that then sits there is the app contradicting itself, and that
 * is what a learner reads as broken.
 *
 * Two things have to hold. The scene has to reach
 * [Policy.advanceOnMatchSimilarity] -- turning the page is a louder claim than
 * a line of advice, so it gets its own, higher bar. And [checkStep] has to
 * agree, with one exception spelled out below.
 *
 * The exception is the reason this is not simply `check == CORRECT`. CORRECT
 * means two independent signals agreed, and on a phone with no detector model
 * -- or on a step whose photograph nothing was recognised in -- the second
 * signal does not exist and never will. Refusing to ever turn the page there
 * would strand every learner on such a guide holding a screwdriver, so the
 * scene comparison decides alone. Labels that *are* present and *disagree* are
 * a different thing entirely, and still stop it.
 */
fun mayAdvance(i: CheckInputs, p: Policy = Policy.DEFAULT): Boolean {
    // 0 turns the whole behaviour off and waits for a person.
    if (p.advanceOnMatchSimilarity <= 0f) return false
    return when (checkStep(i, p)) {
        // The objects agreed. That is a claim about the work, and it holds on
        // a bench this app has never seen.
        StepCheck.CORRECT -> true
        // Nothing to compare objects with. Then, and only then, the bench
        // comparison decides alone, at its own higher bar -- otherwise a guide
        // whose photographs the detector had no word for could never move by
        // itself at all.
        StepCheck.LIKELY_CORRECT ->
            labelOverlap(i.expected, i.seen) == null &&
                i.sceneSimilarity >= p.advanceOnMatchSimilarity
        StepCheck.UNCERTAIN -> false
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

