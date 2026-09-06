package com.showhow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Does the bench look like the step, decided without waking a model.
 *
 * The rule the whole file is built around: there is no fourth value. Nothing
 * here can say BLOCKED, because nothing in this app disables Next, hides
 * Continue or refuses to advance on a camera's opinion. A learner holding the
 * board at a different angle is not wrong, and an app that stops them there is
 * worse than an app that says nothing.
 *
 * The second rule, and the reason this file was rewritten: a verdict is made
 * out of frames, plural. One frame is evidence, never an answer.
 */
class VerificationTestCascade {

    private val p = Policy.DEFAULT

    private fun inputs(
        similarity: Float = 0.9f,
        change: Int = 0,
        expected: List<String> = listOf("laptop", "keyboard"),
        seen: List<String> = listOf("laptop", "keyboard"),
    ) = CheckInputs(similarity, change, expected, seen)

    /** Hold the camera on the same thing for a while and see what it settles on. */
    private fun settle(
        i: CheckInputs,
        policy: Policy = p,
        frames: Int = 40,
        on: StepConfidence = StepConfidence(policy),
    ): StepConfidence {
        repeat(frames) { on.update(i) }
        return on
    }

    // --- one frame is evidence, not an answer ----------------------------

    @Test
    fun `a single perfect frame is not yet a verdict`() {
        // The bug this file exists to fix, half one: a hand passing across the
        // bench lands one frame over the threshold, and the old check called
        // that finished work and turned the page.
        val c = StepConfidence(p)
        c.update(inputs())
        assertEquals(StepCheck.LIKELY_CORRECT, c.check)
        assertFalse(c.mayAdvance)
    }

    @Test
    fun `holding the camera on a matching bench does get there`() {
        // ...and half two: it has to be reachable, or the learner stands over
        // a finished job holding a screwdriver while the app says nothing.
        val c = settle(inputs())
        assertEquals(StepCheck.CORRECT, c.check)
        assertTrue(c.mayAdvance)
        assertTrue("confidence ${c.value}", c.value > 0.9f)
    }

    @Test
    fun `confidence is earned over frames, not granted by one`() {
        val c = StepConfidence(p)
        val readings = (1..6).map { c.update(inputs()); c.value }
        assertEquals(readings.sorted(), readings)
        // Roughly half a second at one frame per 100 ms. Long enough that a
        // hand swinging past cannot buy it, short enough to not be a wait.
        assertTrue("$readings", readings.count { it < p.checkLabelOverlap } in 3..5)
    }

    // --- has the phone stopped moving? -----------------------------------

    @Test
    fun `a phone still swinging is uncertain, whatever the frame happens to show`() {
        // Comparing a frame taken mid-swing is how an app tells someone their
        // correct bench looks wrong.
        assertEquals(StepCheck.UNCERTAIN, settle(inputs(change = 40)).check)
        assertEquals(
            StepCheck.UNCERTAIN,
            settle(inputs(similarity = 0.99f, change = 40)).check,
        )
        assertEquals(0f, settleWeight(40, p), 1e-6f)
    }

    @Test
    fun `a slightly shaky phone counts for less, not for nothing`() {
        // The other half of "it could never make its mind up". 18 changed bits
        // is over the old hard cutoff of 14, so every such frame used to be
        // binned -- and a hand-held phone over a workbench is never still.
        assertTrue(settleWeight(18, p) in 0.5f..0.9f)
        assertEquals(StepCheck.CORRECT, settle(inputs(change = 18)).check)
        // It just takes longer to get there than a phone resting on the bench.
        val shaky = StepConfidence(p).also { repeat(5) { _ -> it.update(inputs(change = 18)) } }
        val steady = StepConfidence(p).also { repeat(5) { _ -> it.update(inputs(change = 0)) } }
        assertTrue(shaky.value < steady.value)
    }

    @Test
    fun `the first frame of a step is treated as settled, not as a huge change`() {
        assertEquals(StepCheck.CORRECT, settle(inputs(change = 0)).check)
    }

    @Test
    fun `tightening the settle threshold makes it hold out for a steadier frame`() {
        val i = inputs(change = 10)
        assertEquals(StepCheck.CORRECT, settle(i).check)
        assertEquals(StepCheck.UNCERTAIN, settle(i, p.copy(checkSettledMaxChange = 4)).check)
    }

    // --- what the frame is worth -----------------------------------------

