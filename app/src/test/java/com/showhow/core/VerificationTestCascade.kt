package com.showhow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Does the bench look like the step, decided without waking a model.
 *
 * The rule the whole file is built around: there is no fourth value. Nothing
 * here can say BLOCKED, because nothing in this app disables Next, hides
 * Continue or refuses to advance on a camera's opinion. A learner holding the
 * board at a different angle is not wrong, and an app that stops them there is
 * worse than an app that says nothing.
 */
class VerificationTestCascade {

    private val p = Policy.DEFAULT

    private fun inputs(
        similarity: Float = 0.9f,
        change: Int = 0,
        expected: List<String> = listOf("laptop", "keyboard"),
        seen: List<String> = listOf("laptop", "keyboard"),
    ) = CheckInputs(similarity, change, expected, seen)

    // --- rung 1: has the scene settled? ----------------------------------

    @Test
    fun `a phone still moving is uncertain, whatever the frame happens to show`() {
        // The cheapest rung, and first for a reason: comparing a frame taken
        // mid-swing is how an app tells someone their correct bench looks wrong.
        assertEquals(StepCheck.UNCERTAIN, checkStep(inputs(change = 40)))
        assertEquals(StepCheck.UNCERTAIN, checkStep(inputs(similarity = 0.99f, change = 40)))
    }

    @Test
    fun `a settled scene is compared`() {
        assertEquals(StepCheck.CORRECT, checkStep(inputs(change = 2)))
    }

    @Test
    fun `the first frame of a step is treated as settled, not as a huge change`() {
        assertEquals(StepCheck.CORRECT, checkStep(inputs(change = 0)))
    }

    // --- rung 2 and 3: the photograph, and the labels --------------------

    @Test
    fun `looking like the photo with the same things in it is CORRECT`() {
        assertEquals(StepCheck.CORRECT, checkStep(inputs(similarity = 0.85f)))
    }

    @Test
    fun `the right things on a bench that looks nothing like the photo is CORRECT`() {
        // The whole point of the cascade's order. Everyone's desk, lighting and
        // camera angle are different, and a check that demands the frame look
        // like the expert's frame is a check nobody but the expert can pass.
        // The objects are the claim about the work; they travel between benches.
        assertEquals(
            StepCheck.CORRECT,
            checkStep(inputs(similarity = 0.05f, seen = listOf("laptop", "keyboard"))),
        )
    }

    @Test
    fun `a bench that looks right with the wrong things on it is not correct`() {
        // The other half, and the reason the scene comparison cannot rescue
        // anything: same desk, same light, same angle, work not done.
        assertEquals(
            StepCheck.UNCERTAIN,
            checkStep(inputs(similarity = 0.95f, seen = listOf("person"))),
        )
    }

    @Test
    fun `half the evidence is a step in progress, not a step finished`() {
        // One of the two expected things. Worth saying, not worth a page turn.
        assertEquals(
            StepCheck.LIKELY_CORRECT,
            checkStep(inputs(similarity = 0.85f, seen = listOf("laptop"))),
        )
    }

    @Test
    fun `two screws out is not the same state as one screw out`() {
        // Counted, not merely named. A set comparison called these identical,
        // which is how a panel with one screw still in read as finished.
        val twoOut = listOf("laptop", "philips_screw", "philips_screw")
        val oneOut = listOf("laptop", "philips_screw")
        // Two of the three boxes the photograph had, not all of them -- which a
        // set comparison would have said, because both lists name the same two
        // things.
        assertEquals(2.0 / 3.0, labelOverlap(twoOut, oneOut)!!, 1e-9)
        assertEquals(1.0, labelOverlap(twoOut, twoOut)!!, 1e-9)
        assertEquals(listOf("philips_screw"), labelShortfall(twoOut, oneOut))
        // And below the bar, so a panel with one screw still in does not read
        // as a panel with both out.
        assertEquals(StepCheck.LIKELY_CORRECT, checkStep(inputs(expected = twoOut, seen = oneOut)))
        assertEquals(StepCheck.CORRECT, checkStep(inputs(expected = twoOut, seen = twoOut)))
    }

    @Test
    fun `a plausible-looking scene alone is worth mentioning`() {
        assertEquals(
            StepCheck.LIKELY_CORRECT,
            checkStep(inputs(similarity = 0.60f, expected = emptyList(), seen = listOf("person"))),
        )
    }

    @Test
    fun `nothing matching anywhere is uncertain, not a verdict on the learner`() {
        assertEquals(
            StepCheck.UNCERTAIN,
            checkStep(inputs(similarity = 0.10f, seen = listOf("person", "bottle"))),
        )
    }

    // --- rung 4: absence is not disagreement ------------------------------

    @Test
    fun `camera off and nothing seen is uncertain`() {
        assertEquals(
            StepCheck.UNCERTAIN,
            checkStep(inputs(similarity = 0f, seen = emptyList())),
        )
    }

