package com.showhow.core

/**
 * Reason to think the expert took something back and said it again.
 *
 * **Evidence, never a verdict.** Nothing here rewrites an instruction, drops a
 * step or overrules a word of the transcript. It notices that a step looks like
 * a self-repair, says which signals it noticed, and hands that to the coach as
 * one more thing to read. The coach decides -- it is the only part of this app
 * that has read the whole session and can tell "no, not that one" from "no
 * problem".
 *
 * That division is the whole design. Deciding from the wording alone would be a
 * keyword matcher rewriting a real person's instructions, and "no" is a word
 * people say constantly without retracting anything.
 *
 * @param retractionAtMs when the retraction was spoken, on the take's clock.
 * @param strength 0..1, how much evidence there is. Never a probability, just
 *   an ordering, so the coach can weigh a strong signal above a faint one.
 * @param signals what was actually noticed, in words. Goes into the prompt and
 *   the debug screen, because "strength 0.7" tells nobody anything.
 * @param supersededText what was said before the retraction, kept verbatim.
 * @param correctedText what was said after it, kept verbatim.
 */
data class CorrectionEvidence(
    val retractionAtMs: Long,
    val strength: Double,
    val signals: List<String>,
    val supersededText: String,
    val correctedText: String,
)

/**
 * Look for a self-correction inside one step.
 *
 * "Remove this screw... no, sorry, not this one. Remove the side screw." is one
 * step to the cutter -- it is one run of speech with no long pause in it -- and
 * two contradictory instructions to a learner. Left alone, the coach reads both
 * halves as equally meant and writes the guide accordingly.
 *
 * Four signals, and **a retraction word is never enough on its own**:
 *
 *   marker      "no", "sorry", "nahi", "galat", "wait". Necessary, never
 *               sufficient. People say "no problem" and "no, that's fine" all
 *               day without taking anything back.
 *   repeat      a content word said on both sides of the marker -- "screw"
 *               before and "screw" after. This is what separates a repair from
 *               a change of subject: a repair says the same thing again.
 *   hesitation  "uh", "matlab", "actually" near the marker. People stumble
 *               when they are correcting themselves.
 *   proximity   the marker landed within [Policy.correctionWindowMs] of the
 *               words it retracts. A repair is immediate; a minute later is a
 *               new thought.
 *
 * And one signal *against*, which is where [LinkWordConfirmer]'s word list
 * earns a second use: a linking word after the marker -- "phir", "then", "uske
 * baad" -- argues the expert moved on to the next thing rather than repaired
 * the last one. The same list that says "a new step starts here" says "this was
 * not a correction".
 *
 * Returns null when the evidence does not clear [Policy.correctionMinStrength],
 * and null is the ordinary answer: most steps contain no correction, and a
 * false positive costs a learner the instruction that was actually meant.
 *
 * Timestamps are used when there are any. A recogniser that returns sentences
 * rather than word clocks gives every word the same time; proximity then simply
 * abstains rather than firing on everything, in the same spirit as the
 * confirmer abstaining when it has nothing to vote with.
 */
