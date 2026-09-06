package com.showhow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The expert's own words as evidence of what is being shown.
 *
 * The case that matters: a tool no model on this phone can recognise, held up
 * and named. Before this the frame picker scored that moment the same as a
 * frame of an empty bench, and the guide showed the empty bench.
 */
class NamedNearTest {

    private val terms = setOf("screwdriver", "philips", "spudger", "battery")

    private fun said(vararg pairs: Pair<String, Long>) =
        pairs.map { (t, ms) -> SpokenWord(t, ms) }

    @Test
    fun `a word spoken over the frame counts`() {
        val spoken = said("this" to 4000, "philips" to 4500, "screwdriver" to 5000)
        assertEquals(2, namedNear(5000, spoken, terms, 2500))
    }

    @Test
    fun `a word spoken well before the frame does not`() {
        val spoken = said("screwdriver" to 1000)
        assertEquals(0, namedNear(9000, spoken, terms, 2500))
    }

    @Test
    fun `the window reaches both ways`() {
        val spoken = said("spudger" to 3000)
        assertEquals(1, namedNear(5000, spoken, terms, 2500))
        assertEquals(1, namedNear(1000, spoken, terms, 2500))
        assertEquals(0, namedNear(6000, spoken, terms, 2500))
    }

    @Test
    fun `punctuation and case do not hide a word`() {
        val spoken = said("Screwdriver," to 5000, "BATTERY." to 5200)
        assertEquals(2, namedNear(5000, spoken, terms, 2500))
    }

    @Test
    fun `nothing said and nothing to look for are both zero`() {
        assertEquals(0, namedNear(5000, emptyList(), terms, 2500))
        assertEquals(0, namedNear(5000, said("screwdriver" to 5000), emptySet(), 2500))
        assertEquals(0, namedNear(5000, said("screwdriver" to 5000), terms, 0))
    }

    @Test
    fun `the named frame beats a sharper one with nothing said over it`() {
        val range = StepRange(0, 0, 10_000)
        // The empty bench is the sharper photograph. The tool being held up and
        // named is the one the step is actually about.
        val bench = FrameStats(0, 2_000, sharpness = 0.90, meanLuma = 120.0, dHash = 1L, detections = 1)
        val tool = FrameStats(1, 4_000, sharpness = 0.62, meanLuma = 120.0, dHash = 999L, detections = 0, namedThings = 2)

        val picked = pickFrames(listOf(bench, tool), listOf(range), Policy.DEFAULT)
        assertEquals(listOf(1), picked)
    }

    @Test
    fun `with nothing named the old ranking is untouched`() {
        val range = StepRange(0, 0, 10_000)
        val dull = FrameStats(0, 2_000, sharpness = 0.30, meanLuma = 120.0, dHash = 1L, detections = 0)
        val sharp = FrameStats(1, 4_000, sharpness = 0.90, meanLuma = 120.0, dHash = 999L, detections = 0)

        assertEquals(listOf(1), pickFrames(listOf(dull, sharp), listOf(range), Policy.DEFAULT))
        assertTrue(dull.namedThings == 0 && sharp.namedThings == 0)
    }
}