    @Test
    fun `the right things on a bench that looks nothing like the photo is CORRECT`() {
        // Everyone's desk, lighting and camera angle are different, and a check
        // that demands the frame look like the expert's frame is a check nobody
        // but the expert can pass. The objects are the claim about the work;
        // they travel between benches.
        val c = settle(inputs(similarity = 0.05f, seen = listOf("laptop", "keyboard")))
        assertEquals(StepCheck.CORRECT, c.check)
        assertTrue(c.mayAdvance)
    }

    @Test
    fun `a bench that looks right with the wrong things on it is not correct`() {
        // Same desk, same light, same angle, work not done. The scene
        // comparison does not get to rescue this, however well it scores.
        val c = settle(inputs(similarity = 0.95f, seen = listOf("person")))
        assertEquals(StepCheck.UNCERTAIN, c.check)
        assertFalse(c.mayAdvance)
    }

    @Test
    fun `half the evidence is a step in progress, not a step finished`() {
        val c = settle(inputs(similarity = 0.85f, seen = listOf("laptop")))
        assertEquals(StepCheck.LIKELY_CORRECT, c.check)
        assertFalse(c.mayAdvance)
    }

    @Test
    fun `two screws out is not the same state as one screw out`() {
        // Counted, not merely named. A set comparison called these identical,
        // which is how a panel with one screw still in read as finished.
        val twoOut = listOf("laptop", "philips_screw", "philips_screw")
        val oneOut = listOf("laptop", "philips_screw")
        assertEquals(2.0 / 3.0, labelOverlap(twoOut, oneOut)!!, 1e-9)
        assertEquals(1.0, labelOverlap(twoOut, twoOut)!!, 1e-9)
        assertEquals(listOf("philips_screw"), labelShortfall(twoOut, oneOut))
        assertEquals(
            StepCheck.LIKELY_CORRECT,
            settle(inputs(expected = twoOut, seen = oneOut)).check,
        )
        assertEquals(
            StepCheck.CORRECT,
            settle(inputs(expected = twoOut, seen = twoOut)).check,
        )
    }

    @Test
    fun `evidence sitting exactly on the bar still reaches it`() {
        // Three of the four boxes the photograph had is 0.75 on every frame
        // forever, and 0.75 is the bar. An average that only approaches its
        // target would sit just underneath it for the rest of the session.
        val four = listOf("laptop", "keyboard", "mouse", "philips_screw")
        val three = four.dropLast(1)
        assertEquals(0.75, labelOverlap(four, three)!!, 1e-9)
        assertEquals(StepCheck.CORRECT, settle(inputs(expected = four, seen = three)).check)
    }

    @Test
    fun `nothing matching anywhere is uncertain, not a verdict on the learner`() {
        assertEquals(
            StepCheck.UNCERTAIN,
            settle(inputs(similarity = 0.10f, seen = listOf("person", "bottle"))).check,
        )
    }

    // --- absence is not disagreement --------------------------------------

    @Test
    fun `camera off and nothing seen is uncertain`() {
        assertEquals(
            StepCheck.UNCERTAIN,
            settle(inputs(similarity = 0f, seen = emptyList())).check,
        )
        assertNull(frameEvidence(inputs(similarity = 0f, seen = emptyList())))
    }

    @Test
    fun `a reach across the camera does not undo the last ten good frames`() {
        // Absence fades slower than evidence builds, on purpose. A hand between
        // the phone and the bench blanks two or three frames, and that is not
        // the learner having got something wrong.
        val c = settle(inputs())
        repeat(3) { c.update(CheckInputs(0f, 0, emptyList(), emptyList())) }
        assertEquals(StepCheck.CORRECT, c.check)
        assertTrue(c.mayAdvance)
    }

    @Test
    fun `a bench that stops matching does give the verdict back`() {
        // The other direction, and it has to work or the page turns on a step
        // the learner has already undone.
        val c = settle(inputs())
        repeat(20) { c.update(inputs(similarity = 0.1f, seen = listOf("person"))) }
        assertEquals(StepCheck.UNCERTAIN, c.check)
        assertFalse(c.mayAdvance)
    }

    @Test
    fun `a missing detector must not read as the learner getting it wrong`() {
        // No labels either side. The overlap abstains rather than scoring zero,
        // so the scene comparison decides alone -- and a phone with no detector
        // model still has to be able to move by itself, or every learner on
        // such a guide is stranded on step one.
        val c = settle(inputs(similarity = 0.85f, expected = emptyList(), seen = emptyList()))
        assertEquals(StepCheck.CORRECT, c.check)
        assertTrue(c.mayAdvance)
        assertNull(labelOverlap(emptyList(), listOf("laptop")))
        assertNull(labelOverlap(listOf("laptop"), emptyList()))
    }

