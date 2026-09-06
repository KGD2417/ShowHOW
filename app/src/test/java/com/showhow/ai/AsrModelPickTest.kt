package com.showhow.ai

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Which model each recogniser is pointed at.
 *
 * Both bugs this covers were silent: 600 MB of better English sat unused beside
 * the model doing the work, and every English take was asked for in a tag the
 * phone did not have. Neither showed up as an error -- they showed up as an app
 * that mishears.
 */
class AsrModelPickTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** A model directory Vosk would accept: the one file [VoskAsr.verify] checks. */
    private fun model(name: String) {
        val dir = File(tmp.root, name)
        File(dir, "conf").mkdirs()
        File(dir, "conf/model.conf").writeText("--x=1\n")
    }

    @Test
    fun `the big model wins when it is on the phone`() {
        model("vosk-en")
        model("vosk-en-big")
        assertEquals(File(tmp.root, "vosk-en-big"), VoskAsr.dirFor(tmp.root, "en"))
    }

    @Test
    fun `the plain model is used when there is no big one`() {
        model("vosk-hi")
        assertEquals(File(tmp.root, "vosk-hi"), VoskAsr.dirFor(tmp.root, "hi"))
    }

    @Test
    fun `a half-unpacked big model does not win`() {
        model("vosk-en")
        File(tmp.root, "vosk-en-big/am").mkdirs()   // no conf/model.conf
        assertEquals(File(tmp.root, "vosk-en"), VoskAsr.dirFor(tmp.root, "en"))
    }

    @Test
    fun `a language present only as big still counts as present`() {
        model("vosk-en-big")
        assertEquals(listOf("en"), VoskAsr.languagesPresent(tmp.root))
    }

    @Test
    fun `the system engine is asked in the tag the phone actually has`() {
        assertEquals("en-US", DeviceAsr.bcp47("en", Locale.US))
        assertEquals("en-IN", DeviceAsr.bcp47("en", Locale.forLanguageTag("en-IN")))
    }

    @Test
    fun `an unrelated phone locale falls back to the Indian tag`() {
        assertEquals("hi-IN", DeviceAsr.bcp47("hi", Locale.US))
        assertEquals("mr-IN", DeviceAsr.bcp47("mr", Locale.US))
        assertEquals("en-IN", DeviceAsr.bcp47("en", Locale.forLanguageTag("hi-IN")))
    }
}
