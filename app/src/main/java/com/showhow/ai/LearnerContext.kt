package com.showhow.ai

import com.showhow.data.Guide
import com.showhow.data.Provenance

/**
 * How much the guide and the camera actually support an answer.
 *
 * The learner asks "is this the right screwdriver?" and there are four honest
 * replies, which differ in who is vouching for them rather than in how
 * confident they sound:
 *
 *   DIRECT_GUIDE_FACT  the guide says so. The expert did this job and wrote it.
 *   VISUAL_FACT        the detector reported it, in this frame or this step's
 *                      photograph. A real observation by a model that looked.
 *   GENERAL_KNOWLEDGE  neither. Laptops usually take a Phillips; nobody checked
 *                      this laptop.
 *   UNCERTAIN          nothing here identifies the thing being asked about.
 *
 * The one that costs a learner a broken laptop is general knowledge wearing the
 * guide's authority, which is why [groundedEvidence] exists and why nothing may
 * ever be promoted into DIRECT_GUIDE_FACT.
 *
 * UNCERTAIN is an answer, not a refusal. It says what is missing and lets the
 * learner decide; it never stops them, hides a control, or declines to reply.
 */
enum class AnswerEvidence { DIRECT_GUIDE_FACT, VISUAL_FACT, GENERAL_KNOWLEDGE, UNCERTAIN }

/**
 * Everything the coach is told before it answers one question.
 *
 * Assembled rather than looked up, so that what the model saw is a value a test
 * can build and assert on. The step the learner is standing in front of is the
 * primary context and the rest of the guide stays available, because "is this
 * the one you meant?" is usually answered two steps earlier.
 *
 * @param seenNow labels the detector is returning from the live camera, right
 *   now. Empty when the camera is off or no model is loaded. These are COCO
 *   classes and nothing else -- the loaded detector has never heard of a RAM
 *   module, and this list must never be described as though it had.
 * @param expectedObjects what the detector reported when this step's photograph
 *   was taken. Also COCO, also a real observation.
 * @param expectedTools tools named in the guide's own words. The expert said
 *   these; no model inferred them.
 */
data class LearnerContext(
    val job: String,
    val verified: Boolean,
    val stepNumber: Int,
    val totalSteps: Int,
    val instruction: String,
    val instructionSource: Provenance,
    val transcript: String,
    val warning: String,
    val warningSource: Provenance,
    val previous: String,
    val next: String,
    val expectedTools: List<String>,
    val expectedObjects: List<String>,
    val seenNow: List<String>,
    val question: String,
) {
    /** Anything the expert actually said or wrote about this step. */
    val hasGuideEvidence: Boolean
        get() = instruction.isNotBlank() || transcript.isNotBlank()

    /** Anything a detector actually reported, live or when the photo was taken. */
    val hasVisualEvidence: Boolean
        get() = seenNow.isNotEmpty() || expectedObjects.isNotEmpty()
}

/**
 * Build the context for one question.
 *
 * Pure, so the thing the model is shown can be asserted on without a phone or a
 * 2 GB model file. Everything it contains is already on the guide or already
 * came out of the detector; nothing is inferred and nothing is invented.
 */
fun learnerContext(
    guide: Guide,
    stepIndex: Int,
    question: String,
    seenNow: List<String> = emptyList(),
    toolWords: List<String> = emptyList(),
): LearnerContext {
    val i = stepIndex.coerceIn(0, (guide.steps.size - 1).coerceAtLeast(0))
    val step = guide.steps.getOrNull(i)
    val objects = step?.caption.orEmpty().split(",").map { it.trim() }.filter { it.isNotBlank() }
    // Tools come from what the expert said and wrote, never from the detector:
    // the loaded model knows COCO classes and has no label for a screwdriver.
    val guideText = listOf(step?.instruction, step?.transcript).joinToString(" ") { it.orEmpty() }
    return LearnerContext(
        job = guide.title,
        verified = guide.verified,
        stepNumber = i + 1,
        totalSteps = guide.steps.size,
        instruction = step?.instruction.orEmpty(),
        instructionSource = step?.instructionSource ?: Provenance.UNKNOWN,
        transcript = step?.transcript.orEmpty(),
        warning = step?.warning.orEmpty(),
        warningSource = step?.warningSource ?: Provenance.UNKNOWN,
        previous = summarise(guide, i - 1),
        next = summarise(guide, i + 1),
        expectedTools = toolWords.filter { guideText.contains(it, ignoreCase = true) },
        expectedObjects = objects,
        seenNow = seenNow.map { it.trim() }.filter { it.isNotBlank() }.distinct(),
        question = question,
    )
}

/** One line for a neighbouring step, or empty when there is no such step. */
private fun summarise(guide: Guide, index: Int): String {
    val s = guide.steps.getOrNull(index) ?: return ""
    val text = s.instruction.ifBlank { s.transcript.ifBlank { s.caption } }
    return if (text.isBlank()) "" else "${index + 1}. " + text.take(SUMMARY_CHARS)
}