    @Test
    fun `a missing detector must not read as the learner getting it wrong`() {
        // No labels either side. The overlap abstains rather than scoring zero,
        // so the scene comparison decides alone -- and the answer is
        // LIKELY_CORRECT rather than CORRECT, deliberately.
        //
        // A phone with no detector model can never reach CORRECT, because
        // CORRECT means two independent signals agreed and only one of them
        // exists here. Claiming less on less evidence is the point. What
        // matters is that it is not UNCERTAIN and not a warning: a missing
        // model must never read as the learner having got something wrong.
        assertEquals(
            StepCheck.LIKELY_CORRECT,
            checkStep(inputs(similarity = 0.85f, expected = emptyList(), seen = emptyList())),
        )
        assertNull(labelOverlap(emptyList(), listOf("laptop")))
        assertNull(labelOverlap(listOf("laptop"), emptyList()))
    }

    @Test
    fun `overlap is a fraction of what the photo showed`() {
        assertEquals(1.0, labelOverlap(listOf("laptop"), listOf("laptop", "person"))!!, 1e-9)
        assertEquals(0.5, labelOverlap(listOf("laptop", "mouse"), listOf("laptop"))!!, 1e-9)
        assertEquals(0.0, labelOverlap(listOf("mouse"), listOf("laptop"))!!, 1e-9)
        // Case and spacing come off a detector unnormalised.
        assertEquals(1.0, labelOverlap(listOf(" Laptop "), listOf("laptop"))!!, 1e-9)
    }

    // --- what it must never do -------------------------------------------

    @Test
    fun `there are exactly three outcomes and none of them blocks`() {
        // The assertion that keeps a fourth value from ever being added
        // quietly. If BLOCKED appears here, a control somewhere can be disabled.
        assertEquals(
            listOf("CORRECT", "LIKELY_CORRECT", "UNCERTAIN"),
            StepCheck.entries.map { it.name },
        )
    }

    @Test
    fun `no model is consulted, so the same inputs always give the same answer`() {
        val i = inputs(similarity = 0.61f, seen = listOf("laptop"))
        val once = checkStep(i)
        repeat(5) { assertEquals(once, checkStep(i)) }
    }

    // --- the knobs are the knobs -----------------------------------------

    @Test
    fun `the thresholds are policy values, tunable without a rebuild`() {
        // How much of the photograph's evidence has to be on the bench.
        val half = inputs(similarity = 0.05f, seen = listOf("laptop"))
        assertEquals(StepCheck.LIKELY_CORRECT, checkStep(half, p))
        assertEquals(StepCheck.CORRECT, checkStep(half, p.copy(checkLabelOverlap = 0.5)))

        // And the scene thresholds, on the path where the objects abstain.
        val blind = inputs(similarity = 0.60f, expected = emptyList(), seen = emptyList())
        assertEquals(StepCheck.LIKELY_CORRECT, checkStep(blind, p))
        assertEquals(
            StepCheck.UNCERTAIN,
            checkStep(blind, p.copy(checkLikelySimilarity = 0.9f, checkCorrectSimilarity = 0.95f)),
        )
    }

    @Test
    fun `tightening the settle threshold makes it hold out for a steadier frame`() {
        val i = inputs(change = 10)
        assertEquals(StepCheck.CORRECT, checkStep(i, p))
        assertEquals(StepCheck.UNCERTAIN, checkStep(i, p.copy(checkSettledMaxChange = 4)))
    }

    // --- may the page turn by itself -------------------------------------

    @Test
    fun `the page turns when the scene and the labels both agree`() {
        assertTrue(mayAdvance(inputs(similarity = 0.85f), p))
    }

    @Test
    fun `a bench that merely looks similar does not turn the page`() {
        // Same desk, same light, hand out of shot, nothing actually done.
        // Well over the scene threshold on structure alone, and still no.
        assertFalse(mayAdvance(inputs(similarity = 0.95f, seen = listOf("person")), p))
    }

    @Test
    fun `still moving never turns the page, however well it matches`() {
        assertFalse(mayAdvance(inputs(similarity = 0.99f, change = 40), p))
    }

    @Test
    fun `the scene threshold does not hold back a bench whose objects agree`() {
        // It used to, and that was the bug: a learner working correctly on
        // their own desk sat at 58% forever. The objects agreeing is the
        // claim, and it does not need the furniture to match.
        assertTrue(mayAdvance(inputs(similarity = 0.05f), p))
    }

    @Test
    fun `with nothing to compare objects against, the scene threshold is the bar`() {
        val blind = inputs(expected = emptyList(), seen = emptyList())
        assertFalse(mayAdvance(blind.copy(sceneSimilarity = 0.65f), p))
        assertTrue(mayAdvance(blind.copy(sceneSimilarity = 0.71f), p))
    }

    @Test
    fun `a phone with no detector is not stranded on step one forever`() {
        // Nothing either side, so CORRECT is unreachable by design -- see the
        // test above. The scene comparison has to be allowed to decide alone,
        // or a guide built without a detector model can never move by itself.
        assertTrue(
            mayAdvance(inputs(similarity = 0.85f, expected = emptyList(), seen = emptyList()), p),
        )
        // But labels that exist and disagree still stop it.
        assertFalse(mayAdvance(inputs(similarity = 0.85f, seen = listOf("bottle")), p))
    }

    @Test
    fun `zero turns the whole behaviour off`() {
        assertFalse(mayAdvance(inputs(similarity = 1f), p.copy(advanceOnMatchSimilarity = 0f)))
    }
}
