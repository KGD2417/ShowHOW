package com.showhow.core

/**
 * One token of corrected transcript, and how many raw tokens it replaced.
 *
 * [took] exists so word clocks survive a merge: "screw driver" becoming
 * "screwdriver" is two timed words turning into one, and the step cutter reads
 * those clocks. Dropping the second word's end time would shorten the step.
 */
data class DomainFix(val text: String, val took: Int)

/**
 * Repair the handful of words this job depends on and a general recogniser
 * mangles.
 *
 * **This is not the recogniser learning anything.** Vosk decodes against a
 * fixed Kaldi graph; the vocabulary is `graph/words.txt` and nothing at runtime
 * adds to it. Checked against the shipped English model, every term here is
 * already in that 368k-word vocabulary -- so the model *can* say "screwdriver",
 * it just often prefers the likelier "screw driver", because a general language
 * model has read far more sentences about drivers than about screwdrivers.
 *
 * So the fix is at the other end: take the words it did return and put the
 * domain ones back together. Two rules, both deliberately timid, because a
 * corrector that guesses is worse than a recogniser that mishears -- a wrong
 * word the expert never said is exactly the confident nonsense this app
 * refuses everywhere else.
 *
 *   1. **Join.** Two adjacent tokens that spell a term when run together become
 *      that term: "screw driver", "counter clockwise", "anti clockwise".
 *   2. **Near miss.** A single token one edit away from a term of eight
 *      characters or more becomes that term: "screwdrive", "clockwize".
 *
 * The eight-character floor is the whole safety margin. Short words are where
 * an edit of one turns a real word into a different real word -- "flip" and
 * "clip", "hex" and "her" -- and no floor on length would have the corrector
 * quietly rewriting speech that was heard correctly. Long technical words have
 * no such neighbours.
 *
 * A token that already spells a term exactly is never touched.
 *
 * ponytail: Levenshtein over a short list, per token, per transcript. A trie
 * would be faster and is worth it at a few hundred terms, not at fifteen.
 */
fun correctDomainTokens(tokens: List<String>, terms: List<String>): List<DomainFix> {
    if (tokens.isEmpty() || terms.isEmpty()) return tokens.map { DomainFix(it, 1) }

    val vocabulary = terms.map { it.lowercase() }
    val exact = vocabulary.toSet()
    val out = ArrayList<DomainFix>(tokens.size)

    var i = 0
    while (i < tokens.size) {
        val token = tokens[i]
        val lower = token.lowercase()

        // Heard correctly. Nothing here improves on that.
        if (lower in exact) {
            out += DomainFix(lower, 1)
            i++
            continue
        }

        val joined = if (i + 1 < tokens.size) lower + tokens[i + 1].lowercase() else null
        val fromJoin = joined?.let { j -> vocabulary.firstOrNull { it == j } ?: near(j, vocabulary) }
        if (fromJoin != null) {
            out += DomainFix(fromJoin, 2)
            i += 2
            continue
        }

        val fromNearMiss = near(lower, vocabulary)
        out += if (fromNearMiss != null) DomainFix(fromNearMiss, 1) else DomainFix(token, 1)
        i++
    }
    return out
}

/** Whitespace in, corrected text out. For the live caption and the ask sheet. */
fun correctDomainText(text: String, terms: List<String>): String {
    if (text.isBlank()) return text
    return correctDomainTokens(text.trim().split(WHITESPACE), terms).joinToString(" ") { it.text }
}

/** The nearest term within one edit, or null. Only terms long enough to be safe. */
private fun near(token: String, vocabulary: List<String>): String? {
    if (token.length < MIN_FUZZY_LENGTH) return null
    return vocabulary.firstOrNull {
        it.length >= MIN_FUZZY_LENGTH &&
            kotlin.math.abs(it.length - token.length) <= 1 &&
            editDistance(token, it) == 1
    }
}

/**
 * Levenshtein, stopped early.
 *
 * Only ever asked "is this exactly one edit away", so a row that has already
 * gone past one edit everywhere cannot come back and the rest is wasted work.
 */
private fun editDistance(a: String, b: String, cap: Int = 1): Int {
    if (a == b) return 0
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (i in 1..a.length) {
        current[0] = i
        var best = current[0]
        for (j in 1..b.length) {
            val substitute = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
            current[j] = minOf(previous[j] + 1, current[j - 1] + 1, substitute)
            if (current[j] < best) best = current[j]
        }
        if (best > cap) return cap + 1
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length]
}

/** Below this a single edit turns one real word into another. See the KDoc. */
private const val MIN_FUZZY_LENGTH = 8

private val WHITESPACE = Regex("\\s+")
