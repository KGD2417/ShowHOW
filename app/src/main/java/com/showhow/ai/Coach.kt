package com.showhow.ai

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One step after the coach has rewritten it: an imperative title and the how. */
data class CoachStep(val title: String, val instruction: String)

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
     * The expert's steps, rewritten as instructions a stranger can follow.
     *
     * Returns as many as the model gave back, indexed the same as [rawSteps].
     * A short list is normal and a missing entry means "keep what the expert
     * said" -- the caller falls back per step rather than throwing the rewrite
     * away, because eight good steps and two raw ones is a better guide than
     * ten raw ones.
     */
    suspend fun rewrite(job: String, rawSteps: List<String>): List<CoachStep> {
        if (rawSteps.isEmpty()) return emptyList()
        val body = rawSteps.mapIndexed { i, t -> "${i + 1}. ${t.ifBlank { "(said nothing)" }}" }
            .joinToString("\n")
            .take(maxContextChars())
        val out = generate(
            """
            You are turning one expert's spoken walkthrough into a written guide.
            The job: $job
            Below is what the expert said during each step, transcribed. It is
            informal, may be in Hindi or Marathi, and may be garbled.

            $body

            Rewrite each step in English as an instruction someone doing this
            for the first time can follow. Keep the same numbering and the same
            number of steps. Do not invent a step that is not there.
            Answer with one line per step, in exactly this format:
            number|short title|one or two sentence instruction
            Nothing else.
            """.trimIndent(),
        )
        val steps = parseRewrite(out, rawSteps.size)
        Log.i(TAG, "rewrote ${steps.count { it != null }}/${rawSteps.size} steps")
        return steps.map { it ?: CoachStep("", "") }
    }

    /**
     * A learner's question, against the whole guide.
     *
     * @param stepIndex where they are right now, zero based, so "this screw"
     *   resolves to the right step.
     */
    suspend fun answer(
        job: String,
        steps: List<String>,
        stepIndex: Int,
        question: String,
    ): String {
        if (question.isBlank()) return ""
        val body = steps.mapIndexed { i, t ->
            val here = if (i == stepIndex) "  <- they are here" else ""
            "${i + 1}. $t$here"
        }.joinToString("\n").take(maxContextChars())
        return generate(
            """
            You are helping someone follow a repair guide, hands busy, on a
            phone. The job: $job
            The guide, as the expert recorded it:

            $body

            They are on step ${stepIndex + 1} and ask: "$question"

            Answer in English, in at most three sentences, plain and practical.
            Prefer what the guide says. If you have to use general repair
            knowledge the guide does not contain, start that sentence with
            "$BEYOND " so they know it did not come from the expert.
            """.trimIndent(),
        ).trim()
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

        val LADDER = listOf(LlmInference.Backend.GPU, LlmInference.Backend.CPU)
    }
}

/**
 * `number|title|instruction` lines into a list of [expected] slots.
 *
 * Written to survive a small model rather than to validate it: the
 * numbering is what places a line, so a model that skips step 3, emits
 * them out of order, wraps the lot in markdown or adds a sentence of
 * preamble still lands every line it got right. Anything unparseable is
 * dropped, and its slot stays null so the caller keeps the expert's own
 * words there.
 */
internal fun parseRewrite(raw: String, expected: Int): List<CoachStep?> {
    val out = arrayOfNulls<CoachStep>(expected)
    for (line in raw.lineSequence()) {
        val parts = line.split('|')
        if (parts.size < 3) continue
        // Leading "**3." or "- 3" from a model that would not stop
        // formatting: take the first run of digits on the line.
        val n = Regex("\\d+").find(parts[0])?.value?.toIntOrNull() ?: continue
        val i = n - 1
        if (i !in 0 until expected || out[i] != null) continue
        val title = parts[1].trim().trim('*', '#', '-', ' ')
        // Any further pipes belong to the instruction, not a fourth field.
        val instruction = parts.drop(2).joinToString("|").trim()
        if (title.isBlank() && instruction.isBlank()) continue
        out[i] = CoachStep(title, instruction)
    }
    return out.toList()
}
