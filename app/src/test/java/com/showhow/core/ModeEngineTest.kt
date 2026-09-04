package com.showhow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Held in a hand, quiet room, user close: the TAP baseline. */
private val TAP_INPUTS = ModeInputs(accelVariance = 0.20, dbfs = -50.0)

/** Drives the engine long enough to clear the 400 ms dwell. */
private fun ModeEngine.settle(inputs: ModeInputs, fromMs: Long = 0L): Long {
    var t = fromMs
    repeat(60) {
        update(t, inputs)
        t += 20
    }
    return t
}

class ModeEngineTest {

    /** Test 7: a value oscillating between enter and exit must not flip state. */
    @Test
    fun `schmitt hysteresis does not flicker on a border value`() {
        val e = ModeEngine()
        var t = e.settle(TAP_INPUTS)
        assertEquals(Mode.TAP, e.mode)
        assertTrue(e.isInHand)

        // 0.07 sits between the 0.09 enter and the 0.05 exit. Held is held.
        repeat(200) {
            e.update(t, TAP_INPUTS.copy(accelVariance = 0.07))
            t += 20
        }
        assertTrue("in-hand dropped on a border value", e.isInHand)
        assertEquals(Mode.TAP, e.mode)

        // Same for the loudness trigger: -29 dBFS is between -26 and -32.
        val loud = ModeEngine()
        t = loud.settle(TAP_INPUTS.copy(dbfs = -20.0))
        assertEquals(Mode.HANDS, loud.mode)
        repeat(200) {
            loud.update(t, TAP_INPUTS.copy(dbfs = -29.0))
            t += 20
        }
        assertTrue("room-loud dropped on a border value", loud.isRoomLoud)
        assertEquals(Mode.HANDS, loud.mode)
    }

    /**
     * Test 8: the dwell.
     *
     * A candidate that lasts less than 400 ms never commits. Without this the
     * screen repaints on every border sample, and that flicker is exactly what
     * the product exists to fix.
     */
    @Test
    fun `a candidate shorter than the dwell never commits`() {
        val e = ModeEngine()
        var t = e.settle(TAP_INPUTS)
        assertEquals(Mode.TAP, e.mode)

        // 300 ms of loud room -- 100 ms short of the dwell.
        val loud = TAP_INPUTS.copy(dbfs = -18.0)
        repeat(15) {
            assertFalse("committed inside the dwell", e.update(t, loud))
            t += 20
        }
        assertEquals(Mode.TAP, e.mode)

        // Back to quiet: the pending candidate is dropped, not banked.
        repeat(5) {
            e.update(t, TAP_INPUTS)
            t += 20
        }
        assertEquals(Mode.TAP, e.mode)

        // Hold it past 400 ms and it does commit.
        e.settle(loud, t)
        assertEquals(Mode.HANDS, e.mode)
        assertTrue(e.reason.startsWith("HANDS <- room is loud"))
    }

    /** Test 9: EASY beats HANDS beats TALK beats TAP. */
    @Test
    fun `decision table precedence`() {
        // Everything true at once: EASY wins.
        val all = ModeInputs(
            easyMode = true,
            accelVariance = 0.0,     // flat -> would be TALK
            dbfs = -10.0,            // loud -> would be HANDS
            speechUnclear = true,
            userFar = true,
        )
        assertEquals(Mode.EASY, ModeEngine().apply { settle(all) }.mode)

        // Drop EASY: HANDS wins over TALK.
        assertEquals(
            Mode.HANDS,
            ModeEngine().apply { settle(all.copy(easyMode = false)) }.mode,
        )

        // Drop the loud room but keep unclear speech: still HANDS.
        assertEquals(
            Mode.HANDS,
            ModeEngine().apply { settle(all.copy(easyMode = false, dbfs = -50.0)) }.mode,
        )

        // Quiet and clear, but the phone is flat on the counter: TALK.
        assertEquals(
            Mode.TALK,
            ModeEngine().apply {
                settle(all.copy(easyMode = false, dbfs = -50.0, speechUnclear = false))
            }.mode,
        )

        // Quiet, clear, held, close: TAP -- and reached from TALK, not just
        // left sitting on the start value.
        val e = ModeEngine()
        val t = e.settle(all.copy(easyMode = false, dbfs = -50.0, speechUnclear = false))
        assertEquals(Mode.TALK, e.mode)
        e.settle(TAP_INPUTS, t)
        assertEquals(Mode.TAP, e.mode)
    }

    /** Every committed switch leaves a sentence a human can read at 3am. */
    @Test
    fun `every switch carries a readable reason`() {
        val e = ModeEngine()
        e.settle(TAP_INPUTS.copy(dbfs = -18.0))
        assertEquals("HANDS <- room is loud (-18.0 dBFS)", e.reason)

        e.settle(TAP_INPUTS.copy(easyMode = true), 5000)
        assertEquals("EASY <- user setting", e.reason)
    }

    /** The engine is rules only. It has to be fast enough to run per frame. */
    @Test
    fun `decides well inside ten milliseconds`() {
        val e = ModeEngine()
        val start = System.nanoTime()
        repeat(1000) { e.update(it * 20L, TAP_INPUTS) }
        val perCallMs = (System.nanoTime() - start) / 1_000_000.0 / 1000.0
        assertTrue("mode decision took $perCallMs ms", perCallMs < 10.0)
    }
}
