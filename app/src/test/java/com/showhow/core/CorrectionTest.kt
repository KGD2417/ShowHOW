package com.showhow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Did the expert take something back, and did they say what instead?
 *
 * The failure that matters is not missing a correction -- the coach reads the
 * whole session and may catch it anyway. It is claiming one that never
 * happened, because the guide then drops the instruction the expert actually
 * meant, and the learner never learns it was there.
 *
 * So most of these assert null. "No" is a word people say constantly without
 * retracting anything, and every test below that ends in null is a sentence
 * a keyword matcher would have got wrong.
 */
class CorrectionTest {

    private val p = Policy.DEFAULT

    /** A sentence on the take's clock, one word every 400ms from [startMs]. */
    private fun said(text: String, startMs: Long = 0, gapMs: Long = 400) =
        text.split(" ").filter { it.isNotBlank() }
            .mapIndexed { i, w -> SpokenWord(w, startMs + i * gapMs) }

    /** The same sentence from a recogniser with no word clocks at all. */
    private fun untimed(text: String) =
        text.split(" ").filter { it.isNotBlank() }.map { SpokenWord(it, 5_000) }

    // --- 1. a correction -------------------------------------------------

    @Test
    fun `the screw correction from the brief is recognised`() {
        val e = correctionEvidence(
            said("remove this screw no sorry not this one remove the side screw"),
            p,
            "en",
        )
        assertNotNull("this is the case the whole file exists for", e)
        assertTrue("what they settled on must survive", e!!.correctedText.contains("side screw"))
        assertTrue("and so must what they took back", e.supersededText.contains("this screw"))
    }

    @Test
    fun `a Hindi self-correction is recognised, in the script Vosk emits`() {
        // नहीं, not "nahi". A romanised-only marker list would match nothing
        // here while looking entirely correct in policy.json.
        val e = correctionEvidence(said("यह पेंच निकालो नहीं यह वाला नहीं साइड वाला पेंच निकालो"), p, "hi")
        assertNotNull(e)
        assertTrue(e!!.signals.any { it.contains("पेंच") })
    }

    @Test
    fun `the original words are preserved on both sides, never rewritten`() {
        val e = correctionEvidence(said("remove this screw no not this one remove that screw"), p, "en")!!
        assertEquals("remove this screw", e.supersededText)
        assertEquals("not this one remove that screw", e.correctedText)
    }

    // --- 2. no correction ------------------------------------------------

    @Test
    fun `an ordinary instruction with no retraction is null`() {
        assertNull(correctionEvidence(said("undo the ten screws around the base"), p, "en"))
    }

    @Test
    fun `silence is null rather than a crash`() {
        assertNull(correctionEvidence(emptyList(), p, "en"))
        assertNull(correctionEvidence(said("ok"), p, "en"))
    }

    // --- 3. a repeated instruction ---------------------------------------

    @Test
    fun `saying the same action again after a marker is what separates repair from digression`() {
        // The repeat is the load-bearing signal: a repair says the same thing
        // again. Without it the marker is just a word.
        val repair = correctionEvidence(said("pull the connector no pull the connector gently"), p, "en")
        assertNotNull(repair)
        assertTrue(repair!!.signals.any { it.contains("said again") })
    }

    @Test
    fun `a marker followed by an unrelated subject is not treated as a correction`() {
        // "no" then something else entirely. A change of subject, not a repair.
        assertNull(correctionEvidence(said("undo the base screws no bring the torch over"), p, "en"))
    }

    // --- 4. hesitation ---------------------------------------------------

    @Test
    fun `hesitation around the marker adds to the evidence`() {
        val with = correctionEvidence(said("lift the board no uh lift the board slowly"), p, "en")
        val without = correctionEvidence(said("lift the board no lift the board slowly"), p, "en")
        assertNotNull(with)
        assertNotNull(without)
        assertTrue("stumbling should count for something", with!!.strength > without!!.strength)
        assertTrue(with.signals.any { it.contains("hesitate") })
    }

    @Test
    fun `hesitation alone, with no retraction at all, is not a correction`() {
        // People stumble constantly. It raises the odds of a correction; it is
        // not one.
        assertNull(correctionEvidence(said("uh so we um undo the base screws now"), p, "en"))
    }

    // --- 5. a false correction phrase ------------------------------------

    @Test
    fun `no problem is not a correction`() {
        // The sentence a keyword matcher gets wrong, and the reason a marker is
        // never sufficient on its own.
        assertNull(correctionEvidence(said("this screw comes out easily no problem at all"), p, "en"))
    }

    @Test
    fun `a marker with a linking word after it reads as the next step, not a repair`() {
        // LinkWordConfirmer's list, used as evidence against. "then" starts a
        // new step; a repair does not announce itself that way.
        assertNull(
            correctionEvidence(said("undo the screw no problem then undo the other screw"), p, "en"),
        )
    }

    @Test
    fun `a retraction with nothing after it corrects nothing anyone can act on`() {
        // The expert trailed off, or the recogniser lost the rest. There is no
        // replacement instruction to prefer.
        assertNull(correctionEvidence(said("remove this screw no"), p, "en"))
    }

    @Test
    fun `a marker alone never clears the bar, whatever else is in the sentence`() {
        // The arithmetic behind "not a keyword matcher": the marker weight is
        // deliberately below the threshold, so corroboration is required.
        assertTrue(p.correctionMarkerWeight < p.correctionMinStrength)
    }

    // --- clocks ----------------------------------------------------------

    @Test
    fun `a nearby retraction scores higher than a distant one`() {
        val quick = correctionEvidence(said("remove this screw no remove that screw", gapMs = 300), p, "en")!!
        val slow = correctionEvidence(said("remove this screw no remove that screw", gapMs = 9_000), p, "en")!!
        assertTrue("an immediate repair is better evidence", quick.strength > slow.strength)
    }

    @Test
    fun `a recogniser with no word clocks still works, with timing abstaining`() {
        // The system engine returns sentences. Timing must not fire on every
        // step just because every word carries the same stamp.
        val e = correctionEvidence(untimed("remove this screw no sorry remove the side screw"), p, "en")
        assertNotNull(e)
        assertTrue(e!!.signals.none { it.contains("straight after") })
        assertEquals(5_000, e.retractionAtMs)
    }

    // --- the knobs are the knobs -----------------------------------------

    @Test
    fun `raising the bar in policy silences weak evidence, without a rebuild`() {
        val words = said("remove this screw no remove that screw")
        assertNotNull(correctionEvidence(words, p, "en"))
        assertNull(correctionEvidence(words, p.copy(correctionMinStrength = 0.99), "en"))
    }

    @Test
    fun `strength never leaves zero to one, however many signals pile up`() {
        val e = correctionEvidence(said("remove this screw no uh sorry remove this screw again"), p, "en")!!
        assertTrue(e.strength in 0.0..1.0)
    }
}
