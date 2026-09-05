package com.showhow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Defect #1: photos were named by live boundary count and read back by final
 * step index. Live boundaries outnumber final steps, so every photo after the
 * first merge landed on the wrong step.
 *
 * These tests build the same two lists the ViewModel builds -- a sample log
 * scanned live for snaps, then the authoritative cut over the whole log -- and
 * check the two are pinned back together by time.
 */
class SnapMapperTest {

    private val p = Policy.DEFAULT
    private val VOICE = -18.0
    private val ROOM = -70.0

    /** dBFS every 20 ms, from a script of (duration, level) pairs. */
    private fun track(vararg spans: Pair<Long, Double>): Pair<List<Sample>, Long> {
        val out = mutableListOf<Sample>()
        var t = 0L
        for ((dur, db) in spans) {
            repeat((dur / 20).toInt()) {
                out += Sample(t, db)
                t += 20
            }
        }
        return out to t
    }

    /**
     * What ShowHowViewModel.onLevel does while recording: one snap at the
     * start, then one every time speech resumes after a long enough pause.
     */
    private fun liveSnapTimes(samples: List<Sample>): List<Long> {
        val gate = AdaptiveGate(p)
        val snaps = mutableListOf(0L)
        var silenceStart = -1L
        var sawSpeech = false
        for (s in samples) {
            if (gate.update(s.dbfs)) {
                if (silenceStart >= 0L) {
                    if (s.tMs - silenceStart >= p.pauseMs && sawSpeech) snaps += s.tMs
                    silenceStart = -1L
                }
                sawSpeech = true
            } else if (silenceStart < 0L) {
                silenceStart = s.tMs
            }
        }
        return snaps
    }

    @Test
    fun `a merged away boundary does not drag every later photo off by one`() {
        // Four utterances. The second is under minUtteranceMs, so its boundary
        // is merged out of the final cut but was still snapped live.
        val (samples, dur) = track(
            200L to ROOM,
            3000L to VOICE, 1400L to ROOM,   // step 1
            600L to VOICE, 1400L to ROOM,    // too short: merges forward
            3000L to VOICE, 1400L to ROOM,   // step 2
            3000L to VOICE,                  // step 3
        )
        val snaps = liveSnapTimes(samples)
        val ranges = StepCutter(p).cut(samples, dur)

        assertTrue("expected more live snaps than steps", snaps.size > ranges.size)
        val picked = mapSnapsToSteps(snaps, ranges)

        assertEquals(ranges.size, picked.size)
        assertEquals("no step may reuse another step's photo", picked.size, picked.toSet().size)
        for (r in ranges) {
            val t = snaps[picked[r.index]]
            assertTrue(
                "step ${r.index} starts at ${r.startMs} but got a photo from $t",
                Math.abs(t - r.startMs) <= p.pauseMs,
            )
        }
    }

    @Test
    fun `the first step always gets the opening snap`() {
        val (samples, dur) = track(200L to ROOM, 3000L to VOICE, 1500L to ROOM, 3000L to VOICE)
        val ranges = StepCutter(p).cut(samples, dur)
        assertEquals(0, mapSnapsToSteps(liveSnapTimes(samples), ranges).first())
    }

    @Test
    fun `trailing snaps past the last step are dropped`() {
        val ranges = listOf(StepRange(0, 0, 5000), StepRange(1, 5000, 10_000))
        assertEquals(listOf(0, 1), mapSnapsToSteps(listOf(0L, 5000L, 12_000L, 18_000L), ranges))
    }

    @Test
    fun `too few photos spread across the steps instead of front loading`() {
        val ranges = (0 until 4).map { StepRange(it, it * 5000L, (it + 1) * 5000L) }
        // Two photos, four steps: they must not both land on steps 0 and 1.
        val picked = mapSnapsToSteps(listOf(0L, 14_000L), ranges)
        assertEquals(listOf(0, -1, -1, 1), picked)
    }

    @Test
    fun `no photos at all is a guide with no photos, not a crash`() {
        val ranges = listOf(StepRange(0, 0, 5000))
        assertEquals(listOf(-1), mapSnapsToSteps(emptyList(), ranges))
        assertEquals(emptyList<Int>(), mapSnapsToSteps(listOf(0L), emptyList()))
    }
}
