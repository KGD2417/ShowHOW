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

    // 2. Structure and colour against the step's photograph.
    val looksRight = i.sceneSimilarity >= p.checkCorrectSimilarity
    val looksPlausible = i.sceneSimilarity >= p.checkLikelySimilarity

    // 3. The detector's labels against its own labels for that photograph.
    //    Abstains when either side is empty, rather than counting an absence
    //    as a disagreement.
    val overlap = labelOverlap(i.expected, i.seen)
    val sameThings = overlap != null && overlap >= p.checkLabelOverlap

    return when {
        looksRight && sameThings -> StepCheck.CORRECT
        // Either signal alone is worth saying and not worth insisting on. A
        // learner working at a different angle fails the first and passes the
        // second; a learner at the right angle with the part already removed
        // does the reverse.
        looksRight || sameThings || looksPlausible -> StepCheck.LIKELY_CORRECT
        else -> StepCheck.UNCERTAIN
    }
}

/**
 * How much of what the photograph showed is in front of the camera now, 0..1,
 * or null when either side has nothing to say.
 *
 * Null and not zero. An empty list means no detector, or nothing recognised,
 * and reporting that as "none of the expected things are here" would turn a
 * missing model into a warning about the learner's work.
 */
internal fun labelOverlap(expected: List<String>, seen: List<String>): Double? {
    val want = expected.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    val have = seen.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    if (want.isEmpty() || have.isEmpty()) return null
    return want.count { it in have }.toDouble() / want.size
}