/** The context as the model reads it. */
internal fun renderContext(c: LearnerContext): String = buildString {
    append("The job: ").append(c.job.ifBlank { "an unnamed repair job" })
    append(if (c.verified) " (checked by the expert)" else " (a draft the expert has not checked)")
    append("\n\nThey are on step ").append(c.stepNumber).append(" of ").append(c.totalSteps)
    append(".\n\nTHIS STEP -- the main thing they are asking about:")
    append("\n  the guide says: ").append(c.instruction.ifBlank { "(nothing written)" })
    if (c.instruction.isNotBlank()) append(" [").append(c.instructionSource).append("]")
    append("\n  the expert's own words: ").append(c.transcript.ifBlank { "(they worked in silence)" })
    if (c.warning.isNotBlank()) {
        append("\n  a caution on this step: ").append(c.warning)
        append(" [").append(c.warningSource).append("]")
    }
    append("\n  tools the guide names: ")
        .append(c.expectedTools.ifEmpty { listOf("(none named)") }.joinToString(", "))
    append("\n  the detector saw in this step's photo: ")
        .append(c.expectedObjects.ifEmpty { listOf("(nothing recognised)") }.joinToString(", "))
    append("\n  the detector sees through the camera right now: ")
        .append(c.seenNow.ifEmpty { listOf("(camera off, or nothing recognised)") }.joinToString(", "))

    if (c.previous.isNotBlank()) append("\n\nThe step before: ").append(c.previous)
    if (c.next.isNotBlank()) append("\nThe step after: ").append(c.next)
    append("\n\nThey ask: \"").append(c.question).append("\"")
}

/**
 * A claimed classification, capped by the evidence that was actually there.
 *
 * The same rule as everywhere else in this app: a claim may be weaker than the
 * evidence and never stronger. A model that answers from its own repair
 * knowledge and labels it [AnswerEvidence.DIRECT_GUIDE_FACT] is telling a
 * learner the expert vouched for something the expert never said, and that is
 * the sentence that puts a screwdriver through a motherboard.
 *
 * A model that says UNCERTAIN is always believed. Admitting the guide does not
 * cover something is the one claim nobody has an incentive to fake.
 */
internal fun groundedEvidence(claimed: AnswerEvidence, c: LearnerContext): AnswerEvidence {
    val ceiling = when {
        c.hasGuideEvidence -> AnswerEvidence.DIRECT_GUIDE_FACT
        c.hasVisualEvidence -> AnswerEvidence.VISUAL_FACT
        // The model always has its own knowledge, so this floor is always
        // available. It is never a reason to stay silent.
        else -> AnswerEvidence.GENERAL_KNOWLEDGE
    }
    return if (rank(claimed) < rank(ceiling)) claimed else ceiling
}

private fun rank(e: AnswerEvidence): Int = when (e) {
    AnswerEvidence.DIRECT_GUIDE_FACT -> 3
    AnswerEvidence.VISUAL_FACT -> 2
    AnswerEvidence.GENERAL_KNOWLEDGE -> 1
    AnswerEvidence.UNCERTAIN -> 0
}

/**
 * One answer, split into how well supported it is and what it actually says.
 *
 * The tag is read from wherever the model put it, because a 2B model asked for
 * a leading `[guide]` will also produce `Answer: [GUIDE]` and `**[guide]**`,
 * and dropping the answer over its label would be the wrong trade every time.
 *
 * `[general]` is kept as the inline marker it has always been, and `[uncertain]`
 * joins it: a sentence carrying either downgrades the whole answer, because an
 * answer that is three parts guide and one part guesswork is an answer with
 * guesswork in it.
 */
internal fun parseAnswer(raw: String, c: LearnerContext): Pair<AnswerEvidence, String> {
    val text = raw.trim()
    if (text.isBlank()) return AnswerEvidence.UNCERTAIN to ""

    val tag = TAGS.entries.firstOrNull { (token, _) ->
        text.contains(token, ignoreCase = true)
    }?.value

    val cleaned = TAGS.keys.fold(text) { acc, token -> acc.replace(token, "", ignoreCase = true) }
        .replace(LEAD_IN, "")
        .trim()
        .trim('*', '#', ':', '-', ' ')

    if (cleaned.isBlank()) return AnswerEvidence.UNCERTAIN to ""
    return groundedEvidence(tag ?: AnswerEvidence.UNCERTAIN, c) to cleaned
}

/**
 * Markers the model may use, weakest first.
 *
 * Order matters: an answer that says both `[guide]` and `[uncertain]` is an
 * uncertain answer, so the doubtful markers are matched first.
 */
private val TAGS = linkedMapOf(
    Coach.UNSURE to AnswerEvidence.UNCERTAIN,
    Coach.BEYOND to AnswerEvidence.GENERAL_KNOWLEDGE,
    "[seen]" to AnswerEvidence.VISUAL_FACT,
    "[guide]" to AnswerEvidence.DIRECT_GUIDE_FACT,
)

/** "Answer:" and friends, which a model adds however firmly it is asked not to. */
private val LEAD_IN = Regex("^\\s*(answer|a)\\s*:\\s*", RegexOption.IGNORE_CASE)

private const val SUMMARY_CHARS = 90
