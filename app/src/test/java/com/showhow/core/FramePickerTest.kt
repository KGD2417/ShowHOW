package com.showhow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which photograph represents a step.
 *
 * Every frame here is invented, which is the point: the Android side measures
 * pixels and this side does arithmetic, so the whole decision can be driven
 * with numbers that never came off a camera and will give the same answer on
 * every run.
 *
 * The failure that matters is not picking a slightly worse frame. It is
 * captioning a blurred smear "aiming for", because a learner will stop and try
 * to match it.
 */
class FramePickerTest {

    private val p = Policy.DEFAULT

    /** A perfectly ordinary usable frame, varied per test. */
    private fun frame(
        i: Int,
        tMs: Long,
        sharpness: Double = 0.40,
        luma: Double = 120.0,
        hash: Long = 0L,
        detections: Int = 2,
    ) = FrameStats(i, tMs, sharpness, luma, hash, detections)

    private fun ranges(vararg bounds: Long): List<StepRange> =
        bounds.toList().zipWithNext().mapIndexed { i, (a, b) -> StepRange(i, a, b) }

    /** Hashes far enough apart that nothing is ever rejected as a duplicate. */
    private fun distinct(n: Int): Long = when (n % 4) {
        0 -> 0x0000000000000000L
        1 -> 0x00000000FFFFFFFFL
        2 -> 0xFFFFFFFF00000000uL.toLong()
        else -> -1L
    }

    // --- the range ---------------------------------------------------------

    @Test
    fun `a step only ever takes a frame from inside its own time range`() {
        val frames = listOf(frame(0, 500, hash = distinct(0)), frame(1, 6_000, hash = distinct(1)))
        val out = pickFrames(frames, ranges(0, 4_000, 8_000), p)
        assertEquals(0, out[0])
        assertEquals(1, out[1])
    }

    @Test
    fun `a step with no frames in its range gets null rather than a neighbour's`() {
        val out = pickFrames(listOf(frame(0, 500)), ranges(0, 4_000, 8_000), p)
        assertEquals(0, out[0])
        assertNull(out[1])
    }

    @Test
    fun `the range is half open, so a frame on the boundary belongs to the later step`() {
        val out = pickFrames(listOf(frame(0, 4_000)), ranges(0, 4_000, 8_000), p)
        assertNull(out[0])
        assertEquals(0, out[1])
    }

    @Test
    fun `no frames at all is every step null, not a crash`() {
        assertEquals(listOf(null, null), pickFrames(emptyList(), ranges(0, 4_000, 8_000), p))
    }

    // --- rejection ---------------------------------------------------------

    @Test
    fun `a blurred frame loses to a sharp one`() {
        val frames = listOf(
            frame(0, 1_000, sharpness = 0.01, hash = distinct(0)),
            frame(1, 2_000, sharpness = 0.40, hash = distinct(0)),
        )
        assertEquals(1, pickFrames(frames, ranges(0, 4_000), p)[0])
    }

    @Test
    fun `a step whose every frame is blurred keeps no photo`() {
        // The whole reason this returns null. A smear captioned "aiming for" is
        // worse than no picture: the learner will try to match it.
        val frames = listOf(frame(0, 1_000, sharpness = 0.01), frame(1, 2_000, sharpness = 0.02))
        assertNull(pickFrames(frames, ranges(0, 4_000), p)[0])
    }

    @Test
    fun `a frame of nothing is rejected at either end of the brightness range`() {
        // A lens face down on the bench, and a frame straight into a worklight.
        // Neither is blurry, so sharpness alone would let both through.
        assertNull(pickFrames(listOf(frame(0, 1_000, luma = 4.0)), ranges(0, 4_000), p)[0])
        assertNull(pickFrames(listOf(frame(0, 1_000, luma = 250.0)), ranges(0, 4_000), p)[0])
    }

    @Test
    fun `a frame that repeats the previous step's picture is rejected`() {
        val same = 0x0F0F0F0F0F0F0F0FL
        val frames = listOf(
            frame(0, 1_000, hash = same),
            frame(1, 5_000, hash = same),                       // identical
            frame(2, 6_000, hash = same.inv(), sharpness = 0.30), // clearly different
        )
        val out = pickFrames(frames, ranges(0, 4_000, 8_000), p)
        assertEquals(0, out[0])
        assertEquals("the duplicate must lose to the frame that differs", 2, out[1])
    }

