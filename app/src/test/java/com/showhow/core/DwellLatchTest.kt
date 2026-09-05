package com.showhow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DwellLatchTest {

    private val latch = DwellLatch<String>(dwellMs = 300)

    @Test
    fun `a pose held long enough fires exactly once`() {
        assertNull(latch.update(0, "PALM"))
        assertNull(latch.update(200, "PALM"))
        assertEquals("PALM", latch.update(300, "PALM"))
        // Still holding: the step must not run away from the user.
        assertNull(latch.update(400, "PALM"))
        assertNull(latch.update(2000, "PALM"))
    }

    @Test
    fun `a flickering pose never fires`() {
        var t = 0L
        repeat(20) {
            assertNull(latch.update(t, if (it % 2 == 0) "PALM" else "FIST"))
            t += 100
        }
    }

    @Test
    fun `the hand has to leave before the same pose counts again`() {
        assertNull(latch.update(0, "PALM"))
        assertEquals("PALM", latch.update(300, "PALM"))
        assertNull(latch.update(400, null))
        // The dwell restarts from when the hand came back, not from zero.
        assertNull(latch.update(500, "PALM"))
        assertEquals("PALM", latch.update(800, "PALM"))
    }

    @Test
    fun `a different pose in between is its own action`() {
        assertNull(latch.update(0, "PALM"))
        assertEquals("PALM", latch.update(300, "PALM"))
        assertNull(latch.update(400, "FIST"))
        assertEquals("FIST", latch.update(700, "FIST"))
        assertNull(latch.update(800, "PALM"))
        assertEquals("PALM", latch.update(1100, "PALM"))
    }

    @Test
    fun `nothing in front of the camera fires nothing`() {
        assertNull(latch.update(0, null))
        assertNull(latch.update(5000, null))
    }
}
