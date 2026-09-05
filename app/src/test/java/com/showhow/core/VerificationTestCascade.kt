package com.showhow.core

import org.junit.Assert.assertEquals
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
    fun `one signal without the other is LIKELY_CORRECT, never CORRECT`() {
        // Right angle, part already removed.
        assertEquals(
            StepCheck.LIKELY_CORRECT,
            checkStep(inputs(similarity = 0.85f, seen = listOf("person"))),
        )
        // Right things in shot, different angle.
        assertEquals(
            StepCheck.LIKELY_CORRECT,
            checkStep(inputs(similarity = 0.30f, seen = listOf("laptop", "keyboard"))),
        )
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
        val i = inputs(similarity = 0.60f, seen = listOf("person"))
        assertEquals(StepCheck.LIKELY_CORRECT, checkStep(i, p))
        assertEquals(
            StepCheck.UNCERTAIN,
            checkStep(i, p.copy(checkLikelySimilarity = 0.9f, checkCorrectSimilarity = 0.95f)),
        )
    }

    @Test
    fun `tightening the settle threshold makes it hold out for a steadier frame`() {
        val i = inputs(change = 10)
        assertEquals(StepCheck.CORRECT, checkStep(i, p))
        assertEquals(StepCheck.UNCERTAIN, checkStep(i, p.copy(checkSettledMaxChange = 4)))
    }
}
