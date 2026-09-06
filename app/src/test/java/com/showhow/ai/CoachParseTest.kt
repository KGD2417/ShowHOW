package com.showhow.ai

import com.showhow.data.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layer between a 2B model's habits and the guide a stranger reads.
 *
 * This is the only part of the coach testable without a phone, and it is the
 * part that decides what a real person is recorded as having said. Every input
 * below is a shape a small model actually produced.
 *
 * The format is `number|SOURCE|title|instruction|note` and not JSON on purpose:
 * tasks-genai 0.10.35 has no constrained decoding, so the shape is a request
 * rather than a guarantee, and JSON fails as one piece -- one unbalanced brace
 * costs every step including the eight that were right. Lines fail one at a
 * time.
 */
class CoachParseTest {

    private fun step(transcript: String = "", caption: String = "", photo: Boolean = true) =
        TakeStep(startMs = 0, endMs = 5000, transcript = transcript, caption = caption, hasPhoto = photo)

    // --- placement -------------------------------------------------------

    @Test
    fun `clean output lands in order`() {
        val out = parseRewrite(
            """
            1|EXPERT|Power down|Shut the laptop down and unplug the charger.|
            2|EXPERT|Remove the base|Undo the ten screws around the edge.|
            """.trimIndent(),
            2,
        )
        assertEquals("Power down", out[0]?.title)
        assertEquals("Undo the ten screws around the edge.", out[1]?.instruction)
        assertEquals(Provenance.EXPERT, out[0]?.source)
    }

    @Test
    fun `preamble and markdown around the lines are ignored`() {
        // Gemma will not stop saying "Here is the guide:" and will not stop
        // bolding things, however firmly the prompt asks.
        val out = parseRewrite(
            """
            Sure! Here is the rewritten guide:

            **1.** | EXPERT | Power down | Shut it down first. |
            - 2 | VISUAL | Open the base | Undo the screws. |

            Let me know if you need anything else.
            """.trimIndent(),
            2,
        )
        assertEquals("Power down", out[0]?.title)
        assertEquals("Open the base", out[1]?.title)
        assertEquals(Provenance.VISUAL, out[1]?.source)
    }

    @Test
    fun `a skipped step leaves its slot null rather than shifting the rest`() {
        // The whole reason placement is by number. Shifting would put step
        // four's instruction over step three's photo, which reads as the app
        // being broken rather than the model being lazy.
        val out = parseRewrite("1|EXPERT|A|first|\n3|EXPERT|C|third|", 3)
        assertEquals("A", out[0]?.title)
        assertNull(out[1])
        assertEquals("C", out[2]?.title)
    }

    @Test
    fun `a step number past the end is dropped, not clamped`() {
        val out = parseRewrite("1|EXPERT|A|first|\n9|EXPERT|Z|invented|", 2)
        assertEquals(1, out.count { it != null })
        assertEquals("A", out[0]?.title)
    }

    @Test
    fun `the first line for a step wins, so a repeat cannot overwrite it`() {
        val out = parseRewrite("1|EXPERT|Good|the real one|\n1|EXPERT|Bad|second attempt|", 1)
        assertEquals("Good", out[0]?.title)
    }

    @Test
    fun `nothing usable gives all nulls rather than blank steps`() {
        // All-null is also what a missing model produces, and it means the
        // caller keeps the expert's own words on every step.
        assertEquals(listOf(null, null), parseRewrite("", 2))
        assertEquals(listOf(null, null), parseRewrite("I could not do that.", 2))
    }

    // --- the job's name ---------------------------------------------------

    @Test
    fun `the coach names the job from the session`() {
        val raw = """
            TITLE|Replacing laptop RAM
            1|EXPERT|Power down|Shut the laptop down.|
        """.trimIndent()
        assertEquals("Replacing laptop RAM", parseTitle(raw))
        // And the title line must not be mistaken for a step.
        assertEquals("Power down", parseRewrite(raw, 1)[0]?.title)
    }

    @Test
    fun `a decorated title line still yields the name`() {
        assertEquals("Replacing laptop RAM", parseTitle("**TITLE**| \"Replacing laptop RAM\" "))
        assertEquals("Replacing laptop RAM", parseTitle("Title | Replacing laptop RAM"))
    }

    @Test
    fun `a model that will not name the job leaves it unnamed`() {
        // Better an honest "New job" than a name invented from a garbled take.
        assertEquals("", parseTitle("TITLE|Untitled job"))
        assertEquals("", parseTitle("TITLE|I cannot tell what this job is"))
        assertEquals("", parseTitle("1|EXPERT|A|do it|"))
        assertEquals("", parseTitle(""))
    }

    @Test
    fun `a title that is really a paragraph is rejected`() {
        val essay = "TITLE|" + "a very long description of the job ".repeat(4)
        assertEquals("", parseTitle(essay))
    }

    // --- the extra columns -----------------------------------------------

    @Test
    fun `a note keeps its sentence, and a stray column pipe becomes a space`() {
        // The pipe used to be preserved. The device settled it: a pipe in the
        // note column is a sixth field the model was not asked for, not
        // punctuation someone meant, and it reached the learner as "mind the
        // clips|". The sentence matters; the pipe never did.
        val out = parseRewrite(
            "1|EXPERT|Pry the clips|Lift the left clip.|unclear whether | both clips come off",
            1,
        )
        assertEquals("Lift the left clip.", out[0]?.instruction)
        assertEquals("unclear whether both clips come off", out[0]?.note)
    }

