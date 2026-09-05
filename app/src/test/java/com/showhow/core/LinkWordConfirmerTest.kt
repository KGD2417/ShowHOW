package com.showhow.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The second opinion on a cut. The cases that matter are the vetoes it must
 * not cast: no recogniser, no word list, knob at zero.
 */
class LinkWordConfirmerTest {

    private val hindi = Policy.DEFAULT.linkWordsHi   // phir, ab, uske baad, next, then

    private fun words(vararg pairs: Pair<String, Long>) =
        pairs.map { (t, ms) -> SpokenWord(t, ms) }

    @Test
    fun `a cut with a linking word beside it survives`() {
        val c = LinkWordConfirmer(hindi, words("kholo" to 4000, "phir" to 5200, "filter" to 5600))
        assertEquals(listOf(5000L), c.confirm(listOf(5000L)))
    }

    @Test
    fun `a cut with nothing but ordinary words beside it is vetoed`() {
        // The expert reached for a spanner mid-sentence: a real pause, not a step.
        val c = LinkWordConfirmer(hindi, words("dhakkan" to 4600, "kholo" to 5400))
        assertEquals(emptyList<Long>(), c.confirm(listOf(5000L)))
    }

    @Test
    fun `a linking word outside the window does not count`() {
        val c = LinkWordConfirmer(hindi, words("phir" to 30_000), windowMs = 2500)
        assertEquals(emptyList<Long>(), c.confirm(listOf(5000L)))
    }

    @Test
    fun `a two word link phrase is matched across two tokens`() {
        // policy.json ships "uske baad" as one entry; Vosk emits two tokens.
        val c = LinkWordConfirmer(hindi, words("uske" to 5100, "baad" to 5400))
        assertEquals(listOf(5000L), c.confirm(listOf(5000L)))
    }

    @Test
    fun `minLinkWords of two needs two of them`() {
        val one = LinkWordConfirmer(hindi, words("phir" to 5100), minLinkWords = 2)
        assertEquals(emptyList<Long>(), one.confirm(listOf(5000L)))

        val two = LinkWordConfirmer(hindi, words("ab" to 4900, "phir" to 5100), minLinkWords = 2)
        assertEquals(listOf(5000L), two.confirm(listOf(5000L)))
    }

    @Test
    fun `with no words at all every cut passes through`() {
        // ASR off or model missing. Vetoing everything here would hand back one
        // enormous step, which is the failure the cutter exists to prevent.
        val c = LinkWordConfirmer(hindi, emptyList())
        assertEquals(listOf(3000L, 9000L), c.confirm(listOf(3000L, 9000L)))
    }

    @Test
    fun `an empty word list or a zeroed knob disables the veto`() {
        val noList = LinkWordConfirmer(emptyList(), words("dhakkan" to 5000))
        assertEquals(listOf(5000L), noList.confirm(listOf(5000L)))

        val off = LinkWordConfirmer(hindi, words("dhakkan" to 5000), minLinkWords = 0)
        assertEquals(listOf(5000L), off.confirm(listOf(5000L)))
    }

    @Test
    fun `it only ever removes cuts, and keeps the cutter's ranges tiling`() {
        val samples = (0L until 20_000L step 20).map { t ->
            // Speech everywhere except two long pauses at 5 s and 12 s.
            val quiet = t in 5_000..6_500 || t in 12_000..13_500
            Sample(t, if (quiet) -70.0 else -18.0)
        }
        // Only the second pause is followed by a linking word.
        val confirmer = LinkWordConfirmer(hindi, words("phir" to 13_000))
        val ranges = StepCutter(Policy.DEFAULT, confirmer).cut(samples, 20_000)

        assertEquals(2, ranges.size)
        assertEquals(0L, ranges.first().startMs)
        assertEquals(20_000L, ranges.last().endMs)
        assertEquals(ranges[0].endMs, ranges[1].startMs)
    }
}
