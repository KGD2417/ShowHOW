package com.showhow.core

import com.showhow.data.Guide
import com.showhow.data.Step
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PolicyStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Test 11a: Policy survives a serialize/deserialize round trip. */
    @Test
    fun `policy json round trip`() {
        val original = Policy.DEFAULT.copy(
            pauseMs = 900,
            maxSteps = 7,
            linkWordsMr = listOf("mag", "tyanantar"),
        )
        assertEquals(original, Policy.parse(original.encode()))
    }

    /** Test 11b: Guide and Step too -- guides are files, so this is the format. */
    @Test
    fun `guide json round trip`() {
        val guide = Guide(
            id = "g1",
            title = "Clean the filter",
            lang = "mr",
            createdAt = 1_700_000_000_000,
            steps = listOf(
                Step(0, "Step 1", "Lid off", 0, 3000, "s1.jpg"),
                Step(1, "Step 2", "Basket out", 3000, 7200, "s2.jpg", warning = "Water is hot"),
            ),
        )
        val text = Policy.json.encodeToString(Guide.serializer(), guide)
        assertEquals(guide, Policy.json.decodeFromString(Guide.serializer(), text))
    }

    /** The seed copies once, then filesDir is the only source of truth. */
    @Test
    fun `seeds the file once then reads only the file`() {
        val f = File(tmp.root, "policy.json")
        val store = PolicyStore(f)
        store.seedAndLoad { Policy.DEFAULT.encode() }

        assertTrue(f.exists())
        assertEquals(Policy.DEFAULT, store.policy.value)

        // Push a tuned file the way adb would, then reload. No restart.
        f.writeText(Policy.DEFAULT.copy(pauseMs = 800, maxSteps = 5).encode())
        assertTrue(store.reload())
        assertEquals(800L, store.policy.value.pauseMs)
        assertEquals(5, store.policy.value.maxSteps)

        // Seeding again must not clobber the tuned file.
        store.seedAndLoad { Policy.DEFAULT.encode() }
        assertEquals(800L, store.policy.value.pauseMs)
    }

    /**
     * Test 12: a malformed policy.json keeps the last good values.
     *
     * At 4am someone will fat-finger a comma over adb. That must not reset the
     * tuning and must not take the app down.
     */
    @Test
    fun `malformed policy keeps the previous good value`() {
        val f = File(tmp.root, "policy.json")
        val store = PolicyStore(f)
        store.seedAndLoad { Policy.DEFAULT.copy(pauseMs = 1500).encode() }
        assertEquals(1500L, store.policy.value.pauseMs)

        f.writeText("{ \"pauseMs\": 900,,, ")            // trailing garbage
        assertFalse(store.reload())
        assertEquals("previous policy was lost", 1500L, store.policy.value.pauseMs)
        assertNotNull(store.lastError)

        // A file that vanishes is the same story.
        f.delete()
        assertFalse(store.reload())
        assertEquals(1500L, store.policy.value.pauseMs)

        // And a good file afterwards recovers cleanly.
        f.writeText(Policy.DEFAULT.copy(pauseMs = 1100).encode())
        assertTrue(store.reload())
        assertEquals(1100L, store.policy.value.pauseMs)
        assertEquals(null, store.lastError)
    }

    /** Unknown keys are tolerated: a stale field must not brick the reload. */
    @Test
    fun `unknown keys are ignored`() {
        val f = File(tmp.root, "policy.json")
        f.writeText("{\"pauseMs\": 1300, \"somethingWeAddedLater\": 42}")
        val store = PolicyStore(f)
        assertTrue(store.reload())
        assertEquals(1300L, store.policy.value.pauseMs)
    }
}
