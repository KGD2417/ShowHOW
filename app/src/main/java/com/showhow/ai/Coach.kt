package com.showhow.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.showhow.data.Provenance
import com.showhow.data.provenanceOf
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One candidate step as it comes out of the cutter, with everything known about
 * it. What the coach is given.
 *
 * [caption] is the object detector's labels, not a description of the photo:
 * the coach model is text-only (see [Coach]) and has never seen an image. It is
 * the difference between "the detector reported laptop, screwdriver in this
 * step's frame" and "the model looked at the photo", and only the first is true.
 */
data class TakeStep(
    val startMs: Long,
    val endMs: Long,
    /** What the expert actually said here. Empty on a silent step. */
    val transcript: String,
    /** Detector labels for this step's frame. Empty if no detector, no photo. */
    val caption: String,
    val hasPhoto: Boolean,
    /**
     * What suggests the expert took something back inside this step, in plain
     * words, or empty.
     *
     * Rendered from [com.showhow.core.CorrectionEvidence] rather than passed
     * structured, because it is going into a prompt and because it must arrive
     * as what it is: something noticed, for the coach to weigh against the rest
     * of the session. Nothing upstream has rewritten a word of [transcript] on
     * the strength of it.
     */
    val correction: String = "",
)

/** One step after the coach has rewritten it. */
data class CoachStep(
    val title: String,
    val instruction: String,
    /** Where [instruction] came from, after grounding. See [groundedSource]. */
    val source: Provenance = Provenance.UNKNOWN,
    /** One line of care for this step, or empty. Becomes Step.warning. */
    val note: String = "",
    /** Where [note] came from, after grounding. Becomes Step.warningSource. */
    val noteSource: Provenance = Provenance.UNKNOWN,
    /**
     * The coach judged this narration not part of the job -- an aside, a false
     * start, an instruction the expert then took back.
     *
     * A flag and never a deletion. The step still owns a slice of the take and
     * a photograph, and those are evidence; dropping it would also break the
     * invariant that step ranges tile the take exactly.
     */
    val aside: Boolean = false,
)

/**
 * The second person in the app.
 *
 * ShowHow has two users and they need opposite things. The expert records once
 * and narrates the way people actually talk -- half sentences, "is wale ko",
 * three things at a time, a screwdriver named by pointing at it. The learner
 * opens that guide cold and needs an ordered instruction they can follow with
 * their hands full, plus somewhere to put the question the expert never thought
 * to answer, like which screwdriver.
 *
 * So the coach runs twice, over the same guide, with the same model:
 *
 *   [rewrite] once, when the guide is built -- the expert's transcript becomes
 *             a clean numbered instruction. The original transcript is kept,
 *             never overwritten, because it is the evidence.
 *   [answer]  live, in the Player -- a question against the whole guide.
 *
 * **It answers in English on purpose.** The take is Hindi or Marathi and Gemma
 * at this size is markedly weaker in both; a fluent English answer is more use
 * to a learner than broken Marathi, and the expert's own audio is still there
 * for anyone who wants the original words.
 *
 * **It is allowed to know things the guide does not.** That is the whole point
 * of [answer] -- "which screwdriver" is a question no transcript contains. It
 * is also the one place in this app where a model may say something the expert
 * did not, so [BEYOND] makes it mark those sentences and the Player labels
 * them. A model that quietly blends the two would put words in the expert's
 * mouth, which is the failure this codebase has spent every other model
 * avoiding.
 *
 * Same shape as every other model here: gitignored file under `filesDir`, a
 * delegate ladder, one load, and an empty answer when the model is absent --
 * never a canned one.
 *
 * ponytail: one-shot [LlmInference.generateResponse] per call, no session and
 * no history, so a follow-up question does not know about the previous one.
 * LlmInferenceSession is the upgrade if the demo needs a conversation.
 */
