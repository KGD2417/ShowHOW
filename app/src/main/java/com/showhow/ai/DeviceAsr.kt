package com.showhow.ai

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.File
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Transcription by the phone's own speech engine, which is the only route to
 * hardware-accelerated speech on this device.
 *
 * Vosk is Kaldi: a C++ decoder with no delegate and no accelerator, so it will
 * always be CPU no matter what model is fed to it. The system recogniser is the
 * opposite -- Qualcomm and the OEM run it on the Hexagon DSP, and it is the
 * same engine the phone uses for its own dictation, so it is far better on
 * English than a 600 MB Kaldi graph.
 *
 * [SpeechRecognizer.createOnDeviceSpeechRecognizer] is used deliberately rather
 * than the ordinary one. The ordinary recogniser is allowed to use the network;
 * the on-device recogniser is contractually not. That distinction is the
 * difference between this app keeping its central claim and quietly breaking
 * it, and it is why the ordinary constructor appears nowhere in this file.
 *
 * **It has no word timings.** The system engine returns sentences, not word
 * clocks. Rather than invent timings -- which would poison the step cutter with
 * confident nonsense -- [hasWordTimings] is false and the ViewModel transcribes
 * each step's slice separately once the cuts are already decided. The
 * link-word confirmer then abstains, which is the behaviour it was written to
 * have when there is nothing to vote with.
 */
class DeviceAsr(private val context: Context) : Asr {

    /** The system engine returns sentences, not per-word clocks. */
    override val hasWordTimings: Boolean = false

    /**
     * @return one pseudo-word carrying the whole slice, because callers that
     *   ask this of a timing-free recogniser want the text, not the clock.
     */
    override suspend fun transcribe(wav: File, lang: String): List<Word> {
        val text = transcribeText(wav, lang)
        return if (text.isBlank()) emptyList() else listOf(Word(text, 0, 0, 1f))
    }

    /**
     * Recognise one PCM16/16k/mono WAV.
     *
     * The engine wants headerless PCM on a pipe, so the file is replayed into
     * one with the RIFF header skipped.
     */
    suspend fun transcribeText(wav: File, lang: String): String {
        if (!available(context)) return ""
        if (!wav.exists() || wav.length() <= HEADER_BYTES) return ""
        val started = System.currentTimeMillis()

        val text = withTimeoutOrNull(TIMEOUT_MS) { run(wav, lang) }
        if (text == null) {
            Log.w(TAG, "recogniser did not answer within $TIMEOUT_MS ms")
            return ""
        }
        Log.i(TAG, "device asr: ${text.length} chars in ${System.currentTimeMillis() - started} ms")
        return text
    }

    private suspend fun run(wav: File, lang: String): String =
        suspendCancellableCoroutine { cont ->
            // SpeechRecognizer is a main-thread object: created there, called
            // there, and it delivers callbacks there.
            Handler(Looper.getMainLooper()).post {
                val recognizer = runCatching {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                }.getOrElse {
                    Log.w(TAG, "no on-device recogniser", it)
                    if (cont.isActive) cont.resume("")
                    return@post
                }

                val pipe = runCatching { pcmPipe(wav) }.getOrElse {
                    Log.w(TAG, "could not feed the wav to the recogniser", it)
                    recognizer.destroy()
                    if (cont.isActive) cont.resume("")
                    return@post
                }

                fun finish(result: String) {
                    runCatching { recognizer.destroy() }
                    runCatching { pipe.close() }
                    if (cont.isActive) cont.resume(result)
                }

                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: android.os.Bundle) {
                        finish(best(results))
                    }

                    override fun onError(error: Int) {
                        // ERROR_NO_MATCH on a quiet slice is normal, not a fault.
                        if (error != SpeechRecognizer.ERROR_NO_MATCH) {
                            Log.w(TAG, "recogniser error $error")
                        }
                        finish("")
                    }

                    override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
                    override fun onBeginningOfSpeech() = Unit
                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit
                    override fun onEndOfSpeech() = Unit
                    override fun onPartialResults(partial: android.os.Bundle?) = Unit
                    override fun onEvent(type: Int, params: android.os.Bundle?) = Unit
                })

                runCatching { recognizer.startListening(intent(pipe, lang)) }.onFailure {
                    Log.w(TAG, "could not start the recogniser", it)
                    finish("")
                }
            }

            cont.invokeOnCancellation { }
        }

    private fun intent(pcm: ParcelFileDescriptor, lang: String): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, bcp47(lang))
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pcm)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

    /**
     * The WAV, minus its header, on a pipe the recogniser can read.
     *
     * A thread rather than a coroutine because the write blocks until the
     * engine drains it, and it ends when the file does, which is what tells the
     * engine the utterance is over.
     */
    private fun pcmPipe(wav: File): ParcelFileDescriptor {
        val (read, write) = ParcelFileDescriptor.createPipe()
        Thread({
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    wav.inputStream().use { input ->
                        input.skip(HEADER_BYTES)
                        input.copyTo(out, COPY_BUFFER)
                    }
                }
            }.onFailure { Log.w(TAG, "pipe write ended early", it) }
        }, "showhow-asr-pipe").start()
        return read
    }

    private fun best(results: android.os.Bundle): String =
        results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
            .orEmpty()

    companion object {
        private const val TAG = "DeviceAsr"

        /** What AudioRecorder writes. The engine has to be told, it cannot sniff. */
        private const val SAMPLE_RATE = 16_000
        private const val HEADER_BYTES = 44L
        private const val COPY_BUFFER = 32 * 1024

        /** A step is at most a couple of minutes; ten times that is a hang. */
        private const val TIMEOUT_MS = 30_000L

        fun bcp47(lang: String): String = when {
            lang.startsWith("mr") -> "mr-IN"
            lang.startsWith("hi") -> "hi-IN"
            else -> "en-IN"
        }

        /**
         * Whether this phone can recognise speech without a network.
         *
         * API 33 for both the check and the feeding of a file, and the language
         * pack still has to have been downloaded by the system -- so this can be
         * false on a perfectly modern phone, and the app falls back to Vosk
         * rather than pretending.
         */
        fun available(context: Context): Boolean =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                    .getOrDefault(false)
    }
}
