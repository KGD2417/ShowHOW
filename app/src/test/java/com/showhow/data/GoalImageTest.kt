package com.showhow.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Whether a step has a picture of what it should end up looking like.
 *
 * The Player itself needs an emulator to test, but this decision does not, and
 * it is the one that matters: it is what stands between a learner and a black
 * rectangle captioned "no photo" taking a third of their screen to announce a
 * failure that has not happened. A step without a photo is ordinary -- the
 * phone was face down, or the nearest frame was the inside of a pocket -- and
 * the honest answer is the instruction and the expert's voice, with no picture.
 */
class GoalImageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = GuideStore(tmp.newFolder("guides"))

    private fun jpeg(store: GuideStore, id: String, name: String, bytes: Int = 64): File =
        File(store.dir(id), name).apply { writeBytes(ByteArray(bytes)) }

    @Test
    fun `a step whose photo is on disk gets that photo`() {
        val s = store()
        val f = jpeg(s, "g1", "snap0.jpg")
        assertEquals(f, s.goalImage("g1", "snap0.jpg"))
    }

    @Test
    fun `a step that never had a photo gets null, not a placeholder`() {
        assertNull(store().goalImage("g1", ""))
    }

    @Test
    fun `a photo named in the guide but missing from disk gets null`() {
        // A guide folder copied between phones with the JPEGs left behind. The
        // step still knows the name; the file is not there.
        val s = store()
        s.dir("g1")
        assertNull(s.goalImage("g1", "snap0.jpg"))
    }

    @Test
    fun `a zero length photo gets null`() {
        // An interrupted write leaves a file that exists and decodes to
        // nothing. Existing is not the same as usable.
        val s = store()
        jpeg(s, "g1", "snap0.jpg", bytes = 0)
        assertNull(s.goalImage("g1", "snap0.jpg"))
    }

    @Test
    fun `a directory sharing the photo's name is not a photo`() {
        val s = store()
        File(s.dir("g1"), "snap0.jpg").mkdirs()
        assertNull(s.goalImage("g1", "snap0.jpg"))
    }

    @Test
    fun `each step resolves its own photo, and a photoless step among them stays null`() {
        // The shape the Player actually walks: some steps have a frame, some do
        // not, and one missing photo must not disturb its neighbours.
        val s = store()
        jpeg(s, "g1", "snap0.jpg")
        jpeg(s, "g1", "snap2.jpg")
        val steps = listOf(
            Step(index = 0, photo = "snap0.jpg"),
            Step(index = 1, photo = ""),
            Step(index = 2, photo = "snap2.jpg"),
        )
        val resolved = steps.map { s.goalImage("g1", it.photo) }
        assertEquals("snap0.jpg", resolved[0]?.name)
        assertNull(resolved[1])
        assertEquals("snap2.jpg", resolved[2]?.name)
    }

    @Test
    fun `a re-recorded step keeps its goal image, because audio and picture are separate`() {
        val s = store()
        jpeg(s, "g1", "snap0.jpg")
        val step = Step(index = 0, photo = "snap0.jpg", audio = "step0.wav")
        assertEquals("snap0.jpg", s.goalImage("g1", step.photo)?.name)
    }
}
