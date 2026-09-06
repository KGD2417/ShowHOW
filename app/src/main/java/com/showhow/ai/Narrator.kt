package com.showhow.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
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

    /**
     * The utterance the listener is waiting on, and whoever is waiting.
     *
     * One shared [TextToSpeech] means one shared progress listener, so a
     * second [speak] used to replace the first's listener and QUEUE_FLUSH its
     * audio -- and the first call then never returned, because nothing was
     * left to resume it. The auto-advance awaits [speak] before turning the
     * page, so that hang was a guide that stopped moving. A superseded caller
     * is released here instead: its audio is gone, so it is finished.
     */
    private val lock = Any()
    private var speaking: String? = null
    private var waiting: CancellableContinuation<Unit>? = null

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = release(utteranceId)

            @Deprecated("required by the base class", ReplaceWith(""))
            override fun onError(utteranceId: String?) = release(utteranceId)

            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.w(TAG, "speak failed: $errorCode")
                release(utteranceId)
            }
        })
    }

    /** Let go of whoever is waiting on [id], or on anything if null. */
    private fun release(id: String?) = synchronized(lock) {
        if (id != null && speaking != null && id != speaking) return@synchronized
        waiting?.takeIf { it.isActive }?.resume(Unit)
        waiting = null
        speaking = null
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

        suspendCancellableCoroutine { cont ->
            val id = synchronized(lock) {
                // QUEUE_FLUSH below is about to delete whatever is playing, so
                // whoever was awaiting it is done, one way or the other.
                waiting?.takeIf { it.isActive }?.resume(Unit)
                waiting = cont
                speaking = "showhow-" + utterance++
                speaking!!
            }

            val queued = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
            if (queued != TextToSpeech.SUCCESS) release(id)

            cont.invokeOnCancellation {
                runCatching { tts.stop() }
                synchronized(lock) {
                    if (speaking == id) {
                        waiting = null
                        speaking = null
                    }
                }
            }
        }
    }

    override fun stop() {
        runCatching { tts.stop() }
        // stop() fires no callback for an utterance it kills, so the waiter is
        // released here or it waits forever.
        release(null)
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
