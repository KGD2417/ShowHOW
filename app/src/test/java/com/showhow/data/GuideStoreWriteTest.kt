package com.showhow.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A guide that will not parse is a guide that has disappeared -- take,
 * photographs and all -- while the folder sits on disk full of the expert's
 * recording and nothing in the app can reach it.
 *
 * So the only thing these tests care about is that a failed write loses the
 * *new* version rather than the old one, and that nothing here ever throws.
 */
class GuideStoreWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun store() = GuideStore(tmp.newFolder("guides"))

    private fun guide(id: String, title: String) = Guide(
        id = id,
        title = title,
        lang = "hi",
        createdAt = 1L,
        steps = listOf(Step(index = 0, title = "Step 1", startMs = 0, endMs = 1000)),
    )

    @Test
    fun `a saved guide reads back`() {
        val s = store()
        assertTrue(s.save(guide("g1", "Open the panel")))
        assertEquals("Open the panel", s.load("g1")?.title)
    }

    @Test
    fun `saving twice replaces rather than appends`() {
        val s = store()
        s.save(guide("g1", "First"))
        s.save(guide("g1", "Second"))
        assertEquals("Second", s.load("g1")?.title)
        // The temp file the atomic write uses must not be left behind, or the
        // next guide folder listing counts it and a phone accumulates one per
        // save for the life of the app.
        assertFalse(
            s.guideFile("g1").parentFile!!.listFiles()!!.any { it.name.endsWith(".tmp") },
        )
    }

    @Test
    fun `a write that cannot happen leaves the previous guide intact`() {
        val s = store()
        s.save(guide("g1", "The good one"))

        // The failure this exists for, staged so it happens on every OS: put a
        // directory where the temp file has to go, and the write cannot start.
        // On the phone it is a full disk or a process killed halfway through,
        // and the damage is the same shape -- the old file truncated and the
        // new one never finished, which is a guide that has vanished.
        val blocker = File(s.guideFile("g1").parentFile, "guide.json.tmp")
        assertTrue(blocker.mkdirs())

        assertFalse("a failed write must say so", s.save(guide("g1", "The one that fails")))
        assertEquals("The good one", s.load("g1")?.title)

        // And once the obstruction is gone, saving works again -- a failure is
        // not a state the store gets stuck in.
        assertTrue(blocker.delete())
        assertTrue(s.save(guide("g1", "The next one")))
        assertEquals("The next one", s.load("g1")?.title)
    }

    @Test
    fun `verifying writes both copies and reports on both`() {
        val s = store()
        s.save(guide("g1", "Draft"))
        assertTrue(s.saveVerified(guide("g1", "Checked")))
        assertEquals("Checked", s.load("g1")?.title)
        assertEquals("Checked", s.loadForLearner("g1")?.title)
        assertNotNull(s.loadForLearner("g1")?.verifiedAt)
    }

    @Test
    fun `a learner follows the verified copy while a draft is being edited`() {
        val s = store()
        s.saveVerified(guide("g1", "Checked"))
        s.save(guide("g1", "Half-finished edit"))
        assertEquals("Half-finished edit", s.load("g1")?.title)
        assertEquals("Checked", s.loadForLearner("g1")?.title)
    }

    @Test
    fun `a corrupt guide file reads as absent, not as a crash`() {
        val s = store()
        s.save(guide("g1", "Fine"))
        // Exactly what a truncated write used to leave behind.
        s.guideFile("g1").writeText("""{"id":"g1","title":"Fin""")
        assertEquals(null, s.load("g1"))
        assertEquals(null, s.loadForLearner("g1"))
        // And the folder -- the take, the photographs -- is still there.
        assertTrue(File(s.guideFile("g1").parentFile, "guide.json").isFile)
    }
}