class Coach(
    private val context: Context,
    private val modelFile: File,
    /** Chars of guide handed to the model. Long context is slow, not smarter. */
    private val maxContextChars: () -> Int,
) : AutoCloseable {

    @Volatile
    private var llm: LlmInference? = null

    @Volatile
    private var dead = false

    /** Which backend took the job, for the telemetry panel. */
    @Volatile
    var delegateName: String = "--"
        private set

    /** True when there is a model to load at all. Cheap, so the UI can ask. */
    val present: Boolean get() = modelFile.isFile

    /**
     * The whole take, read at once, rewritten into a guide a stranger can
     * follow.
     *
     * One call and not one per step, and that is the entire point of this
     * method. A step-at-a-time rewrite cannot know that step 4 undoes step 3,
     * that "no wait, the other one" in step 6 corrects step 5, or that step 2
     * was the expert's phone ringing. Reading the take end to end is what lets
     * it drop an abandoned instruction instead of politely rewriting it into
     * the guide as though the expert meant it.
     *
     * **It is given evidence, not pictures.** The model is text-only: the
     * runtime has `addImage` and `setEnableVisionModality`, but they need a
     * vision-capable model and this is Gemma at 2B. So each step arrives with
     * its clock, what the expert said, and what the *object detector* reported
     * in its frame -- which is a real observation about the photo, made by a
     * model that did look at it. Actual multimodal reasoning is a separate job
     * and needs a different .task file; nothing here pretends otherwise.
     *
     * Returns one slot per input step, null where the model gave nothing back,
     * so the caller keeps the expert's own words there rather than blanking a
     * step the model merely skipped.
     */
    suspend fun rewrite(job: String, steps: List<TakeStep>): List<CoachStep?> {
        if (steps.isEmpty()) return emptyList()
        val body = steps.mapIndexed { i, s -> describe(i + 1, s) }
            .joinToString("\n\n")
            .take(maxContextChars())
        val out = generate(
            """
            You are turning one expert's recorded repair session into a written
            guide for someone doing the job for the first time.

            The job: $job

            Below is the whole session in order. For each step you get its time,
            what the expert said (transcribed, informal, possibly Hindi or
            Marathi, possibly garbled), and what an object detector reported
            seeing in that step's photograph. You cannot see the photographs
            yourself; the detector's labels are all the visual evidence there is.

            $body

            Read all of it before writing anything. Then write the guide.

            Rules:
            - Keep the steps in the order they happened, and keep their numbers.
            - If the expert corrected themselves later, follow the correction
              and do not repeat what they took back.
            - Where a step is marked "possible self-correction", that is
              something noticed in the wording and the timing, not a
              conclusion. Judge it against the rest of the session: if they did
              change their mind, write only what they settled on; if they did
              not -- "no problem" is not a correction -- ignore the note and
              write the step as spoken.
            - If a step is an aside, a false start, or an instruction the expert
              abandoned, mark it SKIP instead of rewriting it.
            - Never state a fact the session does not support. If you are unsure
              what happened in a step, say so in the note rather than guessing.
            - Do not invent tools, part names, torque figures or measurements
              that were neither spoken nor detected.

            Answer with one line per step and nothing else, in exactly this
            format:

            number|SOURCE|short title|one or two sentence instruction|WHO: note

            SOURCE says where your instruction came from:
              EXPERT   you rewrote what the expert said in this step
              VISUAL   the expert said nothing useful; you used the detector
              GENERAL  neither -- this is your own repair knowledge
              SKIP     this step does not belong in the guide
            The note is one short line of care for this step, or empty. Start
            it with where it comes from, the same four words: "EXPERT: " if the
            expert actually said it, "VISUAL: " if it follows from what the
            detector saw, "GENERAL: " if it is your own repair knowledge.

            Leave the note empty unless there is a real reason for it. Most
            steps have none, an empty note is the right answer, and a caution
            nobody needed costs the learner their attention on the one that
            matters. Never invent a risk to fill the column.

            Keep it to the kind of thing anyone doing this job would want said
            out loud -- "Keep track of the removed screws." or "Be careful not
            to pull the connector by the wires." Do not give torque figures,
            voltages, part numbers or specific safety procedures unless the
            expert said them: you do not know this machine, and a confident
            number nobody checked is worse than silence.
            """.trimIndent(),
        )
        val written = parseRewrite(out, steps.size)
        Log.i(TAG, "rewrote ${written.count { it != null }}/${steps.size} steps")
        // The model's own SOURCE is a claim, and a claim is checked against what
        // it was actually handed before it reaches a guide.
        return written.mapIndexed { i, c ->
            c?.copy(
                source = groundedSource(c.source, steps[i], c.instruction),
                // Same rule for the warning, and for the same reason. A model
                // labelling its own caution EXPERT over a step where the expert
                // said nothing would put a safety claim in a real person's
                // mouth, which is the worst version of the failure this whole
                // mechanism exists to prevent.
                noteSource = groundedSource(c.noteSource, steps[i], c.note),
            )
        }
    }

    /** One step as the prompt sees it. */
    private fun describe(n: Int, s: TakeStep): String = buildString {
        append("Step ").append(n)
        append(" [").append(clock(s.startMs)).append(" - ").append(clock(s.endMs)).append("]")
        append("\n  said: ").append(s.transcript.ifBlank { "(nothing -- worked in silence)" })
        append("\n  detector saw: ").append(s.caption.ifBlank { "(nothing recognised)" })
        if (!s.hasPhoto) append("\n  (no photograph for this step)")
        if (s.correction.isNotBlank()) append("\n  possible self-correction: ").append(s.correction)
    }

    /**
     * A learner's question, answered against the step they are standing in
     * front of and the guide around it.
     *
     * The current step is the primary context and the whole guide stays
     * available, because "is this the one you meant?" is usually settled two
     * steps earlier. What the model is shown is a [LearnerContext], assembled
     * beforehand, so the exact thing it read is a value a test can build.
     *
     * The answer comes back in English whatever the question was asked in --
     * the learner speaks Hindi or Marathi into the same Vosk stream as always,
     * and Gemma at 2B writes far better English than either. It also comes back
     * classified: see [AnswerEvidence]. That classification is a claim, so
     * [groundedEvidence] caps it against what was actually in the context, and
     * nothing may be promoted into DIRECT_GUIDE_FACT.
     *
     * @return the evidence class and the answer. An empty answer means no
     *   model, or a model that failed -- never a refusal. Nothing here declines
     *   to reply, and nothing it returns stops the learner doing anything.
     */
    suspend fun answer(context: LearnerContext): Pair<AnswerEvidence, String> {
        if (context.question.isBlank()) return AnswerEvidence.UNCERTAIN to ""
        val out = generate(
            """
            You are helping someone follow a repair guide on a phone, hands
            busy, in the middle of the job. Everything you know is below.

            ${renderContext(context).take(maxContextChars())}

            Answer in English, in at most three sentences, plain and practical.
            Answer even when the guide does not cover it -- they are mid-repair
            and being told nothing is no use to them.

            Begin with exactly one tag saying what your answer rests on:

              [guide]      the guide above says this. Only when it really does.
              [seen]       the detector actually reported it, in the photo or
                           through the camera right now.
              $BEYOND    your own repair knowledge. The guide does not say it.
              $UNSURE  nothing above identifies the thing they are asking
                           about. Say what is missing and what would settle it.

            Never write [guide] for something you know rather than something the
            guide says. The expert did this job and put their name to it; you
            did not, and a learner cannot tell the difference once you have
            blurred it.

            The detector knows only ordinary object classes -- laptop, keyboard,
            person, mouse. It has no label for a RAM module, an SSD, a heatsink
            or a screwdriver, so never claim it saw one.
            """.trimIndent(),
        )
        return parseAnswer(out, context)
    }

    private suspend fun generate(prompt: String): String = withContext(Dispatchers.IO) {
        val engine = engine() ?: return@withContext ""
        val started = System.currentTimeMillis()
        runCatching { engine.generateResponse(prompt) }
            .onSuccess { Log.i(TAG, "answered in ${System.currentTimeMillis() - started} ms") }
            .getOrElse {
                // Not fatal and not retried: a prompt that overflows the
                // context throws here, and the next, shorter one will not.
                Log.w(TAG, "generate failed", it)
                ""
            }
    }

    /** LOAD -> VERIFY -> INIT, once, down the backend ladder. Each fall logged. */
    private fun engine(): LlmInference? {
        llm?.let { return it }
        if (dead) return null
        synchronized(this) {
            llm?.let { return it }
            if (dead) return null
            if (!modelFile.isFile) {
                Log.w(TAG, "no coach model at $modelFile, the coach stays quiet")
                dead = true
                return null
            }
            for (backend in LADDER) {
                val started = System.currentTimeMillis()
                val built = runCatching { build(backend) }.getOrElse {
                    Log.w(TAG, "coach would not load on $backend", it)
                    null
                }
                if (built != null) {
                    Log.i(TAG, "coach on $backend in ${System.currentTimeMillis() - started} ms")
                    delegateName = backend.name
                    llm = built
                    return built
                }
            }
            Log.w(TAG, "no backend would take the coach, it stays quiet")
            dead = true
            return null
        }
    }

    private fun build(backend: LlmInference.Backend): LlmInference =
        LlmInference.createFromOptions(
            context,
            LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelFile.absolutePath)
                // Input plus output. A twelve step guide is around 1500 tokens
                // of context, so this leaves room for the answer and not much
                // more -- which is the point, a long answer is a worse answer
                // when the reader has a screwdriver in one hand.
                .setMaxTokens(2048)
                .setPreferredBackend(backend)
                .build(),
        )

    /** RELEASE. A 2B model is most of a gigabyte of native memory. */
    override fun close() {
        synchronized(this) {
            runCatching { llm?.close() }
            llm = null
            dead = true
        }
    }

    companion object {
        private const val TAG = "Coach"

        /** Path under filesDir. Gitignored, so expect it to be missing. */
        const val COACH_MODEL = "models/coach.task"

        /**
         * Marks a sentence the expert never said.
         *
         * A literal string rather than a flag because it has to survive the
         * model repeating it back inside a paragraph, which no structured
         * output format would.
         */
        const val BEYOND = "[general]"

        /**
         * Marks an answer nothing in the guide or the camera supports.
         *
         * New beside [BEYOND] rather than folded into it, because "I know this
         * but the guide does not" and "nobody here knows" are different things
         * to tell someone holding a screwdriver over a live board.
         */
        const val UNSURE = "[uncertain]"

        val LADDER = listOf(LlmInference.Backend.GPU, LlmInference.Backend.CPU)
    }
}

