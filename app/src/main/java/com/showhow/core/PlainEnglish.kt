package com.showhow.core

/**
 * Turning what a person said into something a stranger can read.
 *
 * A recogniser returns a flat lowercase stream: no capitals, no full stops, and
 * every "um" the expert said on their way to the point. That is the correct
 * output for a recogniser and the wrong thing to put in front of a learner --
 * a step that reads "um so uh you take the screwdriver and um turn it" is
 * harder to follow than the audio it came from.
 *
 * Two facts about spoken English are enough to fix most of it, and both are
 * free because the recogniser already reports word clocks:
 *
 *   - **A hesitation is not a word.** "um", "uh", "erm" carry no meaning; they
 *     are the sound of someone thinking. So is an immediately repeated word --
 *     "the the screwdriver" is one restart, not two articles.
 *   - **A pause is punctuation.** The gap a speaker leaves between finishing
 *     one thought and starting the next is exactly where a full stop belongs.
 *     Nothing has to guess at sentence structure; the expert already performed
 *     it, and the clocks recorded it.
 *
 * What is deliberately *not* here is any attempt at grammar. No comma
 * insertion, no clause detection, no rewriting. Those need a language model and
 * would be the app putting words in the expert's mouth, which is [Coach]'s job
 * and is labelled as such wherever it happens.
 */

/**
 * Drop hesitations and restarts, keeping every clock intact.
 *
 * [fillers] comes from policy.json and holds *only* sounds that are never
 * content. It does not ship with "like", "so", "actually", "right" or
 * "basically", and that omission is the point: every one of those is a real
 * word in a real instruction -- "turn it like this", "so it sits flat", "right
 * up against the frame". Stripping them would delete meaning from a guide and
 * nobody would be able to tell from the result. They can be added in
 * policy.json by someone who has listened to the take and decided.
 */
fun stripFillers(words: List<SpokenWord>, fillers: Set<String>): List<SpokenWord> {
    if (words.isEmpty()) return words
    val out = ArrayList<SpokenWord>(words.size)
    for (w in words) {
        val bare = w.text.lowercase().trim(*EDGES)
        if (bare.isEmpty() || bare in fillers) continue
        // A word repeated back to back is a restart, not emphasis. Compared on
        // the bare form so "the, the" collapses too.
        if (out.isNotEmpty() && out.last().text.lowercase().trim(*EDGES) == bare) continue
        out += w
    }
    return out
}

/** The same, for a recogniser that returned a sentence and no clocks. */
fun stripFillers(text: String, fillers: Set<String>): String {
    if (text.isBlank()) return text
    val kept = stripFillers(text.trim().split(WHITESPACE).map { SpokenWord(it, 0) }, fillers)
    return kept.joinToString(" ") { it.text }
}

/**
 * Words into sentences, using the pauses the expert actually left.
 *
 * A gap of [sentenceGapMs] or more between the end of one word and the start of
 * the next ends the sentence. Everything else is left exactly as spoken.
 *
 * @param sentenceGapMs shorter than the step cutter's pause on purpose. That
 *   one is looking for the end of a whole step and can afford to be sure; this
 *   one is looking for the end of a sentence inside a step, which is a briefer
 *   thing. A value at or above the cutter's would put one sentence in each step
 *   and never a second.
 */
fun readable(words: List<SpokenWord>, sentenceGapMs: Long): String {
    if (words.isEmpty()) return ""
    val out = StringBuilder()
    var startOfSentence = true

    for ((i, w) in words.withIndex()) {
        // Before every word but the first, including the one after a full stop.
        if (out.isNotEmpty()) out.append(' ')
        out.append(if (startOfSentence) w.text.replaceFirstChar { it.uppercase() } else w.text)
        startOfSentence = false

        val next = words.getOrNull(i + 1) ?: continue
        // endMs defaults to startMs for a recogniser without real word
        // durations, and then this measures start to start -- still the right
        // shape, just a little more generous.
        if (next.startMs - maxOf(w.endMs, w.startMs) >= sentenceGapMs) {
            out.append('.')
            startOfSentence = true
        }
    }
    if (out.isNotEmpty() && !out.endsWith('.')) out.append('.')
    return out.toString()
}

/** Both passes, which is how every caller wants it. */
fun plainEnglish(words: List<SpokenWord>, fillers: Set<String>, sentenceGapMs: Long): String =
    readable(stripFillers(words, fillers), sentenceGapMs)

/** Punctuation a recogniser may leave on a word. Vosk does not, others do. */
private val EDGES = charArrayOf('.', ',', '!', '?', ';', ':', '"', '\'')

private val WHITESPACE = Regex("\\s+")
