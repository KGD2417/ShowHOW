package com.showhow.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOP_MS = 20L
private const val ROOM = -20.0      // ceiling fan
private const val VOICE = -8.0

/** Builds a level track from alternating (durationMs, dBFS) spans. */
private fun track(vararg spans: Pair<Long, Double>): Pair<List<Sample>, Long> {
    val out = mutableListOf<Sample>()
    var t = 0L
    for ((durMs, db) in spans) {
        val end = t + durMs
        while (t < end) {
            out += Sample(t, db)
            t += HOP_MS
        }
    }
    return out to t
}

class StepCutterTest {

    /** Test 5: an utterance under 2500 ms merges into the one after it. */
    @Test
    fun `a short utterance merges into the following step`() {
        val (short, dur) = track(
            400L to ROOM,       // lead-in, lets the gate find the room
            1000L to VOICE,     // 1.0 s -- too short to stand alone
            1500L to ROOM,      // a real pause
            5000L to VOICE,
        )
        assertEquals(1, StepCutter().cut(short, dur).size)

        // Same shape, but the first utterance is long enough to keep.
        val (long, dur2) = track(
            400L to ROOM,
            3000L to VOICE,     // 3.0 s -- over the 2500 ms floor
            1500L to ROOM,
            5000L to VOICE,
        )
        assertEquals(2, StepCutter().cut(long, dur2).size)
    }

    /** Test 6: twenty clean utterances still come out as twelve steps. */
    @Test
    fun `never more than the step cap`() {
        val spans = mutableListOf<Pair<Long, Double>>(400L to ROOM)
        repeat(20) {
            spans += 3000L to VOICE
            spans += 1500L to ROOM
        }
        val (samples, dur) = track(*spans.toTypedArray())
        val steps = StepCutter().cut(samples, dur)
        assertEquals(Policy.DEFAULT.maxSteps, steps.size)
        assertTrue(steps.size <= Policy.DEFAULT.maxSteps)
    }

    /** Test 10: the steps tile the take exactly -- no gaps, no overlaps. */
    @Test
    fun `steps tile the take with no gaps and no overlaps`() {
        val spans = mutableListOf<Pair<Long, Double>>(400L to ROOM)
        repeat(6) {
            spans += 3000L to VOICE
            spans += 1500L to ROOM
        }
        val (samples, dur) = track(*spans.toTypedArray())
        val steps = StepCutter().cut(samples, dur)

        assertEquals(0L, steps.first().startMs)
        assertEquals(dur, steps.last().endMs)
        steps.zipWithNext { a, b -> assertEquals(a.endMs, b.startMs) }
        assertEquals(dur, steps.sumOf { it.durationMs })
        steps.forEachIndexed { i, s -> assertEquals(i, s.index) }
    }

    /** A take with nothing in it is one step, not zero. */
    @Test
    fun `silence only take is a single step`() {
        val (samples, dur) = track(5000L to ROOM)
        val steps = StepCutter().cut(samples, dur)
        assertEquals(1, steps.size)
        assertEquals(dur, steps.first().endMs)
    }

    /** With no recogniser behind it the confirmer abstains, so cuts pass through. */
    @Test
    fun `link word confirmer with no words passes candidates through untouched`() {
        val (samples, dur) = track(
            400L to ROOM, 3000L to VOICE, 1500L to ROOM, 3000L to VOICE,
        )
        val plain = StepCutter(Policy.DEFAULT).cut(samples, dur)
        val confirmed = StepCutter(
            Policy.DEFAULT,
            LinkWordConfirmer(Policy.DEFAULT.linkWords("hi"), emptyList()),
        ).cut(samples, dur)
        assertEquals(plain, confirmed)

        // A confirmer that vetoes everything collapses the take to one step.
        // LinkWordConfirmerTest covers what the real one keeps and drops.
        val vetoed = StepCutter(Policy.DEFAULT) { emptyList() }.cut(samples, dur)
        assertEquals(1, vetoed.size)
    }
}
