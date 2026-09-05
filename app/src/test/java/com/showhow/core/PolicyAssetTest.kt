package com.showhow.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shipped policy.json has to carry every knob [Policy] has.
 *
 * This exists because it already failed once, silently, in the worst possible
 * way. `Policy.kt` was fixed to hold the Devanagari linking words the Vosk
 * models actually emit; the asset still held only the romanised ones. On first
 * run the asset is copied to filesDir and from then on the app reads *only*
 * that file -- so the fix was live in the source, dead on every phone, and the
 * config file looked entirely correct while the confirmer abstained on every
 * cut. Nothing crashed and nothing logged.
 *
 * A missing key is not a crash either, because the parser is deliberately
 * lenient and fills in the Kotlin default. That is the trap: during the ten
 * hours nobody can compile, a knob that is not in this file is a knob the team
 * cannot turn, and there is no symptom until someone needs it at 3am.
 *
 * Keys, not values. The team is meant to tune the asset away from the defaults
 * -- that is what it is for. Drifting out of sync is the thing to catch.
 */
class PolicyAssetTest {

    private val asset = File("src/main/assets/policy.json")

    private val shipped by lazy {
        Json.parseToJsonElement(asset.readText(Charsets.UTF_8)).jsonObject
    }

    @Test
    fun `every knob in Policy is in the shipped asset, and nothing extra`() {
        // The serializer's own view of the class, so adding a knob to Policy.kt
        // and forgetting the asset fails here rather than at 3am on a phone.
        val d = Policy.serializer().descriptor
        val declared = (0 until d.elementsCount).map { d.getElementName(it) }.toSet()
        assertEquals(
            "policy.json is out of sync with Policy.kt",
            declared.sorted(),
            shipped.keys.sorted(),
        )
    }

    @Test
    fun `the shipped asset parses into a Policy`() {
        // Lenient parsing means a typo'd key is silently ignored rather than
        // rejected, so "it parses" is necessary and nowhere near sufficient --
        // which is why the test above checks the key set instead.
        Policy.parse(asset.readText(Charsets.UTF_8))
    }

    @Test
    fun `the shipped linking words include the Devanagari the models emit`() {
        // The regression this file was written for. Vosk Hindi emits "फिर", not
        // "phir"; a romanised-only list matches nothing and the second opinion
        // on every cut quietly stops existing.
        for (key in listOf("linkWordsHi", "linkWordsMr")) {
            val words = shipped.getValue(key).jsonArray.map { it.toString() }
            assertTrue(
                "$key has no Devanagari, so it cannot match anything Vosk says",
                words.any { w -> w.any { it in 'ऀ'..'ॿ' } },
            )
        }
    }
}