/**
 * `number|SOURCE|title|instruction|note` lines into a list of [expected] slots.
 *
 * A line format and not JSON, deliberately. The runtime has no constrained
 * decoding -- there is no schema, grammar or JSON class anywhere in
 * tasks-genai 0.10.35 -- so whatever shape is asked for is a request, not a
 * guarantee. JSON from a 2B model fails as one piece: a single unbalanced brace
 * three quarters of the way down costs every step, including the eight it got
 * right. Lines fail one at a time.
 *
 * Written to survive a small model rather than to validate it: the numbering is
 * what places a line, so a model that skips step 3, emits them out of order,
 * wraps the lot in markdown or adds a sentence of preamble still lands every
 * line it got right. Anything unparseable is dropped and its slot stays null,
 * so the caller keeps the expert's own words there.
 */
internal fun parseRewrite(raw: String, expected: Int): List<CoachStep?> {
    val out = arrayOfNulls<CoachStep>(expected)
    for (line in raw.lineSequence()) {
        val parts = line.split('|')
        // number, source, title, instruction. The note is optional because a
        // model with no doubt to report tends to just stop.
        if (parts.size < 4) continue
        // Leading "**3." or "- 3" from a model that would not stop formatting:
        // take the first run of digits on the line.
        val n = Regex("\\d+").find(parts[0])?.value?.toIntOrNull() ?: continue
        val i = n - 1
        if (i !in 0 until expected || out[i] != null) continue

        val claimed = sourceToken(parts[1])
        val title = parts[2].trim().trim('*', '#', '-', ' ')
        val instruction = parts[3].trim()
        // Any further pipes belong to the note, not a sixth field.
        val (noteSource, note) = splitNote(parts.drop(4).joinToString("|"))
        val aside = parts[1].trim().uppercase().contains(SKIP)

        if (!aside && title.isBlank() && instruction.isBlank()) continue
        out[i] = CoachStep(
            title = title,
            instruction = instruction,
            source = claimed ?: Provenance.UNKNOWN,
            note = note,
            noteSource = noteSource,
            aside = aside,
        )
    }
    return out.toList()
}

