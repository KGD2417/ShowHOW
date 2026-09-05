package com.showhow.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Reads a step aloud in a synthetic voice.
 *
 * The expert's own recording is still on disk and still the source of truth --
 * this is a second way to hear a step, for the case where the expert mumbled,
 * spoke over a drill, or said it in a language the person following does not
 * read. A synthetic voice is only better than a real one when the real one is
 * hard to follow, so nothing here deletes take.wav.
 *
 * **Offline voices only.** Android ships voices that synthesise on the phone
 * and voices that call home, and the API will happily pick a network one if it
 * sounds better. [offlineVoice] rejects those outright: a guide that needs a
 * signal to read a step aloud is not the product this app claims to be.
 */
interface Narrator {
    /** Speaks, and returns when the phone has finished saying it. */
    suspend fun speak(text: String, lang: String)
    fun stop()
    fun release()
}

class DeviceNarrator(context: Context) : Narrator {

    @Volatile
    private var ready = false

    private var utterance = 0L

    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        if (!ready) Log.w(TAG, "no text-to-speech engine on this phone")
    }

    override suspend fun speak(text: String, lang: String) {
        if (!ready || text.isBlank()) return
        val locale = locale(lang)

        val voice = offlineVoice(locale)
        if (voice == null) {
            // Every voice for this language wants the network. Saying nothing is
            // correct: the step is still on screen and still playable in the
            // expert's own voice.
            Log.w(TAG, "no offline voice for $locale, staying quiet")
            return
        }
        tts.voice = voice

        val id = "showhow-" + utterance++
        suspendCancellableCoroutine { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }

                @Deprecated("required by the base class", ReplaceWith(""))
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Log.w(TAG, "speak failed: $errorCode")
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
            })

            val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (queued != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)

            cont.invokeOnCancellation { runCatching { tts.stop() } }
        }
    }

    override fun stop() {
        runCatching { tts.stop() }
    }

    override fun release() {
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }

    /**
     * The best voice for this language that synthesises on the phone.
     *
     * Quality first among the offline ones, because Android orders by nothing
     * in particular and the difference between its worst and best voice for a
     * language is the difference between a robot and a person.
     */
    private fun offlineVoice(locale: Locale): Voice? =
        runCatching {
            tts.voices
                ?.filter { it.locale.language == locale.language }
                ?.filter { !it.isNetworkConnectionRequired }
                ?.filter { Voice.QUALITY_VERY_LOW != it.quality }
                ?.maxByOrNull { it.quality }
        }.getOrNull()

    private companion object {
        const val TAG = "Narrator"

        fun locale(lang: String): Locale = when {
            lang.startsWith("mr") -> Locale("mr", "IN")
            lang.startsWith("hi") -> Locale("hi", "IN")
            else -> Locale("en", "IN")
        }
    }
}
