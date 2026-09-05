package com.showhow.ai

import com.showhow.core.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pointing at a named component, and refusing to when nothing pointed.
 *
 * The failure is a rectangle over a laptop labelled "RAM" that the detector
 * never reported. A learner will act on a box: it is the most authoritative
 * thing the app can draw, it sits over their actual hardware, and they cannot
 * check it. So there is no "probably here" case and no third state -- either a
 * real detection is behind the box, or the box is not drawn.
 */
class ComponentLocatorTest {

    /**
     * A phone still carrying the stock COCO detector.
     *
     * Spelled out rather than taken from Policy.DEFAULT, because the default
     * now names the classes of the fine-tuned model that replaced it. This is
     * what the app does when someone has not pushed that model yet, and it
     * must still refuse to invent a box.
     */
    private val coco = ComponentLocator(
        {
            mapOf(
                "laptop" to listOf("laptop"),
                "keyboard" to listOf("keyboard"),
                "mouse" to listOf("mouse"),
                "screen" to listOf("tv", "laptop"),
                "ram" to emptyList(),
                "ssd" to emptyList(),
                "screw" to emptyList(),
                "screwdriver" to emptyList(),
                "heatsink" to emptyList(),
            )
        },
        { 0.60f },
    )

    /** The shipped configuration, which now expects the fine-tuned detector. */
    private val shipped = ComponentLocator({ Policy.DEFAULT.componentAliases }, { 0.60f })

    /** What a fine-tuned laptop-parts model would look like, config only. */
    private val laptopParts = ComponentLocator(
        {
            Policy.DEFAULT.componentAliases + mapOf(
                "ram" to listOf("ram_module", "memory"),
                "ssd" to listOf("m2_ssd"),
            )
        },
        { 0.60f },
    )

    private fun frame(vararg boxes: Pair<String, Float>) = Detections(
        boxes.map { (label, score) ->
            DetectionBox(label, score, 0.1f, 0.1f, 0.4f, 0.4f)
        },
    )

    // --- 1. detected RAM --------------------------------------------------

    @Test
    fun `a model that reports RAM gets a box, and it is the detector's own`() {
        // Config only -- no rebuild, no code change. This is the whole point of
        // the aliases living in policy.json.
        val out = laptopParts.locate("ram", frame("ram_module" to 0.81f, "laptop" to 0.9f))
        assertTrue(out is Localization.Found)
        out as Localization.Found
        assertEquals("ram_module", out.label)
        assertEquals(0.81f, out.score, 1e-4f)
    }

    @Test
    fun `an alias list may name several labels for one component`() {
        val out = laptopParts.locate("ram", frame("memory" to 0.77f))
        assertTrue(out is Localization.Found)
    }

    @Test
    fun `the best box wins when a component appears twice`() {
        val out = laptopParts.locate("ram", frame("ram_module" to 0.62f, "memory" to 0.88f))
        assertEquals(0.88f, (out as Localization.Found).score, 1e-4f)
    }

    // --- 2. detected SSD --------------------------------------------------

    @Test
    fun `a model that reports an SSD gets a box`() {
        val out = laptopParts.locate("ssd", frame("m2_ssd" to 0.74f))
        assertEquals("m2_ssd", (out as Localization.Found).label)
    }

    @Test
    fun `component names are matched case and space insensitively`() {
        assertTrue(laptopParts.locate("  SSD ", frame("M2_SSD" to 0.74f)) is Localization.Found)
    }

    // --- 3. unsupported label --------------------------------------------

    @Test
    fun `the detector on this phone cannot find RAM, and says so instead of guessing`() {
        // The limitation, kept explicit. A generic COCO model has no RAM label
        // and never will; asking is answered, not silently failed.
        val out = coco.locate("ram", frame("laptop" to 0.95f, "keyboard" to 0.88f))
        assertTrue(out is Localization.Uncertain)
        assertTrue((out as Localization.Uncertain).reason.contains("no label"))
        assertFalse(coco.supports("ram"))
    }

