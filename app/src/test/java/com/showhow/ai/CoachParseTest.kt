package com.showhow.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The parser is the only part of the coach that can be wrong without a phone.
 *
 * Everything else is a model call, and a model call cannot be tested here. What
 * can be tested is the thing between a 2B model's habits and the guide the
 * learner reads -- and a 2B model has habits. Every case below is a shape one
 * actually produced during the build.
 */
class CoachParseTest {

    @Test
    fun `clean output lands in order`() {
        val out = parseRewrite(
            """
            1|Power down|Shut the laptop down and unplug the charger.
            2|Remove the base|Undo the ten screws around the edge of the base.
            """.trimIndent(),
            2,
        )
        assertEquals("Power down", out[0]?.title)
        assertEquals("Undo the ten screws around the edge of the base.", out[1]?.instruction)
    }

    @Test
    fun `preamble and markdown around the lines are ignored`() {
        // Gemma will not stop saying "Here is the guide:" and will not stop
        // bolding things, however firmly the prompt asks.
        val out = parseRewrite(
            """
            Sure! Here is the rewritten guide:

            **1.** | Power down | Shut it down first.
            - 2 | Open the base | Undo the screws.

            Let me know if you need anything else.
            """.trimIndent(),
            2,
        )
        assertEquals("Power down", out[0]?.title)
        assertEquals("Open the base", out[1]?.title)
    }

    @Test
    fun `a skipped step leaves its slot empty rather than shifting the rest`() {
        // The whole reason placement is by number. Shifting here would put step
        // four's instruction over step three's photo, which reads as the app
        // being broken rather than the model being lazy.
        val out = parseRewrite("1|A|first\n3|C|third", 3)
        assertEquals("A", out[0]?.title)
        assertNull(out[1])
        assertEquals("C", out[2]?.title)
    }

    @Test
    fun `a step number past the end is dropped, not clamped`() {
        val out = parseRewrite("1|A|first\n9|Z|invented", 2)
        assertEquals(1, out.count { it != null })
        assertEquals("A", out[0]?.title)
    }

    @Test
    fun `a pipe inside the instruction stays in the instruction`() {
        val out = parseRewrite("1|Pry the clips|Lift the left clip | then the right.", 1)
        assertEquals("Lift the left clip | then the right.", out[0]?.instruction)
    }

    @Test
    fun `the first line for a step wins, so a repeat cannot overwrite it`() {
        val out = parseRewrite("1|Good|the real one\n1|Bad|a second attempt", 1)
        assertEquals("Good", out[0]?.title)
    }

    @Test
    fun `nothing usable gives all nulls rather than blank steps`() {
        // An empty answer is what a missing model returns. Every slot null
        // means the caller keeps the expert's own words, which is the point.
        assertEquals(listOf(null, null), parseRewrite("", 2))
        assertEquals(listOf(null, null), parseRewrite("I could not do that.", 2))
    }
}
