package com.showhow.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The corrector, and mostly the things it must refuse to do.
 *
 * A corrector that rewrites speech the expert actually said is worse than a
 * recogniser that mishears, because the learner has no way to tell. Half of
 * these are "leave it alone".
 */
class DomainWordsTest {

    private val terms = listOf(
        "screwdriver", "screwdrivers", "clockwise", "counterclockwise",
        "anticlockwise", "unscrew", "laptop", "philips", "torx",
    )

    private fun fix(text: String) = correctDomainText(text, terms)

    @Test
    fun `the split the language model prefers is put back together`() {
        assertEquals("take the screwdriver", fix("take the screw driver"))
        assertEquals("turn it counterclockwise", fix("turn it counter clockwise"))
        assertEquals("turn it anticlockwise", fix("turn it anti clockwise"))
    }

    @Test
    fun `a long word one edit out is repaired`() {
        assertEquals("the screwdriver", fix("the screwdrive"))
        assertEquals("turn clockwise", fix("turn clockwize"))
    }

    @Test
    fun `a word already heard correctly is left exactly alone`() {
        assertEquals("undo the screws on the laptop", fix("undo the screws on the laptop"))
        assertEquals("screwdrivers", fix("screwdrivers"))
    }

    @Test
    fun `short words are never fuzzy matched`() {
        // "torx" is a term, "torn" is one edit away, and both are real words.
        assertEquals("the cable is torn", fix("the cable is torn"))
        // "laptop" is six characters, under the floor: "laptops" stays put.
        assertEquals("two laptops", fix("two laptops"))
    }

    @Test
    fun `ordinary speech is not dragged towards the vocabulary`() {
        val plain = "hold the panel and lift the back cover off the table"
        assertEquals(plain, fix(plain))
    }

    @Test
    fun `two edits away is left alone`() {
        // "screwdiver" is one edit; "scrudiver" is more, and guessing there is
        // how a corrector starts inventing words.
        assertEquals("scrudiver", fix("scrudiver"))
    }

    @Test
    fun `a merge reports how many words it consumed so clocks survive`() {
        val fixes = correctDomainTokens(listOf("the", "screw", "driver", "here"), terms)
        assertEquals(listOf("the", "screwdriver", "here"), fixes.map { it.text })
        assertEquals(listOf(1, 2, 1), fixes.map { it.took })
    }

    @Test
    fun `empty input and empty vocabulary are both no-ops`() {
        assertEquals("", fix(""))
        assertEquals("screw driver", correctDomainText("screw driver", emptyList()))
    }
}