    @Test
    fun `nor an SSD, a screw, a screwdriver or a heatsink`() {
        for (part in listOf("ssd", "screw", "screwdriver", "heatsink", "battery")) {
            assertTrue(part, coco.locate(part, frame("laptop" to 0.95f)) is Localization.Uncertain)
            assertFalse(part, coco.supports(part))
        }
    }

    @Test
    fun `what it can find, it finds`() {
        // The limitation is specific, not blanket. COCO really does know these.
        assertTrue(coco.locate("laptop", frame("laptop" to 0.95f)) is Localization.Found)
        assertTrue(coco.supports("keyboard"))
        assertEquals(setOf("laptop", "keyboard", "mouse", "screen"), coco.vocabulary())
    }

    @Test
    fun `the shipped policy now knows screwdrivers and screw heads`() {
        // What the fine-tune bought. These names exist here only because a
        // model was actually trained on labels with those names.
        for (c in listOf("screwdriver", "screw", "philips", "pozidriv", "torx", "hex")) {
            assertTrue(c, shipped.supports(c))
        }
        assertTrue(
            shipped.locate("screwdriver", frame("screwdriver" to 0.83f)) is Localization.Found,
        )
        assertEquals(
            "philips_screw",
            (shipped.locate("philips", frame("philips_screw" to 0.77f)) as Localization.Found).label,
        )
    }

    @Test
    fun `the parts no dataset covered are still honestly absent`() {
        // RAM, SSD and heatsink were not in any dataset that was trained on, so
        // they stay empty and still answer "no label for that" rather than
        // borrowing a box from a class that happens to be nearby.
        for (c in listOf("ram", "ssd", "heatsink", "battery")) {
            assertFalse(c, shipped.supports(c))
            assertTrue(c, shipped.locate(c, frame("screwdriver" to 0.9f)) is Localization.Uncertain)
        }
    }

    @Test
    fun `a component nobody has configured at all is uncertain, not a crash`() {
        assertTrue(coco.locate("flux capacitor", frame("laptop" to 0.9f)) is Localization.Uncertain)
        assertTrue(coco.locate("", frame("laptop" to 0.9f)) is Localization.Uncertain)
    }

    // --- 4. no detector ---------------------------------------------------

    @Test
    fun `no detector means uncertain, and says nothing is looking`() {
        // Distinguished from "it looked and did not find it", because those are
        // different things to tell someone waiting for an answer.
        val out = coco.locate("laptop", Detections())
        assertTrue(out is Localization.Uncertain)
        assertTrue((out as Localization.Uncertain).reason.contains("not showing anything"))
    }

    @Test
    fun `a detector that is running but has not found it says so differently`() {
        val out = coco.locate("mouse", frame("laptop" to 0.9f))
        assertEquals("not in this frame", (out as Localization.Uncertain).reason)
    }

    // --- 5. low confidence ------------------------------------------------

    @Test
    fun `a box below the confidence floor is not pointed at`() {
        // Seen, but not clearly enough to name. Saying so beats drawing a box
        // the learner would trust more than the detector does.
        val out = laptopParts.locate("ram", frame("ram_module" to 0.41f))
        assertTrue(out is Localization.Uncertain)
        assertTrue((out as Localization.Uncertain).reason.contains("below the confidence"))
    }

    @Test
    fun `the floor is a policy value, so it can be turned without a rebuild`() {
        val lenient = ComponentLocator(
            { Policy.DEFAULT.componentAliases + mapOf("ram" to listOf("ram_module")) },
            { 0.30f },
        )
        assertTrue(lenient.locate("ram", frame("ram_module" to 0.41f)) is Localization.Found)
    }

    @Test
    fun `a confident wrong label never stands in for the one that was asked for`() {
        // "I could not find the RAM but here is a laptop" is exactly the box
        // that gets a motherboard scratched.
        val out = laptopParts.locate("ram", frame("laptop" to 0.99f, "keyboard" to 0.97f))
        assertTrue(out is Localization.Uncertain)
    }
}
