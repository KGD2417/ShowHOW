package com.showhow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val N = 32

private fun image(f: (Int, Int) -> Int): IntArray =
    IntArray(N * N) { i -> f(i % N, i / N) }

private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

class SceneHashTest {

    /** Advisory, so what matters is that same-ish scores high and other scores low. */
    @Test
    fun `same scene scores high and a different one scores low`() {
        val kitchen = image { x, y -> rgb((x * 7) % 256, (y * 5) % 256, 40) }
        // Same structure, slightly dimmer -- a different bulb, not a different room.
        val dimmer = image { x, y -> rgb((x * 7) % 256 * 9 / 10, (y * 5) % 256 * 9 / 10, 36) }
        val other = image { x, y -> rgb(200, (x + y) % 256, (x * 11) % 256) }

        fun sim(a: IntArray, b: IntArray) = SceneHash.similarity(
            SceneHash.dHash(a, N, N), SceneHash.dHash(b, N, N),
            SceneHash.hsvHistogram(a, N, N), SceneHash.hsvHistogram(b, N, N),
        )

        assertEquals(1.0f, sim(kitchen, kitchen), 1e-6f)
        assertTrue("dimmer same scene scored ${sim(kitchen, dimmer)}", sim(kitchen, dimmer) > 0.8f)
        assertTrue("different scene scored ${sim(kitchen, other)}", sim(kitchen, other) < 0.8f)
    }

    /** It has to survive whatever the camera hands it. It never blocks anything. */
    @Test
    fun `degenerate input does not crash`() {
        assertEquals(0L, SceneHash.dHash(IntArray(0), 0, 0))
        assertEquals(0.0, SceneHash.hsvHistogram(IntArray(0), 0, 0).sum(), 1e-9)
        val flat = IntArray(N * N) { rgb(0, 0, 0) }
        assertTrue(SceneHash.similarity(0L, 0L, SceneHash.hsvHistogram(flat, N, N), DoubleArray(36)) >= 0f)
    }
}
