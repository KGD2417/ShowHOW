package com.showhow.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Recogniser output into readable English, and the lines it must not cross.
 *
 * The dangerous direction here is deletion: a filler list that swallows a real
 * word removes meaning from a guide, and the learner reading the result has no
 * way to know something was taken out. Several of these tests exist only to
 * pin that shut.
 */
class PlainEnglishTest {

    private val fillers = Policy.DEFAULT.fillerWords.map { it.lowercase() }.toSet()

    /** Words at a steady 300 ms, so any gap in a test is deliberate. */
    private fun said(vararg text: String, from: Long = 0): List<SpokenWord> =
        text.mapIndexed { i, t ->
            val start = from + i * 300L
            SpokenWord(t, start, start + 200L)
        }

    @Test
    fun `hesitations are dropped`() {
        val words = said("um", "take", "the", "uh", "screwdriver")
        assertEquals("take the screwdriver", stripFillers(words, fillers).joinToString(" ") { it.text })
    }

    @Test
    fun `a restart collapses to one word`() {
        val words = said("the", "the", "screwdriver")
        assertEquals("the screwdriver", stripFillers(words, fillers).joinToString(" ") { it.text })
    }

    @Test
    fun `content words that look like fillers are kept`() {
        // Every one of these is a real instruction. If this test ever goes red
        // because someone added "like" or "so" to the list, the guide is now
        // deleting meaning.
        val words = said("turn", "it", "like", "this", "so", "it", "sits", "right", "up")
        assertEquals(
            "turn it like this so it sits right up",
            stripFillers(words, fillers).joinToString(" ") { it.text },
        )
    }

    @Test
    fun `a pause becomes a full stop and the next word is capitalised`() {
        val words = listOf(
            SpokenWord("flip", 0, 300),
            SpokenWord("it", 350, 600),
            // a full second of nothing: the expert finished a thought
            SpokenWord("now", 1700, 2000),
            SpokenWord("unscrew", 2050, 2400),
        )
        assertEquals("Flip it. Now unscrew.", readable(words, 700))
    }

    @Test
    fun `words spoken without a real gap stay one sentence`() {
        assertEquals("Take the screwdriver.", readable(said("take", "the", "screwdriver"), 700))
    }

    @Test
    fun `both passes together`() {
        val words = listOf(
            SpokenWord("um", 0, 200),
            SpokenWord("flip", 250, 500),
            SpokenWord("the", 550, 700),
            SpokenWord("the", 750, 900),
            SpokenWord("laptop", 950, 1300),
            SpokenWord("uh", 3000, 3200),
            SpokenWord("then", 3250, 3500),
            SpokenWord("unscrew", 3550, 3900),
        )
        assertEquals("Flip the laptop. Then unscrew.", plainEnglish(words, fillers, 700))
    }

    @Test
    fun `a recogniser with no word durations still punctuates`() {
        // endMs defaults to startMs, so the gap reads start to start.
        val words = listOf(SpokenWord("flip", 0), SpokenWord("it", 200), SpokenWord("now", 1500))
        assertEquals("Flip it. Now.", readable(words, 700))
    }

    @Test
    fun `empty stays empty rather than becoming a full stop`() {
        assertEquals("", readable(emptyList(), 700))
        assertEquals("", stripFillers("", fillers))
    }

    @Test
    fun `the text-only path drops hesitations and leaves the rest`() {
        assertEquals("take the screwdriver", stripFillers("um take the uh screwdriver", fillers))
    }

    @Test
    fun `the sentence gap sits below the step cutter's pause`() {
        // If these ever cross, every step becomes exactly one sentence, because
        // any gap long enough to split one would already have ended the step.
        assert(Policy.DEFAULT.sentenceGapMs < Policy.DEFAULT.pauseMs)
    }
}