fun correctionEvidence(
    words: List<SpokenWord>,
    policy: Policy = Policy.DEFAULT,
    lang: String = "hi",
): CorrectionEvidence? {
    if (words.size < MIN_WORDS) return null
    val text = words.map { normalize(it.text) }

    val markerAt = text.indices.firstOrNull { i -> startsPhrase(text, i, policy.correctionMarkers) }
        ?: return null
    val before = words.take(markerAt)
    val after = words.drop(markerAt + 1)
    // A retraction with no replacement is not a correction anyone can act on:
    // the expert trailed off, or the recogniser lost the rest.
    if (before.isEmpty() || after.size < MIN_REPLACEMENT_WORDS) return null

    val signals = mutableListOf<String>()
    var strength = policy.correctionMarkerWeight
    signals += "the expert says \"${words[markerAt].text}\""

    val repeated = repeatedContentWord(text.take(markerAt), text.drop(markerAt + 1), policy)
    if (repeated != null) {
        strength += policy.correctionRepeatWeight
        signals += "\"$repeated\" is said again afterwards, so the same action is being redone"
    }

    val hesitated = nearPhrase(text, markerAt, policy.hesitationMarkers)
    if (hesitated) {
        strength += policy.correctionHesitationWeight
        signals += "they hesitate around it"
    }

    // This is where "never a keyword matcher" stops being a comment and starts
    // being arithmetic. A retraction word plus a clock is still a retraction
    // word: every sentence has words 400ms apart, so proximity alone would
    // corroborate "no problem" as readily as "no, not that one". What actually
    // distinguishes a repair is that the expert says the thing again, or
    // stumbles doing it. Without one of those there is nothing here.
    if (repeated == null && !hesitated) return null

    // Only meaningful when the words carry different times. All-equal means a
    // recogniser without word clocks, and a signal that would fire on every
    // step is worse than no signal.
    val timed = words.any { it.startMs != words.first().startMs }
    if (timed && words[markerAt].startMs - before.last().startMs <= policy.correctionWindowMs) {
        strength += policy.correctionProximityWeight
        signals += "it comes straight after what it takes back"
    }

    val link = policy.linkWords(lang).firstOrNull { l ->
        val hay = after.joinToString(" ", " ", " ") { normalize(it.text) }
        hay.contains(" " + normalize(l) + " ")
    }
    if (link != null) {
        strength -= policy.correctionLinkWordPenalty
        signals += "but \"$link\" follows, which usually starts a new step rather than a repair"
    }

    // A marker on its own never clears the bar -- that is the arithmetic the
    // "never a keyword matcher" rule reduces to, and the test for it.
    if (strength < policy.correctionMinStrength) return null

    return CorrectionEvidence(
        retractionAtMs = words[markerAt].startMs,
        strength = strength.coerceIn(0.0, 1.0),
        signals = signals,
        supersededText = before.joinToString(" ") { it.text },
        correctedText = after.joinToString(" ") { it.text },
    )
}

/** A word said on both sides of the marker, or null. Not a stopword. */
private fun repeatedContentWord(before: List<String>, after: List<String>, p: Policy): String? {
    val skip = (p.correctionMarkers + p.hesitationMarkers).map { normalize(it) }.toSet()
    val head = before
        .filter { it.length >= MIN_CONTENT_CHARS && it !in skip }
        .toSet()
    return after.firstOrNull { it.length >= MIN_CONTENT_CHARS && it !in skip && it in head }
}

/** Does a phrase from [phrases] start at [i]? Multi-word entries are joined. */
private fun startsPhrase(words: List<String>, i: Int, phrases: List<String>): Boolean =
    phrases.any { phrase ->
        val parts = normalize(phrase).split(" ").filter { it.isNotBlank() }
        parts.isNotEmpty() && parts.indices.all { k -> words.getOrNull(i + k) == parts[k] }
    }

/** Any of [phrases] within a couple of words either side of [i]. */
private fun nearPhrase(words: List<String>, i: Int, phrases: List<String>): Boolean =
    (i - NEAR_WORDS..i + NEAR_WORDS)
        .filter { it in words.indices && it != i }
        .any { startsPhrase(words, it, phrases) }

private fun normalize(s: String): String =
    s.trim().lowercase().trim('.', ',', '!', '?', ';', ':', '"', '\'', '।')

/** Below this a step has nothing to correct. */
private const val MIN_WORDS = 4

/** A retraction with fewer words after it than this replaced nothing. */
private const val MIN_REPLACEMENT_WORDS = 2

/**
 * Shorter than this and a repeated word is grammar, not the subject.
 *
 * Three let "the" through, which made "undo the base screws, no, bring the
 * torch over" look like the same action twice. Four keeps every word a repair
 * actually turns on -- screw, board, connector, pull, lift, पेंच, निकालो -- and
 * drops the articles and postpositions that appear in every sentence.
 */
private const val MIN_CONTENT_CHARS = 4

/** How far either side of the marker a hesitation still counts. */
private const val NEAR_WORDS = 3
