package com.showhow.ai

import com.showhow.data.Guide
import com.showhow.data.Provenance
import com.showhow.data.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the coach is told before it answers, and how far its answer is allowed
 * to go.
 *
 * The sentence this file exists to prevent is "the guide says use a PH0" when
 * the guide says nothing of the kind. A learner cannot tell the difference
 * between the expert's word and a model's guess once they have been blurred,
 * and they are holding a screwdriver over a live board while they decide.
 *
 * So the rule, here as everywhere else in this app: a claim may always be
 * weaker than the evidence and never stronger.
 */
class LearnerContextTest {

    private val tools = listOf("screwdriver", "phillips", "ph0", "spudger")

    private fun guide(vararg steps: Step) = Guide(
        id = "g1",
        title = "Replacing laptop RAM",
        verifiedAt = 1_700_000_000_000,
        steps = steps.toList(),
    )

    private fun ctx(
        guide: Guide,
        step: Int = 0,
        question: String = "is this the right screwdriver?",
        seen: List<String> = emptyList(),
    ) = learnerContext(guide, step, question, seen, tools)

    // --- assembling the context ------------------------------------------

    @Test
    fun `the current step is the primary context and the rest stays available`() {
        val c = ctx(
            guide(
                Step(index = 0, instruction = "Shut the laptop down."),
                Step(index = 1, instruction = "Undo the ten base screws."),
                Step(index = 2, instruction = "Lift the RAM module out."),
            ),
            step = 1,
        )
        assertEquals(2, c.stepNumber)
        assertEquals(3, c.totalSteps)
        assertEquals("Undo the ten base screws.", c.instruction)
        assertTrue("the step before must travel with it", c.previous.contains("Shut the laptop down"))
        assertTrue("and the step after", c.next.contains("Lift the RAM module out"))
    }

    @Test
    fun `the first and last steps have no neighbour to summarise`() {
        val g = guide(Step(index = 0, instruction = "One."), Step(index = 1, instruction = "Two."))
        assertEquals("", ctx(g, step = 0).previous)
        assertEquals("", ctx(g, step = 1).next)
    }

    @Test
    fun `provenance travels with the step, so the model knows what it is reading`() {
        val c = ctx(
            guide(
                Step(
                    index = 0,
                    instruction = "Use a PH0 driver.",
                    instructionSource = Provenance.GENERAL,
                    warning = "keep track of the screws",
                    warningSource = Provenance.GENERAL,
                ),
            ),
        )
        assertEquals(Provenance.GENERAL, c.instructionSource)
        assertEquals(Provenance.GENERAL, c.warningSource)
        assertTrue(renderContext(c).contains("GENERAL"))
    }

    @Test
    fun `expected tools come from what the expert said, never from the detector`() {
        // The loaded detector knows COCO classes and has no label for a
        // screwdriver, so a tool reaching the prompt means a human named it.
        val c = ctx(
            guide(
                Step(
                    index = 0,
                    transcript = "छोटा phillips screwdriver लो",
                    caption = "laptop, keyboard",
                ),
            ),
        )
        assertEquals(listOf("screwdriver", "phillips"), c.expectedTools)
        assertFalse("a COCO label is not a tool", c.expectedTools.contains("laptop"))
    }

    @Test
    fun `expected objects are the detector's labels for this step's photo`() {
        val c = ctx(guide(Step(index = 0, caption = "laptop, keyboard, mouse")))
        assertEquals(listOf("laptop", "keyboard", "mouse"), c.expectedObjects)
    }

    @Test
    fun `live detections are carried separately from what the photo showed`() {
        val c = ctx(guide(Step(index = 0, caption = "laptop")), seen = listOf("laptop", "person", "laptop"))
        assertEquals(listOf("laptop", "person"), c.seenNow)
        assertEquals(listOf("laptop"), c.expectedObjects)
    }

    @Test
    fun `a guide with no steps at all does not crash the context`() {
        val c = ctx(Guide(id = "empty"))
        assertEquals(0, c.totalSteps)
        assertFalse(c.hasGuideEvidence)
    }

    @Test
    fun `whether the guide was verified is part of what the model is told`() {
        assertTrue(renderContext(ctx(guide(Step(index = 0)))).contains("checked by the expert"))
        val draft = learnerContext(Guide(id = "g", steps = listOf(Step(0))), 0, "?", emptyList(), tools)
        assertTrue(renderContext(draft).contains("has not checked"))
    }

    // --- 1. DIRECT_GUIDE_FACT --------------------------------------------

    @Test
    fun `a guide that names the screwdriver supports a direct answer`() {
        val c = ctx(guide(Step(index = 0, instruction = "Use the PH0 screwdriver on the base screws.")))
        val (evidence, text) = parseAnswer("[guide] The PH0, as this step says.", c)
        assertEquals(AnswerEvidence.DIRECT_GUIDE_FACT, evidence)
        assertEquals("The PH0, as this step says.", text)
    }

    @Test
    fun `the expert's own words support it too, even with nothing rewritten`() {
        val c = ctx(guide(Step(index = 0, transcript = "छोटा वाला पेचकस लो")))
        assertEquals(AnswerEvidence.DIRECT_GUIDE_FACT, parseAnswer("[guide] The small one.", c).first)
    }