/** The SOURCE column, or null when the model wrote something else there. */
internal fun sourceToken(raw: String): Provenance? {
    val t = raw.trim().trim('*', '#', '-', ' ', '[', ']').uppercase()
    return Provenance.entries.firstOrNull { it.name == t }
}

/**
 * A note column into who is speaking and what they said.
 *
 * The model is asked for "GENERAL: keep track of the screws" and will also
 * write "[general] keep track", "GENERAL - keep track", and plain "keep track"
 * with no label at all. All four keep the sentence, because a caution the
 * expert may have given is worth more than a tidy column, and an unlabelled one
 * simply arrives as UNKNOWN for grounding to settle.
 *
 * A note that says nothing -- blank, "none", a dash -- is no note. Null is the
 * preferred answer and the prompt says so; this is where that is honoured
 * rather than turned into an empty string with a source attached to it.
 */
internal fun splitNote(raw: String): Pair<Provenance, String> {
    // Trailing pipes come off first: the model is asked for five columns and
    // often writes six, leaving the note as "mind the clips|" once the empty
    // last field is re-joined.
    val trimmed = raw.trim().trim('|', ' ').trim('*', '#', ' ', '[')
    // ']' is a separator too, because the model reaches for the [general]
    // bracket form it was taught for answers and writes "[GENERAL] keep track".
    val at = trimmed.indexOfFirst { it == ':' || it == '-' || it == ']' }
    val claimed = if (at > 0) sourceToken(trimmed.take(at)) else null
    val text = (if (claimed != null) trimmed.drop(at + 1) else trimmed)
        .let(::stripExtraLabels)
        .trim()
        .trim('-', ' ', '|')

    val empty = text.isBlank() ||
        text.equals("none", true) ||
        text.equals("n/a", true) ||
        text.equals("no warning", true) ||
        echoesAbsence(text)
    return if (empty) {
        Provenance.UNKNOWN to ""
    } else {
        (claimed ?: Provenance.UNKNOWN) to text
    }
}

