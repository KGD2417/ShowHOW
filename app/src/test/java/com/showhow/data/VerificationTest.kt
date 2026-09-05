package com.showhow.data

import com.showhow.core.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Draft, edited, verified, and what a learner is given in each case.
 *
 * The failure this guards is quiet: an expert verifies a guide, comes back to
 * fix a word, wanders off mid-edit, and a learner is then following a
 * half-finished draft under a tick that says an expert checked it. Every
 * assertion below is about which of the two files on disk answers that.
 */
class VerificationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = GuideStore(tmp.newFolder("guides"))

    private fun guide(id: String = "g1", title: String = "Replacing laptop RAM") = Guide(
        id = id,
        title = title,
        steps = listOf(
            Step(index = 0, transcript = "पहले लैपटॉप बंद करो", instruction = "Shut the laptop down."),
            Step(index = 1, transcript = "अब पेंच खोलो", instruction = "Undo the base screws."),
        ),
    )

    // --- 1. a draft ------------------------------------------------------

    @Test
    fun `a freshly built guide is a draft`() {
        assertFalse(guide().verified)
        assertEquals(0L, guide().verifiedAt)
    }

    @Test
    fun `a guide written before verification existed loads as a draft`() {
        // Not "assumed fine because it is old". Nobody was ever asked to check
        // it, so draft is the true answer.
        val old = """{"id":"g9","title":"New job","steps":[{"index":0}]}"""
        val g = Policy.json.decodeFromString(Guide.serializer(), old)
        assertFalse(g.verified)
    }

    @Test
    fun `a learner following a guide nobody verified still gets it`() {
        // An unverified guide beats no guide. Draft is a label, not a lock.
        val s = store()
        s.save(guide())
        assertEquals("Replacing laptop RAM", s.loadForLearner("g1")?.title)
        assertFalse(s.loadForLearner("g1")!!.verified)
    }

    // --- 2. an edited guide ----------------------------------------------

    @Test
    fun `editing a verified guide takes the tick off it`() {
        val verified = guide().copy(verifiedAt = 1_700_000_000_000)
        assertTrue(verified.verified)
        assertFalse(verified.copy(title = "Something else").asDraft().verified)
    }

    @Test
    fun `asDraft on a draft changes nothing, so it is free to call on every keystroke`() {
        val d = guide()
        assertTrue("an untouched draft should not be copied", d === d.asDraft())
    }

    @Test
    fun `a draft saved over a verified guide does not replace what the learner gets`() {
        // The whole point of two files. The expert is mid-edit; the learner is
        // mid-job; neither should be able to disturb the other.
        val s = store()
        s.saveVerified(guide())
        s.save(guide().copy(title = "half-finished edit"))

        assertEquals("half-finished edit", s.load("g1")?.title)
        assertEquals("Replacing laptop RAM", s.loadForLearner("g1")?.title)
        assertTrue(s.loadForLearner("g1")!!.verified)
    }

    @Test
    fun `a draft that deletes every step cannot strand the learner`() {
        val s = store()
        s.saveVerified(guide())
        s.save(guide().copy(steps = emptyList()))
        assertEquals(2, s.loadForLearner("g1")?.steps?.size)
    }

    // --- 3. a verified guide ---------------------------------------------

    @Test
    fun `verifying stamps the time and writes both files`() {
        val s = store()
        val before = System.currentTimeMillis()
        s.saveVerified(guide())

        assertTrue(s.verifiedFile("g1").isFile)
        assertTrue(s.guideFile("g1").isFile)
        val g = s.load("g1")!!
        assertTrue(g.verified)
        assertTrue("the stamp must be a real time", g.verifiedAt >= before)
        // Both copies agree at the moment of verification.
        assertEquals(g, s.loadForLearner("g1"))
    }

    @Test
    fun `a warning or a shaky provenance does not stop a guide being verified`() {
        // A model does not get a vote on whether an expert may sign off their
        // own work. Nothing about this guide's content is even consulted.
        val s = store()
        val doubtful = guide().copy(
            steps = listOf(
                Step(
                    index = 0,
                    instruction = "Use a PH0 driver.",
                    instructionSource = Provenance.GENERAL,
                    warning = "not sure the expert said which driver",
                ),
                Step(index = 1, instruction = "Lift the board.", aside = true),
            ),
        )
        s.saveVerified(doubtful)
        assertTrue(s.loadForLearner("g1")!!.verified)
        assertEquals("not sure the expert said which driver", s.load("g1")!!.steps[0].warning)
    }

    @Test
    fun `verification survives the round trip to disk`() {
        val s = store()
        s.saveVerified(guide())
        val reloaded = s.load("g1")!!
        assertTrue(Policy.json.encodeToString(Guide.serializer(), reloaded).contains("verifiedAt"))
        assertEquals(reloaded, s.loadForLearner("g1"))
    }

    // --- 4. reopening a verified guide -----------------------------------

    @Test
    fun `reopening a verified guide shows it as verified`() {
        val s = store()
        s.saveVerified(guide())
        assertTrue("Review must open the working copy, ticked", s.load("g1")!!.verified)
    }

    @Test
    fun `re-verifying after an edit moves the learner on to the new version`() {
        val s = store()
        s.saveVerified(guide())
        val first = s.loadForLearner("g1")!!.verifiedAt

        Thread.sleep(2)
        s.saveVerified(guide().copy(title = "Replacing laptop RAM and SSD"))

        val now = s.loadForLearner("g1")!!
        assertEquals("Replacing laptop RAM and SSD", now.title)
        assertNotEquals("re-verifying must re-stamp", first, now.verifiedAt)
    }

    @Test
    fun `a guide with no verified file at all falls back cleanly`() {
        val s = store()
        s.save(guide())
        assertFalse(s.verifiedFile("g1").exists())
        assertEquals("Replacing laptop RAM", s.loadForLearner("g1")?.title)
    }

    @Test
    fun `a corrupt verified file falls back to the working copy rather than nothing`() {
        // A guide half-copied between phones. Better the draft than a learner
        // staring at "nothing to play".
        val s = store()
        s.saveVerified(guide())
        s.verifiedFile("g1").writeText("{ this is not json")
        s.save(guide().copy(title = "working copy"))
        assertEquals("working copy", s.loadForLearner("g1")?.title)
    }

    @Test
    fun `an unknown guide is null, not an empty guide`() {
        assertNull(store().loadForLearner("nope"))
    }
}