    @Test
    fun `a missing note column is not a parse failure`() {
        // A model with no doubt to report simply stops after the instruction.
        val out = parseRewrite("1|EXPERT|Power down|Shut it down.", 1)
        assertEquals("Shut it down.", out[0]?.instruction)
        assertEquals("", out[0]?.note)
    }

    @Test
    fun `a model writing none in the note column means no note`() {
        assertEquals("", parseRewrite("1|EXPERT|A|do it|none", 1)[0]?.note)
        assertEquals("", parseRewrite("1|EXPERT|A|do it|-", 1)[0]?.note)
    }

    @Test
    fun `an unrecognised SOURCE token does not lose the line`() {
        // The instruction is worth more than the label. An unreadable source
        // falls to UNKNOWN and grounding then decides from the evidence.
        val out = parseRewrite("1|probably expert?|Power down|Shut it down.|", 1)
        assertEquals("Shut it down.", out[0]?.instruction)
        assertEquals(Provenance.UNKNOWN, out[0]?.source)
    }

    // --- SKIP ------------------------------------------------------------

    @Test
    fun `SKIP marks the step an aside instead of writing it into the guide`() {
        // The expert's phone ringing, a false start, an instruction taken back.
        val out = parseRewrite("2|SKIP|||the expert answered their phone here", 3)
        assertTrue(out[1]!!.aside)
        assertEquals("", out[1]?.instruction)
        assertNull(out[0])
    }

    @Test
    fun `a SKIP line survives even though its title and instruction are empty`() {
        // An empty ordinary line is dropped; an empty SKIP line is the verdict.
        assertNull(parseRewrite("1|EXPERT|||", 1)[0])
        assertTrue(parseRewrite("1|SKIP|||", 1)[0]!!.aside)
    }

    @Test
    fun `an ordinary step is not marked aside`() {
        assertFalse(parseRewrite("1|EXPERT|A|do it|", 1)[0]!!.aside)
    }

    // --- grounding: a claimed source is capped by the evidence ------------

    @Test
    fun `EXPERT is honoured when the expert actually spoke`() {
        assertEquals(
            Provenance.EXPERT,
            groundedSource(Provenance.EXPERT, step(transcript = "इसको निकालो"), "Lift it out."),
        )
    }

    @Test
    fun `EXPERT over a silent step is demoted to what the evidence supports`() {
        // The failure this exists to stop: a guide attributing invented words
        // to a real person because a 2B model filled a column.
        assertEquals(
            Provenance.VISUAL,
            groundedSource(Provenance.EXPERT, step(caption = "laptop, screwdriver"), "Undo it."),
        )
        assertEquals(
            Provenance.GENERAL,
            groundedSource(Provenance.EXPERT, step(), "Use a PH0 driver."),
        )
    }

    @Test
    fun `VISUAL over a step with nothing seen is demoted to GENERAL`() {
        assertEquals(
            Provenance.GENERAL,
            groundedSource(Provenance.VISUAL, step(), "Use a PH0 driver."),
        )
    }

    @Test
    fun `a humbler claim than the evidence is honoured, not raised`() {
        // The model saying "I worked from the photo, not the words" over a step
        // that has both is a claim about its own reasoning, and it knows.
        assertEquals(
            Provenance.VISUAL,
            groundedSource(Provenance.VISUAL, step(transcript = "words", caption = "laptop"), "Do it."),
        )
        assertEquals(
            Provenance.GENERAL,
            groundedSource(Provenance.GENERAL, step(transcript = "words"), "Do it."),
        )
    }

    @Test
    fun `a model that admits UNKNOWN is believed`() {
        assertEquals(
            Provenance.UNKNOWN,
            groundedSource(Provenance.UNKNOWN, step(transcript = "words"), "Do it."),
        )
    }

    @Test
    fun `a blank instruction is UNKNOWN however loud the claim`() {
        assertEquals(
            Provenance.UNKNOWN,
            groundedSource(Provenance.EXPERT, step(transcript = "words"), ""),
        )
    }

    // --- the prompt's clock ----------------------------------------------

    @Test
    fun `the clock is mm ss and never negative`() {
        assertEquals("0:00", clock(0))
        assertEquals("0:07", clock(7_400))
        assertEquals("1:05", clock(65_000))
        assertEquals("12:30", clock(750_000))
        assertEquals("0:00", clock(-1))
    }

    // --- a model saying it twice ------------------------------------------

    @Test
    fun `drops the sentence the model said again with a connective on it`() {
        val out = dropSentenceRepeats("सर्जिकल स्टेप पूरा हुआ। अब सर्जिकल स्टेप पूरा हुआ।")
        assertEquals("सर्जिकल स्टेप पूरा हुआ।", out)
    }

    @Test
    fun `drops a repeat that is not the sentence right before it`() {
        // The other habit: it finishes, adds a real second sentence, then puts
        // the first one back on the end.
        val out = dropSentenceRepeats("Remove the screws. Lift the panel. Remove the screws.")
        assertEquals("Remove the screws. Lift the panel.", out)
    }

    @Test
    fun `English keeps a full stop and not a danda`() {
        val out = dropSentenceRepeats("Remove the screws. Remove the screws.")
        assertEquals("Remove the screws.", out)
    }

    @Test
    fun `a repeated word inside two different sentences is not a repeat`() {
        val text = "Undo the screw. Keep the screw somewhere safe."
        assertEquals(text, dropSentenceRepeats(text))
    }

    @Test
    fun `one sentence is returned untouched`() {
        assertEquals("Lift the panel off", dropSentenceRepeats("Lift the panel off"))
    }
}