/**
 * Remove the extra `EXPERT:` / `VISUAL:` / `GENERAL:` labels a model staples on
 * mid-sentence.
 *
 * Observed from Gemma 3n on the device, in one note column:
 *
 *   "Nothing said. Visual: Detector saw nothing recognized. EXPERT: Worked in
 *    silence. EXPERT: Nothing recognized."
 *
 * One label belongs at the front and says who is speaking. Every further one is
 * the model narrating the format back, and it reaches the learner as an
 * unreadable run-on where a single line of care should be.
 */
private fun stripExtraLabels(raw: String): String {
    var out = raw
    for (p in Provenance.entries) {
        out = out.replace(Regex("\\b${p.name}\\s*[:\\-]\\s*", RegexOption.IGNORE_CASE), "")
    }
    return out.replace('|', ' ').replace(Regex("\\s{2,}"), " ")
}

/**
 * Is this note just the prompt's own "nothing here" wording, handed back?
 *
 * A silent step is described to the model as "(nothing -- worked in silence)"
 * and "(nothing recognised)", and a model with a column to fill will restate
 * that as though it were a caution. It is not: it is the absence of one.
 *
 * This is the arithmetic behind "null is preferable to hallucinating a
 * warning". A learner who reads "Detector saw nothing recognized" on a step has
 * been told nothing about their work and charged their attention for it.
 */
private fun echoesAbsence(text: String): Boolean {
    val t = text.lowercase()
    val absent = listOf(
        "nothing said", "nothing recognis", "nothing recogniz", "worked in silence",
        "no photograph", "no photo for", "detector saw nothing", "camera off",
        "nothing was said", "no instruction given", "not applicable",
    )
    // Only when the whole note is made of those. A real caution that happens to
    // mention a missing photo keeps its sentence.
    val stripped = absent.fold(t) { acc, phrase -> acc.replace(Regex("[^.]*$phrase[^.]*\\.?"), "") }
    return stripped.filter { it.isLetter() }.length < MIN_REAL_NOTE_LETTERS
}

/** Under this many letters left, the note said nothing of its own. */
private const val MIN_REAL_NOTE_LETTERS = 12

/** SKIP is not a Provenance -- it is a verdict about the step, not its source. */
private const val SKIP = "SKIP"

/**
 * The model's claimed source, capped by the evidence it was actually handed.
 *
 * The coach is asked where each instruction came from, and its answer is a
 * claim like any other. A model that writes EXPERT over a step where the expert
 * said nothing is not lying on purpose -- it is a 2B model filling a column --
 * but the result would be a guide attributing invented words to a real person,
 * which is the one failure this whole provenance mechanism exists to prevent.
 *
 * So a claim may always be *weaker* than the evidence and never stronger. The
 * model saying "I used the photo, not the words" over a step that has both is
 * honoured; the model saying "the expert said this" over silence is not. A
 * model that admits UNKNOWN is always believed.
 */
internal fun groundedSource(
    claimed: Provenance,
    step: TakeStep,
    instruction: String,
): Provenance {
    if (instruction.isBlank()) return Provenance.UNKNOWN
    if (claimed == Provenance.UNKNOWN) return Provenance.UNKNOWN

    // Which channels actually carried anything for this step. GENERAL is always
    // available: the model always has its own knowledge, and saying so is the
    // humblest claim it can make short of admitting it does not know.
    val available = buildList {
        if (step.transcript.isNotBlank()) add(Provenance.EXPERT)
        if (step.caption.isNotBlank()) add(Provenance.VISUAL)
        add(Provenance.GENERAL)
    }
    // The strongest channel that both exists and is no stronger than the claim.
    //
    // Two rules in one line, and both are load-bearing. Never stronger than the
    // claim, so a model that says "I worked from the photo" is not promoted to
    // the expert's word. And never a channel that was empty, which is the part
    // that was missing: a VISUAL claim on a step where no detector ran is not
    // merely optimistic, it describes an observation nothing made.
    return available.filter { rank(it) <= rank(claimed) }.maxByOrNull { rank(it) }
        ?: Provenance.GENERAL
}

/** How strong a claim each value makes. UNKNOWN claims nothing. */
private fun rank(p: Provenance): Int = when (p) {
    Provenance.EXPERT -> 3
    Provenance.VISUAL -> 2
    Provenance.GENERAL -> 1
    Provenance.UNKNOWN -> 0
}

/** mm:ss on the take's clock, for the prompt. */
internal fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
