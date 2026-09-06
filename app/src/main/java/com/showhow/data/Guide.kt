package com.showhow.data

import kotlinx.serialization.Serializable

/**
 * Where a piece of a guide came from.
 *
 * The coach is the only model in this app allowed to say something the expert
 * did not -- that is the whole point of it, since no transcript contains "PH0"
 * or "don't over-torque that". The cost of allowing it is that the app then
 * owes the reader an answer to "who said this?", and a guide that cannot answer
 * is a guide putting words in a real person's mouth.
 *
 * Most of the guide already answers it by construction: whatever is in
 * [Step.transcript] was heard from the expert, whatever is in [Step.caption]
 * was seen by the detector. [Step.instruction] is the one field that could
 * honestly be any of these, so it is the one that carries a label.
 */
@Serializable
enum class Provenance {
    /** Rewritten from what the expert actually said. */
    EXPERT,

    /** Nothing was said here; this came from what the camera saw. */
    VISUAL,

    /** Neither -- the model's own repair knowledge, from the job alone. */
    GENERAL,

    /**
     * Not established. The default, and what every guide written before this
     * field existed decodes to.
     *
     * Deliberately its own value rather than a null or a lean toward EXPERT:
     * "we never worked out where this came from" and "the expert said it" are
     * different claims, and only one of them is safe to show a learner as the
     * expert's word.
     */
    UNKNOWN,
}

/**
 * Where a coached [Step.instruction] came from, from what the coach was given.
 *
 * Derived, never guessed. At the moment the coach runs, the exact inputs for
 * this step are in hand: if it had the expert's words it rewrote them, if it
 * only had the detector's labels it worked from the picture, and if it had
 * neither then whatever it wrote came from the job description and its own
 * knowledge. Anything that produced no instruction at all stays [UNKNOWN],
 * because a blank is not evidence of anything.
 */
fun provenanceOf(transcript: String, caption: String, instruction: String): Provenance = when {
    instruction.isBlank() -> Provenance.UNKNOWN
    transcript.isNotBlank() -> Provenance.EXPERT
    caption.isNotBlank() -> Provenance.VISUAL
    else -> Provenance.GENERAL
}

@Serializable
data class Step(
    val index: Int,
    val title: String = "",
    val caption: String = "",
    /**
     * Every box the detector found in [photo], one entry per box.
     *
     * [caption] is the same information deduplicated for a human to read, and
     * this is the copy the step check does arithmetic on -- because two screw
     * heads and one screw head are different states of the same job, and a
     * distinct list calls them identical.
     *
     * Defaults to empty so a guide.json written before this field existed
     * still loads; the Player then falls back to splitting [caption], and
     * refreshCaptions fills this in the first time the guide is opened.
     */
    val objects: List<String> = emptyList(),
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
    /**
     * The step as the coach rewrote it, in English. Empty when there is no
     * coach model, and every screen then falls back to [transcript].
     *
     * A separate field rather than a rewrite of [transcript] on purpose. The
     * transcript is evidence -- what a real expert actually said, in their own
     * language -- and it is the thing the Player can still play as audio. A
     * model that overwrote it would leave the guide with no way to check what
     * it changed, which is the difference between a helpful rewrite and a
     * quiet fabrication.
     */
    val instruction: String = "",
    /**
     * Where [instruction] came from. See [Provenance].
     *
     * Defaults to [Provenance.UNKNOWN] so a guide.json written before this
     * field existed still loads, and loads as "nobody established this" rather
     * than as the expert's word.
     */
    val instructionSource: Provenance = Provenance.UNKNOWN,
    /**
     * The coach judged this step not part of the job -- an aside, a false
     * start, or an instruction the expert then took back.
     *
     * A flag and never a deletion. The step still owns its slice of the take
     * and its photograph, and both are evidence of what actually happened; the
     * step ranges also have to tile the take exactly, so removing one would
     * leave a hole. Defaults to false, so a guide written before this field
     * existed reads as "every step counts", which is what it meant.
     */
    val aside: Boolean = false,
    /**
     * One short line of care for this step, or null.
     *
     * Advice, never a gate. Nothing in this app blocks, disables or hides a
     * control because of it -- not Next, not Continue, not verification. A
     * warning a learner cannot dismiss is a warning that stops them finishing
     * the job, and the expert who recorded this finished it.
     *
     * Null is the ordinary value and the preferred one. A guide where every
     * step carries a caution is a guide nobody reads the cautions in, and an
     * invented warning is worse than none: it spends the learner's attention on
     * a risk that is not there, and teaches them the app makes things up.
     */
    val warning: String? = null,
    /**
     * Who is giving that advice. See [Provenance].
     *
     * The whole reason a warning may exist at all. "Careful, don't pull it by
     * the wires" is worth reading when the expert said it and worth reading
     * differently when a model added it, and a learner is entitled to know
     * which. [Provenance.UNKNOWN] whenever there is no warning.
     */
    val warningSource: Provenance = Provenance.UNKNOWN,
    /** e.g. "speech was unclear here, HANDS works better". Advice, never a gate. */
    val modeHint: String = "",
    /**
     * The step in other languages, keyed by language code. Filled on demand.
     *
     * A cache and not a second source of truth: [instruction] is the step, and
     * an entry here is Gemma's rendering of it for the synthetic voice. Written
     * back into guide.json the first time a learner listens in that language,
     * so the wait happens once per step and never again.
     *
     * Nothing here is ever shown as the expert's words -- see [Provenance].
     */
    val translated: Map<String, String> = emptyMap(),
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
    /**
     * When the expert last said "yes, this is right", or 0 for a draft.
     *
     * A timestamp rather than a boolean because it costs the same and answers
     * "when", which is the question anyone asks second. Zero is the default, so
     * every guide written before this existed reads as a draft -- which is
     * true, since nobody was ever asked to check them.
     *
     * What it is not: a quality score. The coach's doubts live in
     * [Step.warning] and its confidence in [Step.instructionSource], and
     * neither has any say here. Verification is an expert putting their name to
     * a guide, and a model is not allowed a vote on whether they may.
     */
    val verifiedAt: Long = 0,
) {
    /** True once an expert has checked this guide. See [verifiedAt]. */
    val verified: Boolean get() = verifiedAt > 0
}

/**
 * The same guide, back to being a draft.
 *
 * Every edit goes through here, because a verified badge on content nobody has
 * re-read is worse than no badge at all -- it is the app vouching for a change
 * the expert made and never looked at again. Editing a verified guide always
 * costs the tick, and earning it back costs one tap.
 *
 * Already a draft is left alone rather than copied, so this is free to call on
 * every keystroke.
 */
fun Guide.asDraft(): Guide = if (verifiedAt == 0L) this else copy(verifiedAt = 0)
