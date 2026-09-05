package com.showhow.capture

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The level meter and the gate both live or die on [AudioRecorder.dbfs], and
 * it is the one part of the recorder that needs no microphone to test.
 */
class AudioRecorderTest {

    private fun sine(n: Int, amplitude: Double, hz: Double = 440.0): ShortArray =
        ShortArray(n) { i ->
            (amplitude * 32767.0 * sin(2 * PI * hz * i / AudioRecorder.SAMPLE_RATE)).toInt().toShort()
        }

    @Test
    fun `full scale sine sits just under zero dBFS`() {
        // RMS of a full-scale sine is 1/sqrt(2), i.e. -3.01 dBFS.
        val db = AudioRecorder.dbfs(sine(1600, 1.0), 1600)
        assertEquals(-3.01, db.toDouble(), 0.15)
    }

    @Test
    fun `halving the amplitude costs six dB`() {
        val loud = AudioRecorder.dbfs(sine(1600, 1.0), 1600)
        val quiet = AudioRecorder.dbfs(sine(1600, 0.5), 1600)
        assertEquals(-6.02, (quiet - loud).toDouble(), 0.15)
    }

    @Test
    fun `a dead mic reports negative infinity, not NaN`() {
        val db = AudioRecorder.dbfs(ShortArray(320), 320)
        assertTrue("expected -Inf, got $db", db == Float.NEGATIVE_INFINITY)
        // AdaptiveGate is the thing that has to survive it.
        assertEquals(-120.0, com.showhow.core.AdaptiveGate.sanitize(db.toDouble()), 0.0)
    }

    @Test
    fun `only the first n samples count`() {
        // The read loop hands over a full-size buffer with a short read in it.
        val buf = sine(320, 1.0)
        val padded = buf.copyOf(640)
        assertEquals(
            AudioRecorder.dbfs(buf, 320).toDouble(),
            AudioRecorder.dbfs(padded, 320).toDouble(),
            1e-6,
        )
    }

    @Test
    fun `wav header declares the sizes a player reads`() {
        val dataBytes = 16_000L * 2 * 3          // three seconds of PCM16 mono
        val h = AudioRecorder.wavHeader(dataBytes)

        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4))
        assertEquals("WAVE", String(h, 8, 4))
        assertEquals("data", String(h, 36, 4))
        assertEquals(36 + dataBytes, le32(h, 4))          // RIFF chunk size
        assertEquals(dataBytes, le32(h, 40))              // data chunk size
        assertEquals(1L, le16(h, 20))                     // PCM, uncompressed
        assertEquals(1L, le16(h, 22))                     // mono
        assertEquals(16_000L, le32(h, 24))                // what Vosk expects
        assertEquals(32_000L, le32(h, 28))                // byte rate
        assertEquals(2L, le16(h, 32))                     // block align
        assertEquals(16L, le16(h, 34))                    // bits per sample
    }

    private fun le32(b: ByteArray, off: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((b[off + i].toLong() and 0xFF) shl (8 * i)) }

    private fun le16(b: ByteArray, off: Int): Long =
        (0 until 2).fold(0L) { acc, i -> acc or ((b[off + i].toLong() and 0xFF) shl (8 * i)) }
}
