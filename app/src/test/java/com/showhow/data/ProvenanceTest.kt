package com.showhow.data

import com.showhow.core.Policy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a coached instruction came from, and what happens to a guide written
 * before anyone was asking.
 *
 * This is the one label in the app that admits a model may have gone past what
 * the expert said, so the failure that matters is not a crash -- it is a
 * confident EXPERT on a sentence the expert never uttered. Every case here is
 * about which way the doubt falls.
 */
class ProvenanceTest {

    private fun decode(json: String): Guide =
        Policy.json.decodeFromString(Guide.serializer(), json)

    // --- 1. expert -------------------------------------------------------

    @Test
    fun `an instruction rewritten from what the expert said is EXPERT`() {
        assertEquals(
            Provenance.EXPERT,
            provenanceOf(
                transcript = "फिर इसको निकालो",
                caption = "laptop, keyboard",
                instruction = "Now lift the RAM module out.",
            ),
        )
    }

    @Test
    fun `a transcript wins over a caption, because words beat a guess at a photo`() {
        assertEquals(
            Provenance.EXPERT,
            provenanceOf("undo the screws", "laptop", "Undo the ten base screws."),
        )
    }

    // --- 2. visual -------------------------------------------------------

    @Test
    fun `an instruction with no words behind it, only a photo, is VISUAL`() {
        // A silent step: the expert worked without narrating, the detector saw
        // what was in shot, and the coach wrote from that. Real and common --
        // and not the expert's word, which is the entire point of the label.
        assertEquals(
            Provenance.VISUAL,
            provenanceOf(
                transcript = "",
                caption = "laptop, screwdriver",
                instruction = "Set the screwdriver down beside the open case.",
            ),
        )
    }

    @Test
    fun `whitespace is not a transcript`() {
        // "  " would otherwise read as the expert having spoken, which is the
        // exact false-EXPERT this class exists to prevent.
        assertEquals(Provenance.VISUAL, provenanceOf("   ", "laptop", "Open the base."))
    }

    // --- 3. general ------------------------------------------------------

    @Test
    fun `no words and no photo means the model wrote it, so GENERAL`() {
        assertEquals(
            Provenance.GENERAL,
            provenanceOf(
                transcript = "",
                caption = "",
                instruction = "Use a PH0 screwdriver for the base screws.",
            ),
        )
    }

    // --- 4. unknown ------------------------------------------------------

    @Test
    fun `no instruction at all is UNKNOWN, however much else the step has`() {
        // A blank is not evidence of anything. The coach may have been absent,
        // or dropped this line; from here the two are indistinguishable and
        // neither is a claim about a source.
        assertEquals(Provenance.UNKNOWN, provenanceOf("said plenty", "laptop", ""))
        assertEquals(Provenance.UNKNOWN, provenanceOf("", "", ""))
        assertEquals(Provenance.UNKNOWN, provenanceOf("said plenty", "laptop", "   "))
    }

    @Test
    fun `a Step defaults to UNKNOWN rather than to EXPERT`() {
        assertEquals(Provenance.UNKNOWN, Step(index = 0).instructionSource)
    }

    // --- 5. old guide files ----------------------------------------------

    @Test
    fun `a guide written before the field existed still loads`() {
        // Verbatim shape of a guide.json from before this commit. It must open,
        // keep every field it does have, and not lose the transcript -- the
        // expert's evidence -- to a schema change.
        val old = """
            {
              "id": "g1730000000000",
              "title": "New job",
              "lang": "hi",
              "createdAt": 1730000000000,
              "take": "take.wav",
              "steps": [
                {
                  "index": 0,
                  "title": "Step 1",
                  "caption": "laptop, keyboard",
                  "startMs": 0,
                  "endMs": 8000,
                  "photo": "snap0.jpg",
                  "transcript": "पहले लैपटॉप बंद करो",
                  "audio": "",
                  "modeHint": ""
                }
              ]
            }
        """.trimIndent()

        val g = decode(old)
        assertEquals("g1730000000000", g.id)
        assertEquals(1, g.steps.size)
        assertEquals("पहले लैपटॉप बंद करो", g.steps[0].transcript)
        assertEquals("snap0.jpg", g.steps[0].photo)
        // The two fields that did not exist when this file was written.
        assertEquals("", g.steps[0].instruction)
        assertEquals(Provenance.UNKNOWN, g.steps[0].instructionSource)
    }

    @Test
    fun `a provenance value this build does not know degrades to UNKNOWN`() {
        // GuideStore.load returns null on any parse failure, so without lenient
        // coercion a single enum constant added by a later build would cost the
        // reader the whole guide rather than one label on one step. Falling to
        // UNKNOWN is also the safe direction: never up to EXPERT.
        val future = """
            {
              "id": "g2",
              "steps": [
                {
                  "index": 0,
                  "transcript": "undo the screws",
                  "instruction": "Undo the base screws.",
                  "instructionSource": "MEASURED"
                }
              ]
            }
        """.trimIndent()

        val g = decode(future)
        assertEquals(Provenance.UNKNOWN, g.steps[0].instructionSource)
        assertEquals("Undo the base screws.", g.steps[0].instruction)
    }

    @Test
    fun `a guide round-trips through disk with its provenance intact`() {
        val before = Guide(
            id = "g3",
            title = "Replacing laptop RAM",
            steps = listOf(
                Step(index = 0, transcript = "x", instruction = "X.", instructionSource = Provenance.EXPERT),
                Step(index = 1, caption = "laptop", instruction = "Y.", instructionSource = Provenance.VISUAL),
                Step(index = 2, instruction = "Z.", instructionSource = Provenance.GENERAL),
                Step(index = 3),
            ),
        )
        val text = Policy.json.encodeToString(Guide.serializer(), before)
        assertTrue("provenance must survive to disk", text.contains("instructionSource"))
        assertEquals(before, decode(text))
    }
}