    @Test
    fun `a plausible-looking scene alone is worth mentioning, not acting on`() {
        val c = settle(inputs(similarity = 0.60f, expected = emptyList(), seen = listOf("person")))
        assertEquals(StepCheck.LIKELY_CORRECT, c.check)
        assertFalse(c.mayAdvance)
    }

    @Test
    fun `with nothing to compare objects against, the scene threshold is the bar`() {
        val blind = inputs(expected = emptyList(), seen = emptyList())
        assertFalse(settle(blind.copy(sceneSimilarity = 0.60f)).mayAdvance)
        assertTrue(settle(blind.copy(sceneSimilarity = 0.71f)).mayAdvance)
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
    fun `no model is consulted, so the same frames always give the same answer`() {
        val i = inputs(similarity = 0.61f, seen = listOf("laptop"))
        val once = settle(i)
        repeat(5) {
            val again = settle(i)
            assertEquals(once.check, again.check)
            assertEquals(once.value, again.value, 1e-9f)
        }
    }

    @Test
    fun `a value resting on a band edge does not flicker`() {
        // Enter high, leave low. Two of three expected boxes is 0.667: under
        // the 0.75 that gets you into CORRECT, over the 0.65 that puts you
        // out of it. Whether the screen says "that looks right" therefore
        // depends on where it came from, which is the whole point -- a value
        // parked on a band edge must not repaint twice a second.
        val edge = listOf("laptop", "keyboard", "mouse")
        val nearly = inputs(expected = edge, seen = edge.dropLast(1))
        val fromAbove = settle(inputs(expected = edge, seen = edge))
        assertEquals(StepCheck.CORRECT, fromAbove.check)
        settle(nearly, on = fromAbove)
        assertTrue("value ${fromAbove.value}", fromAbove.value < p.checkLabelOverlap)
        assertEquals(StepCheck.CORRECT, fromAbove.check)
        // Coming up from nothing, the same number is not enough.
        assertEquals(StepCheck.LIKELY_CORRECT, settle(nearly).check)
    }

    @Test
    fun `a step change forgets the step before it`() {
        val c = settle(inputs())
        assertEquals(StepCheck.CORRECT, c.check)
        c.reset()
        assertEquals(StepCheck.UNCERTAIN, c.check)
        assertEquals(0f, c.value, 1e-9f)
        assertFalse(c.mayAdvance)
    }

    // --- the knobs are the knobs -----------------------------------------

    @Test
    fun `the thresholds are policy values, tunable without a rebuild`() {
        // How much of the photograph's evidence has to be on the bench.
        val half = inputs(similarity = 0.05f, seen = listOf("laptop"))
        assertEquals(StepCheck.LIKELY_CORRECT, settle(half).check)
        assertEquals(StepCheck.CORRECT, settle(half, p.copy(checkLabelOverlap = 0.5)).check)

        // And the scene thresholds, on the path where the objects abstain.
        val blind = inputs(similarity = 0.60f, expected = emptyList(), seen = emptyList())
        assertEquals(StepCheck.LIKELY_CORRECT, settle(blind).check)
        assertEquals(
            StepCheck.UNCERTAIN,
            settle(blind, p.copy(checkLikelySimilarity = 0.9f, checkCorrectSimilarity = 0.95f))
                .check,
        )
    }

    @Test
    fun `a slower rise coefficient makes it wait longer`() {
        val fast = StepConfidence(p.copy(confidenceRiseCoef = 0.5f))
        val slow = StepConfidence(p.copy(confidenceRiseCoef = 0.05f))
        repeat(4) { fast.update(inputs()); slow.update(inputs()) }
        assertTrue(fast.value > slow.value)
        assertEquals(StepCheck.CORRECT, fast.check)
        assertEquals(StepCheck.LIKELY_CORRECT, slow.check)
    }

    @Test
    fun `zero turns the whole page turn off`() {
        val c = settle(inputs(similarity = 1f), p.copy(advanceOnMatchSimilarity = 0f))
        // Still says what it sees -- it just never moves on its own.
        assertEquals(StepCheck.CORRECT, c.check)
        assertFalse(c.mayAdvance)
    }
}
