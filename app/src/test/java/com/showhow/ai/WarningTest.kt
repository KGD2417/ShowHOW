package com.showhow.ai

import com.showhow.data.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one line of care a step may carry, and who is giving it.
 *
 * Two failures, and they pull in opposite directions. A warning nobody needed
 * spends the learner's attention on a risk that is not there and teaches them
 * the app invents things -- so an empty note must stay empty rather than
 * becoming a polite sentence. And a warning the model thought of but attributed
 * to the expert puts a safety claim in a real person's mouth, which is worse
 * than either.
 *
 * Nothing here can stop a learner or an expert doing anything. A warning is
 * advice; the Player draws it and the Review screen shows it, and neither
 * consults it before enabling a control.
 */
class WarningTest {

    private fun step(transcript: String = "", caption: String = "") =
        TakeStep(startMs = 0, endMs = 5_000, transcript = transcript, caption = caption, hasPhoto = true)

    // --- 1. a warning is present -----------------------------------------

    @Test
    fun `a labelled warning keeps its text and its source`() {
        val out = parseRewrite(
            "1|EXPERT|Free the connector|Lift the latch first.|EXPERT: don't pull it by the wires",
            1,
        )[0]!!
        assertEquals("don't pull it by the wires", out.note)
        assertEquals(Provenance.EXPERT, out.noteSource)
    }

    @Test
    fun `the sentence survives even when the label is written loosely`() {
        // A caution the expert may have given is worth more than a tidy column,
        // so every one of these keeps the text.
        for (raw in listOf(
            "GENERAL: keep track of the removed screws",
            "general - keep track of the removed screws",
            "[GENERAL] keep track of the removed screws",
            "**GENERAL**: keep track of the removed screws",
        )) {
            val (source, text) = splitNote(raw)
            assertTrue(raw, text.contains("keep track of the removed screws"))
            assertEquals(raw, Provenance.GENERAL, source)
        }
    }

    @Test
    fun `an unlabelled warning is kept, as UNKNOWN for grounding to settle`() {
        val (source, text) = splitNote("keep track of the removed screws")
        assertEquals("keep track of the removed screws", text)
        assertEquals(Provenance.UNKNOWN, source)
    }

    // --- 2. no warning ---------------------------------------------------

    @Test
    fun `an empty note stays empty rather than becoming a source with no text`() {
        // Null is the preferred answer and the prompt says so. This is where
        // that is honoured instead of quietly turned into "" with a label on it.
        for (raw in listOf("", "  ", "-", "none", "None", "N/A", "no warning", "EXPERT: none")) {
            val (source, text) = splitNote(raw)
            assertEquals(raw, "", text)
            assertEquals(raw, Provenance.UNKNOWN, source)
        }
    }

    @Test
    fun `a step whose line has no note column at all carries no warning`() {
        val out = parseRewrite("1|EXPERT|Power down|Shut it down.", 1)[0]!!
        assertEquals("", out.note)
        assertEquals(Provenance.UNKNOWN, out.noteSource)
    }

    @Test
    fun `a blank warning grounds to UNKNOWN however loudly it is labelled`() {
        assertEquals(
            Provenance.UNKNOWN,
            groundedSource(Provenance.EXPERT, step(transcript = "said plenty"), ""),
        )
    }

    // --- 3. a general warning --------------------------------------------

    @Test
    fun `the model's own repair knowledge stays GENERAL even where the expert spoke`() {
        // A humbler claim than the evidence is always honoured. The model
        // knows it added this; nothing should promote it.
        assertEquals(
            Provenance.GENERAL,
            groundedSource(
                Provenance.GENERAL,
                step(transcript = "अब पेंच खोलो", caption = "laptop"),
                "Keep track of the removed screws.",
            ),
        )
    }

    @Test
    fun `a warning claimed as the expert's over a silent step is demoted`() {
        // Rule four, as arithmetic. The expert said nothing here, so nothing
        // here may be reported as something they said.
        assertEquals(
            Provenance.VISUAL,
            groundedSource(Provenance.EXPERT, step(caption = "laptop, screwdriver"), "Mind the clips."),
        )
        assertEquals(
            Provenance.GENERAL,
            groundedSource(Provenance.EXPERT, step(), "Mind the clips."),
        )
    }

    @Test
    fun `an unlabelled warning cannot become the expert's by default`() {
        // UNKNOWN is the floor, not a hint to guess upward from.
        assertEquals(
            Provenance.UNKNOWN,
            groundedSource(Provenance.UNKNOWN, step(transcript = "अब पेंच खोलो"), "Mind the clips."),
        )
    }

    // --- 4. malformed output ---------------------------------------------

    @Test
    fun `a source token the model invented does not lose the warning`() {
        val (source, text) = splitNote("MANUAL: torque to 0.4 Nm")
        assertEquals("MANUAL: torque to 0.4 Nm", text)
        assertEquals(Provenance.UNKNOWN, source)
    }

    @Test
    fun `a warning containing a colon of its own is not cut in half`() {
        val (source, text) = splitNote("GENERAL: two things: the screws and the clips")
        assertEquals("two things: the screws and the clips", text)
        assertEquals(Provenance.GENERAL, source)
    }

    @Test
    fun `a warning containing a pipe stays whole`() {
        val out = parseRewrite("1|EXPERT|A|do it|GENERAL: mind the clips | and the wires", 1)[0]!!
        assertEquals("mind the clips | and the wires", out.note)
        assertEquals(Provenance.GENERAL, out.noteSource)
    }

    @Test
    fun `a line that is nothing but a label carries no warning`() {
        assertEquals(Provenance.UNKNOWN to "", splitNote("GENERAL:"))
        assertEquals(Provenance.UNKNOWN to "", splitNote("EXPERT: -"))
    }

    @Test
    fun `a malformed note never takes the step down with it`() {
        // The instruction is worth more than the column beside it.
        val out = parseRewrite("1|EXPERT|Power down|Shut it down.|::::", 1)[0]!!
        assertEquals("Shut it down.", out.instruction)
        assertEquals(Provenance.EXPERT, out.source)
    }

    @Test
    fun `a SKIP line may still carry why it was skipped`() {
        val out = parseRewrite("2|SKIP|||GENERAL: the expert's phone rang here", 3)[1]!!
        assertTrue(out.aside)
        assertEquals("the expert's phone rang here", out.note)
    }

    // --- nothing here gates anything -------------------------------------

    @Test
    fun `a warning changes nothing else about its step`() {
        // The closest a JVM test gets to rule seven. A caution is text and a
        // label; it does not mark the step an aside, blank its instruction or
        // weaken its provenance, so there is nothing here for the Player to
        // branch on when it decides whether Next works.
        val plain = parseRewrite("1|EXPERT|Free the connector|Lift the latch.|", 1)[0]!!
        val warned = parseRewrite(
            "1|EXPERT|Free the connector|Lift the latch.|EXPERT: mind the wires",
            1,
        )[0]!!
        assertEquals(plain.instruction, warned.instruction)
        assertEquals(plain.title, warned.title)
        assertEquals(plain.source, warned.source)
        assertEquals(plain.aside, warned.aside)
        assertEquals("mind the wires", warned.note)
        assertNull(plain.note.ifBlank { null })
    }
}
