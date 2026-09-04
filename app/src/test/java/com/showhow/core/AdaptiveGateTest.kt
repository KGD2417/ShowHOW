package com.showhow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 20 ms per sample, same hop AudioRecorder uses. */
private const val HOP_MS = 20L

private fun run(gate: AdaptiveGate, db: Double, ms: Long): List<Boolean> =
    (0 until (ms / HOP_MS)).map { gate.update(db) }

class AdaptiveGateTest {

    /**
     * Test 1: the fan-noise room.
     *
     * Ceiling fan floors the room at -20 dBFS. A threshold nailed at -38 dB
     * sees the fan itself as speech and never finds a pause, so the whole take
     * comes back as one step. The adaptive gate has to find the pause anyway.
     */
    @Test
    fun `adapts to a fan floored room where a fixed threshold cannot`() {
        val gate = AdaptiveGate()
        val fan = -20.0
        val speech = -8.0

        run(gate, fan, 1000)                        // settle on the room
        val duringSpeech = run(gate, speech, 2000)
        val duringPause = run(gate, fan, 2000)

        assertTrue("speech must clear the gate", duringSpeech.all { it })
        assertTrue("fan-only must read as a pause", duringPause.none { it })

        // The control: -38 dB fixed would call the fan speech, forever.
        assertTrue("fixed -38 dB would never see this pause", fan > -38.0)
    }

    /** Test 2: extreme inputs never move the gate outside its clamp. */
    @Test
    fun `gate stays inside the clamp for any input`() {
        val p = Policy.DEFAULT
        for (db in listOf(50.0, 0.0, -3.0, -20.0, -80.0, -500.0, Double.NEGATIVE_INFINITY)) {
            val gate = AdaptiveGate()
            repeat(500) { gate.update(db) }
            assertTrue(
                "gate ${gate.gateDb} out of clamp for input $db",
                gate.gateDb >= p.gateMinDb && gate.gateDb <= p.gateMaxDb,
            )
        }
    }

    /** Test 3: a silent mic reports -Infinity. It must not crash or go NaN. */
    @Test
    fun `silent mic does not crash or produce NaN`() {
        val gate = AdaptiveGate()
        repeat(200) { assertFalse(gate.update(Double.NEGATIVE_INFINITY)) }
        assertFalse("floor went NaN", gate.floorDb.isNaN())
        assertFalse("gate went NaN", gate.gateDb.isNaN())
        assertFalse("level went NaN", gate.levelDb.isNaN())
        assertTrue(gate.floorDb.isFinite())
        assertEquals(Policy.DEFAULT.gateMinDb, gate.gateDb, 1e-9)

        // NaN from a divide-by-zero in the level meter is the same story.
        gate.update(Double.NaN)
        assertFalse(gate.gateDb.isNaN())
    }

    /**
     * Test 4: rise asymmetry.
     *
     * The floor falls fast and rises slowly, so five seconds of continuous
     * talking cannot drag the noise floor up behind the sentence and leave the
     * following pause undetectable.
     */
    @Test
    fun `a long utterance does not drag the floor up`() {
        val gate = AdaptiveGate()
        run(gate, -20.0, 1000)
        val floorBefore = gate.floorDb

        run(gate, -8.0, 5000)                       // five solid seconds of speech
        val drift = gate.floorDb - floorBefore

        assertTrue("floor drifted $drift dB during one sentence", drift < 1.0)
        assertTrue("pause after the sentence must still register", run(gate, -20.0, 500).none { it })
    }
}