    // --- 2. VISUAL_FACT ---------------------------------------------------

    @Test
    fun `a live detection supports a visual answer`() {
        val c = ctx(guide(Step(index = 0)), seen = listOf("laptop", "keyboard"))
        assertEquals(AnswerEvidence.VISUAL_FACT, parseAnswer("[seen] A laptop is in frame.", c).first)
    }

    @Test
    fun `a visual claim with nothing detected anywhere falls to general knowledge`() {
        // Camera off and no photo. There is no observation to have made.
        val c = ctx(guide(Step(index = 0)))
        assertEquals(
            AnswerEvidence.GENERAL_KNOWLEDGE,
            parseAnswer("[seen] I can see the module.", c).first,
        )
    }

    // --- 3. GENERAL_KNOWLEDGE --------------------------------------------

    @Test
    fun `knowing what laptops usually take is general knowledge, not the guide`() {
        val c = ctx(guide(Step(index = 0, instruction = "Undo the base screws.")))
        val (evidence, text) = parseAnswer(
            "${Coach.BEYOND} Laptop base screws are usually Phillips PH0.",
            c,
        )
        assertEquals(AnswerEvidence.GENERAL_KNOWLEDGE, evidence)
        assertFalse("the marker must not survive into the answer", text.contains(Coach.BEYOND))
    }

    @Test
    fun `general knowledge is never promoted to a guide fact, however rich the guide`() {
        // The rule stated outright. A model that knows a thing and a guide that
        // says it are different claims and the weaker one always wins.
        val c = ctx(
            guide(
                Step(index = 0, instruction = "Undo the base screws.", transcript = "पेंच खोलो", caption = "laptop"),
            ),
            seen = listOf("laptop"),
        )
        assertEquals(
            AnswerEvidence.GENERAL_KNOWLEDGE,
            groundedEvidence(AnswerEvidence.GENERAL_KNOWLEDGE, c),
        )
    }

    @Test
    fun `a guide claim over a step the guide says nothing about is demoted`() {
        // The sentence this file exists to prevent.
        val c = ctx(guide(Step(index = 0, caption = "laptop")), seen = listOf("laptop"))
        assertEquals(
            AnswerEvidence.VISUAL_FACT,
            parseAnswer("[guide] The guide says use a PH0.", c).first,
        )
        val blank = ctx(guide(Step(index = 0)))
        assertEquals(
            AnswerEvidence.GENERAL_KNOWLEDGE,
            parseAnswer("[guide] The guide says use a PH0.", blank).first,
        )
    }

    // --- 4. UNCERTAIN -----------------------------------------------------

    @Test
    fun `nothing identifying the screwdriver means uncertain, and is still an answer`() {
        // The brief's own example. The guide has content, so the ceiling is a
        // guide fact -- and the model saying it does not know is believed.
        val c = ctx(guide(Step(index = 0, instruction = "Undo the base screws.")))
        val (evidence, text) = parseAnswer(
            "${Coach.UNSURE} Nothing here says which driver. Match it to the screw head before turning.",
            c,
        )
        assertEquals(AnswerEvidence.UNCERTAIN, evidence)
        assertTrue("uncertain must never mean silent", text.isNotBlank())
        assertFalse(text.contains(Coach.UNSURE))
    }

    @Test
    fun `an answer that is part guide and part guesswork counts as the guesswork`() {
        val c = ctx(guide(Step(index = 0, instruction = "Undo the base screws.")))
        assertEquals(
            AnswerEvidence.UNCERTAIN,
            parseAnswer("[guide] Undo them. ${Coach.UNSURE} I cannot tell which driver.", c).first,
        )
    }

    @Test
    fun `an untagged answer is uncertain rather than assumed to be the guide`() {
        val c = ctx(guide(Step(index = 0, instruction = "Undo the base screws.")))
        assertEquals(AnswerEvidence.UNCERTAIN, parseAnswer("Use the small one.", c).first)
    }

    @Test
    fun `no answer at all is uncertain and empty, never a refusal message`() {
        val c = ctx(guide(Step(index = 0, instruction = "Undo the base screws.")))
        assertEquals(AnswerEvidence.UNCERTAIN to "", parseAnswer("", c))
        assertEquals(AnswerEvidence.UNCERTAIN to "", parseAnswer("   [guide]  ", c))
    }

    // --- the model's habits ----------------------------------------------

    @Test
    fun `the tag is found wherever the model puts it, and stripped`() {
        val c = ctx(guide(Step(index = 0, instruction = "Undo the base screws.")))
        for (raw in listOf(
            "[guide] Undo them.",
            "Answer: [guide] Undo them.",
            "**[GUIDE]** Undo them.",
            "[Guide] - Undo them.",
        )) {
            val (evidence, text) = parseAnswer(raw, c)
            assertEquals(raw, AnswerEvidence.DIRECT_GUIDE_FACT, evidence)
            assertEquals(raw, "Undo them.", text)
        }
    }

    @Test
    fun `the general marker keeps the spelling the Player has always used`() {
        assertEquals("[general]", Coach.BEYOND)
        assertEquals("[uncertain]", Coach.UNSURE)
    }
}