    @Test
    fun `a step whose only frames duplicate the previous step keeps no photo`() {
        val same = 0x0F0F0F0F0F0F0F0FL
        val frames = listOf(frame(0, 1_000, hash = same), frame(1, 5_000, hash = same))
        val out = pickFrames(frames, ranges(0, 4_000, 8_000), p)
        assertEquals(0, out[0])
        assertNull(out[1])
    }

    @Test
    fun `the first step has nothing to duplicate, so it is never rejected for it`() {
        assertEquals(0, pickFrames(listOf(frame(0, 1_000, hash = 0L)), ranges(0, 4_000), p)[0])
    }

    // --- scoring -----------------------------------------------------------

    @Test
    fun `between two equal frames the later one wins, because it shows the result`() {
        // The reason this is not just mapSnapsToSteps, which takes the frame
        // nearest the start -- a picture of the work not yet begun.
        val frames = listOf(frame(0, 200, hash = distinct(0)), frame(1, 3_800, hash = distinct(0)))
        assertEquals(1, pickFrames(frames, ranges(0, 4_000), p)[0])
    }

    @Test
    fun `a much sharper early frame beats a marginal late one`() {
        // Lateness is a preference, not an override.
        val frames = listOf(
            frame(0, 200, sharpness = 0.90, hash = distinct(0)),
            frame(1, 3_800, sharpness = 0.06, hash = distinct(0)),
        )
        assertEquals(0, pickFrames(frames, ranges(0, 4_000), p)[0])
    }

    @Test
    fun `detector evidence breaks a tie between otherwise identical frames`() {
        val frames = listOf(
            frame(0, 2_000, detections = 0, hash = distinct(0)),
            frame(1, 2_000, detections = 3, hash = distinct(0)),
        )
        assertEquals(1, pickFrames(frames, ranges(0, 4_000), p)[0])
    }

    @Test
    fun `detector evidence cannot rescue a blurred frame`() {
        // Boxes say something recognisable was in shot. They do not say the
        // photograph is worth looking at.
        val frames = listOf(frame(0, 2_000, sharpness = 0.01, detections = 8))
        assertNull(pickFrames(frames, ranges(0, 4_000), p)[0])
    }

    // --- determinism -------------------------------------------------------

    @Test
    fun `the same frames in any order give the same choice`() {
        val frames = listOf(
            frame(0, 1_000, sharpness = 0.30, hash = distinct(0)),
            frame(1, 2_000, sharpness = 0.30, hash = distinct(0)),
            frame(2, 3_000, sharpness = 0.30, hash = distinct(0)),
        )
        val forwards = pickFrames(frames, ranges(0, 4_000), p)
        val backwards = pickFrames(frames.reversed(), ranges(0, 4_000), p)
        assertEquals(forwards, backwards)
        // And repeated runs agree with themselves.
        assertEquals(forwards, pickFrames(frames, ranges(0, 4_000), p))
    }

    @Test
    fun `every step gets at most one frame and no two steps share one`() {
        val frames = (0 until 8).map {
            frame(it, it * 1_000L + 500, sharpness = 0.20 + it * 0.05, hash = distinct(it))
        }
        val out = pickFrames(frames, ranges(0, 2_000, 4_000, 6_000, 8_000), p)
        assertEquals(4, out.size)
        val chosen = out.filterNotNull()
        assertEquals("no photo may be used twice", chosen.size, chosen.distinct().size)
    }

    // --- the knobs are the knobs ------------------------------------------

    @Test
    fun `raising the sharpness floor in policy removes frames, without a rebuild`() {
        val frames = listOf(frame(0, 1_000, sharpness = 0.20))
        assertEquals(0, pickFrames(frames, ranges(0, 4_000), p)[0])
        assertNull(pickFrames(frames, ranges(0, 4_000), p.copy(frameMinSharpness = 0.5))[0])
    }

    @Test
    fun `turning the lateness weight up changes which frame wins`() {
        // Sharp-and-early beats soft-and-late by default...
        val frames = listOf(
            frame(0, 200, sharpness = 0.90, hash = distinct(0)),
            frame(1, 3_800, sharpness = 0.30, hash = distinct(0)),
        )
        assertEquals(0, pickFrames(frames, ranges(0, 4_000), p)[0])
        // ...and stops doing so once someone filming under a car turns the
        // lateness weight up, with no rebuild.
        assertNotEquals(
            0,
            pickFrames(frames, ranges(0, 4_000), p.copy(frameLatenessWeight = 2.0))[0],
        )
    }
}
