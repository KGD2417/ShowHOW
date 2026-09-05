package com.showhow.eval

import com.showhow.ai.AnswerEvidence
import com.showhow.ai.TakeStep
import com.showhow.ai.groundedEvidence
import com.showhow.ai.groundedSource
import com.showhow.ai.learnerContext
import com.showhow.ai.parseAnswer
import com.showhow.ai.parseRewrite
import com.showhow.core.Policy
import com.showhow.core.correctionEvidence
import com.showhow.data.Guide
import com.showhow.data.Provenance
import com.showhow.data.Step
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters

/**
 * ShowHow's AI behaviour, evaluated end to end, with no model file.
 *
 * Each fixture is pushed through the real functions the app uses -- the same
 * correction detector, the same response parser, the same grounding, the same
 * context builder -- and the report at the bottom says what each one showed.
 *
 * **What it does not evaluate.** Whether Gemma writes a good instruction. That
 * needs a phone with 2 GB free and a `.task` file, and no amount of JVM testing
 * substitutes for it. What it does evaluate is every way this app could take a
 * model's output and turn it into a claim the expert never made, which is the
 * failure a hackathon jury cannot see and a learner cannot recover from.
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class EvalHarnessTest {

    private val p = Policy.DEFAULT
    private fun fixture(id: Int) = FIXTURES.first { it.id == id }

    /** Everything the build pipeline would do to one fixture, minus the model. */
    private fun build(f: Fixture): Step {
        val take = TakeStep(
            startMs = 0,
            endMs = 8_000,
            transcript = f.words.joinToString(" ") { it.text },
            caption = f.captions.firstOrNull().orEmpty(),
            hasPhoto = f.captions.isNotEmpty(),
            correction = correctionEvidence(f.words, p, "hi")?.let { e ->
                "they say \"${e.supersededText}\", then \"${e.correctedText}\""
            }.orEmpty(),
        )
        val raw = f.coachReply ?: return Step(
            index = 0,
            title = "Step 1",
            transcript = take.transcript,
            caption = take.caption,
        )
        val c = parseRewrite(raw, 1)[0]
            ?: return Step(index = 0, transcript = take.transcript, caption = take.caption)
        return Step(
            index = 0,
            title = c.title.ifBlank { "Step 1" },
            transcript = take.transcript,
            caption = take.caption,
            instruction = c.instruction,
            instructionSource = groundedSource(c.source, take, c.instruction),
            aside = c.aside,
            warning = c.note.ifBlank { null },
            warningSource = groundedSource(c.noteSource, take, c.note),
        )
    }

    /** Everything the ask sheet would do to one Q&A fixture, minus the model. */
    private fun ask(f: Fixture, guide: Guide): Pair<AnswerEvidence, String> {
        val ctx = learnerContext(guide, 0, f.question, f.seenNow, p.toolWords)
        return f.answerReply?.let { parseAnswer(it, ctx) }
            ?: (groundedEvidence(AnswerEvidence.UNCERTAIN, ctx) to "")
    }

    private fun guideNaming(driver: String) = Guide(
        id = "g",
        title = "Replacing laptop RAM",
        verifiedAt = 1_700_000_000_000,
        steps = listOf(Step(index = 0, instruction = driver, caption = "laptop")),
    )

    // --- 1..5 building a guide from real narration -----------------------

    @Test
    fun eval01_normalNarration() {
        val s = build(fixture(1))
        assertEquals("Power down", s.title)
        assertEquals(Provenance.EXPERT, s.instructionSource)
        assertFalse(s.aside)
        assertTrue("the expert's own words are never overwritten", s.transcript.contains("लैपटॉप"))
        record(1, "step built, instruction EXPERT, transcript intact")
    }

    @Test
    fun eval02_hesitation() {
        // "मतलब" is a filler, not a retraction. Nothing was taken back.
        assertNull(
            "a stumble must not read as a correction",
            correctionEvidence(fixture(2).words, p, "hi"),
        )
        assertEquals(Provenance.EXPERT, build(fixture(2)).instructionSource)
        record(2, "no correction claimed; step built normally")
    }

    @Test
    fun eval03_expertCorrection() {
        val e = correctionEvidence(fixture(3).words, p, "en")
        assertNotNull("the brief's own example must be detected", e)
        assertTrue(e!!.correctedText.contains("side screw"))
        assertTrue("what was taken back is kept, not deleted", e.supersededText.contains("this screw"))
        val s = build(fixture(3))
        assertTrue("the guide must carry the corrected action", s.instruction.contains("side"))
        record(3, "correction detected (${e.signals.size} signals); corrected action in the guide")
    }

    @Test
    fun eval04_irrelevantNarration() {
        val s = build(fixture(4))
        assertTrue("an aside is flagged", s.aside)
        assertTrue("and never deleted -- its audio is evidence", s.transcript.isNotBlank())
        record(4, "aside flagged, transcript and slice retained")
    }

    @Test
    fun eval05_repeatedInstruction() {
        val e = correctionEvidence(fixture(5).words, p, "en")
        assertNotNull(e)
        assertTrue(e!!.signals.any { it.contains("said again") })
        val s = build(fixture(5))
        assertEquals("not by the wires", s.warning)
        assertEquals(Provenance.EXPERT, s.warningSource)
        record(5, "repeat detected; warning kept with EXPERT provenance")
    }

    // --- 6..8 missing components -----------------------------------------

    @Test
    fun eval06_missingAsr() {
        val s = build(fixture(6))
        assertEquals("", s.transcript)
        assertTrue("a silent step is still a step", s.instruction.isNotBlank())
        assertEquals(
            "with nothing said, it can only be VISUAL",
            Provenance.VISUAL,
            s.instructionSource,
        )
        record(6, "step survives with no ASR; provenance falls to VISUAL")
    }

    @Test
    fun eval07_missingDetector() {
        // The model claimed VISUAL on a phone with no detector model. There
        // were no labels for it to have seen.
        //
        // It falls to GENERAL and deliberately not to EXPERT, even though the
        // expert did speak here and the instruction plainly came from what they
        // said. Moving a claim *up* to the expert's word on the app's own
        // reasoning is the one thing this mechanism may never do -- the model
        // did not say the expert told it, so the app will not say so either.
        val s = build(fixture(7))
        assertEquals("", s.caption)
        assertEquals(
            "a visual claim with no detector falls to GENERAL, never up to EXPERT",
            Provenance.GENERAL,
            s.instructionSource,
        )
        record(7, "no captions; VISUAL claim falls to GENERAL, never promoted to EXPERT")
    }

    @Test
    fun eval08_missingCoach() {
        val s = build(fixture(8))
        assertEquals("", s.instruction)
        assertEquals(Provenance.UNKNOWN, s.instructionSource)
        assertTrue("the expert's words are all there is, and they are enough", s.transcript.isNotBlank())
        assertNull("no coach means no invented warning", s.warning)
        record(8, "no coach: expert's words kept, nothing invented, UNKNOWN provenance")
    }

    // --- 9..14 answering a learner --------------------------------------

    @Test
    fun eval09_correctScrewdriverQuestion() {
        val (e, text) = ask(fixture(9), guideNaming("Use the PH0 Phillips on the base screws."))
        assertEquals(AnswerEvidence.DIRECT_GUIDE_FACT, e)
        assertTrue(text.contains("PH0"))
        record(9, "DIRECT_GUIDE_FACT: the guide names the driver")
    }

    @Test
    fun eval10_unsupportedScrewdriverQuestion() {
        // The brief's example. The guide has content, so the ceiling is a guide
        // fact -- and a model admitting it does not know is believed.
        val (e, text) = ask(fixture(10), guideNaming("Undo the base screws."))
        assertEquals(AnswerEvidence.UNCERTAIN, e)
        assertTrue("uncertain must never mean silent", text.isNotBlank())
        assertTrue("it should say what would settle it", text.contains("screw head"))
        record(10, "UNCERTAIN, and still answered with what would settle it")
    }

    @Test
    fun eval11_ramLocationQuestion() {
        val (e, _) = ask(fixture(11), guideNaming("The RAM sits under the shield beside the fan."))
        assertEquals(AnswerEvidence.DIRECT_GUIDE_FACT, e)
        record(11, "DIRECT_GUIDE_FACT: answered from the guide")
    }

    @Test
    fun eval12_ssdLocationQuestion() {
        // The detector reported laptop and keyboard. It has no SSD label and
        // never will until a different .tflite is on the phone. The claim is
        // capped at what a detector could have contributed, and the label the
        // learner sees says "what the camera can see", not "the guide says".
        val (e, _) = ask(fixture(12), Guide(id = "g", steps = listOf(Step(0, caption = "laptop"))))
        assertEquals(AnswerEvidence.VISUAL_FACT, e)
        assertTrue(
            "the detector's real vocabulary is laptop-and-keyboard, not SSD",
            fixture(12).seenNow.none { it.contains("ssd", true) },
        )
        record(12, "VISUAL_FACT ceiling; detector vocabulary is COCO, no SSD label exists")
    }

    @Test
    fun eval13_generalKnowledgeQuestion() {
        val (e, text) = ask(fixture(13), guideNaming("Undo the base screws."))
        assertEquals(
            "knowing a thing is not the guide saying it",
            AnswerEvidence.GENERAL_KNOWLEDGE,
            e,
        )
        assertFalse("the marker must not leak into the answer", text.contains("[general]"))
        record(13, "GENERAL_KNOWLEDGE, never promoted to a guide fact")
    }

    @Test
    fun eval14_uncertainVisualQuestion() {
        // "[seen] yes that looks correct" with the camera off and no photo.
        // Nothing looked at anything.
        val (e, _) = ask(fixture(14), Guide(id = "g", steps = listOf(Step(0))))
        assertEquals(AnswerEvidence.GENERAL_KNOWLEDGE, e)
        record(14, "visual claim with nothing detected demoted to GENERAL_KNOWLEDGE")
    }

    // --- malformed and adversarial ---------------------------------------

    @Test
    fun eval15_malformedCoachOutput() {
        assertEquals(listOf(null, null), parseRewrite("I'm sorry, I can't help with that.", 2))
        assertEquals(listOf(null), parseRewrite("{\"steps\": [", 1))
        record(15, "malformed replies drop to null slots; the expert's words stand")
    }

    @Test
    fun eval16_noAnswerIsNotARefusal() {
        val ctx = learnerContext(guideNaming("Undo the screws."), 0, "?", emptyList(), p.toolWords)
        assertEquals(AnswerEvidence.UNCERTAIN to "", parseAnswer("", ctx))
        record(16, "an empty reply is UNCERTAIN and empty, never a refusal message")
    }

    companion object {
        private val results = sortedMapOf<Int, String>()

        fun record(id: Int, outcome: String) {
            results[id] = outcome
        }

        @JvmStatic
        @AfterClass
        fun report() {
            val w = StringBuilder("\n")
            w.appendLine("=".repeat(78))
            w.appendLine("ShowHow AI evaluation -- ${results.size} scenarios, no model file required")
            w.appendLine("=".repeat(78))
            for ((id, outcome) in results) {
                val f = FIXTURES.firstOrNull { it.id == id }
                val name = f?.name ?: "adversarial"
                w.appendLine("PASS  %2d  %-32s %s".format(id, name, outcome))
                if (f != null) w.appendLine("          checks: ${f.checks}")
            }
            w.appendLine("-".repeat(78))
            w.appendLine("NOT COVERED, and no JVM test can cover it:")
            w.appendLine("  whether the coach writes a good instruction, its latency, and its")
            w.appendLine("  memory use. That needs a .task file on a phone. Everything above is")
            w.appendLine("  about what this app does with a model's output, not how good it is.")
            w.appendLine("=".repeat(78))
            println(w)
        }
    }
}
