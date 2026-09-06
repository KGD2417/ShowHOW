package com.showhow.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * One take, one WAV. PCM16 / 16 kHz / mono, which is what Vosk wants anyway.
 *
 * The header is written by hand because MediaRecorder will not give us raw PCM,
 * and the level stream has to come out of the same read loop that writes the
 * file -- the gate must see exactly the samples that got recorded.
 */
class AudioRecorder {

    private val _levels = MutableSharedFlow<Float>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** dBFS, roughly every 20 ms. What AdaptiveGate consumes. */
    val levels: SharedFlow<Float> = _levels

    private val _pcm = MutableSharedFlow<ShortArray>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * The same audio that is going into the WAV, in half-second copies, for a
     * live recognizer to chew on.
     *
     * Deliberately lossy: the buffer drops the oldest chunk if nothing keeps
     * up. That is safe because this feed is only ever a preview -- the guide
     * is built from a second pass over the finished file, so a dropped chunk
     * costs a few words on screen and nothing on disk. Feeding Vosk on the
     * recorder's own thread would have been lossless and would have risked
     * overrunning the microphone, which costs the take itself.
     */
    val pcm: SharedFlow<ShortArray> = _pcm

    @Volatile
    private var recording = false

    /**
     * Why the last take produced nothing, or null.
     *
     * A microphone another app is holding, or a permission revoked between the
     * tap and the read, is not a crash and it is not a quiet room -- and from
     * the level meter alone those two look identical. The Debug screen shows
     * this; the same pattern as [com.showhow.core.PolicyStore.lastError].
     */
    @Volatile
    var lastError: String? = null
        private set

    fun stop() {
        recording = false
    }

    /**
     * Record until [stop], writing a PCM16/16k/mono WAV.
     *
     * Never throws. The microphone can be held by another app -- the system
     * recogniser we ourselves just used is a candidate -- and the constructor
     * and `startRecording` both raise on that. Thrown out of the coroutine that
     * calls this, it took the whole app down; here it becomes [lastError] and
     * a flat meter, which is a thing the expert can see and act on.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(out: File): Unit = withContext(Dispatchers.IO) {
        lastError = null
        try {
            open(out)
        } catch (t: Throwable) {
            recording = false
            lastError = t.message ?: t::class.java.simpleName
            Log.e(TAG, "the microphone would not open", t)
        }
    }

    private suspend fun open(out: File) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufBytes = maxOf(minBuf, HOP_SAMPLES * 2 * 4)
        // VOICE_RECOGNITION, not MIC. It is the input path the platform tunes
        // for a recognizer -- echo cancellation and noise suppression on, the
        // automatic gain control that pumps a level meter off -- and on the
        // demo phone it is the difference between Vosk hearing a sentence and
        // Vosk hearing a room.
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL, ENCODING, bufBytes,
        )
        val effects = attachEffects(rec.audioSessionId)
        val buf = ShortArray(HOP_SAMPLES)
        val chunk = ShortArray(CHUNK_SAMPLES)
        var chunkFill = 0
        var frames = 0L

        out.parentFile?.mkdirs()
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            raf.write(wavHeader(0))
            try {
                rec.startRecording()
                recording = true
                var badReads = 0
                while (recording && currentCoroutineContext().isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) {
                        // read() returns negative constants for a dead or
                        // invalid recorder, and `continue` on one of those is a
                        // tight loop that never ends: a hot CPU, a flat
                        // battery and a take that never finishes. A handful in
                        // a row is a broken microphone, not a slow one.
                        if (n < 0 && ++badReads >= MAX_BAD_READS) {
                            lastError = "microphone stopped responding (code $n)"
                            Log.e(TAG, "giving up: $badReads reads returned $n")
                            break
                        }
                        continue
                    }
                    badReads = 0
                    _levels.tryEmit(dbfs(buf, n))
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        bytes[i * 2] = (buf[i].toInt() and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((buf[i].toInt() shr 8) and 0xFF).toByte()
                    }
                    raf.write(bytes)
                    frames += n

                    // Copy out in half-second chunks. A per-hop emission would
                    // be fifty allocations a second for a recognizer that wants
                    // them in mouthfuls anyway.
                    var off = 0
                    while (off < n) {
                        val take = minOf(n - off, chunk.size - chunkFill)
                        System.arraycopy(buf, off, chunk, chunkFill, take)
                        chunkFill += take
                        off += take
                        if (chunkFill == chunk.size) {
                            _pcm.tryEmit(chunk.copyOf())
                            chunkFill = 0
                        }
                    }
                }
            } finally {
                recording = false
                // Every one of these guarded, and the header last, because the
                // header is the take. A release() that throws used to skip the
                // rewrite and leave a WAV whose header claims zero bytes --
                // unplayable, untranscribable, and the expert's ninety seconds
                // gone while the file sat there full of audio.
                effects.forEach { runCatching { it.release() } }
                runCatching { rec.stop() }
                runCatching { rec.release() }
                runCatching {
                    // Sizes are only known now, so rewrite the header in place.
                    raf.seek(0)
                    raf.write(wavHeader(frames * 2))
                }.onFailure { Log.e(TAG, "could not finish the wav header", it) }
            }
        }
    }

    /**
     * Turn on whatever cleanup the phone can do in hardware.
     *
     * A hackathon hall is the worst room a recogniser will ever see: a hundred
     * people, no soft surfaces, and an expert two feet from the mic. These run
     * on the audio DSP rather than the CPU and cost nothing.
     *
     * Automatic gain control is deliberately **not** here, and neither is the
     * MIC source that switches it on. AGC would help the recogniser hear a
     * quiet speaker and would wreck the step cutter: it lifts the noise floor
     * during every pause, and a gate whose floor creeps at 0.004 per sample
     * cannot follow that. Pauses would stop looking like pauses, and the take
     * would come back as one enormous step. Cleaner audio is not worth losing
     * the thing that makes this a step-by-step guide.
     *
     * Both are optional silicon. Absent is normal, not an error -- the list is
     * simply shorter and the take still records.
     */
    private fun attachEffects(sessionId: Int): List<android.media.audiofx.AudioEffect> {
        val on = mutableListOf<android.media.audiofx.AudioEffect>()
        if (NoiseSuppressor.isAvailable()) {
            runCatching { NoiseSuppressor.create(sessionId) }.getOrNull()
                ?.also { it.enabled = true; on += it }
        }
        if (AcousticEchoCanceler.isAvailable()) {
            runCatching { AcousticEchoCanceler.create(sessionId) }.getOrNull()
                ?.also { it.enabled = true; on += it }
        }
        Log.i(TAG, "audio effects on: " + on.joinToString { it.javaClass.simpleName })
        return on
    }

    companion object {
        private const val TAG = "AudioRecorder"

        /**
         * Consecutive negative reads before the microphone is declared dead.
         *
         * Not a tunable: below a handful it would give up on a transient, and
         * any larger number is still a fraction of a second of a loop that is
         * doing nothing.
         */
        private const val MAX_BAD_READS = 20

        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 20 ms at 16 kHz. Fifty gate updates a second. */
        const val HOP_SAMPLES = 320

        /** Half a second at 16 kHz. What the live recognizer is fed. */
        const val CHUNK_SAMPLES = 8_000

        fun dbfs(buf: ShortArray, n: Int): Float {
            if (n <= 0) return Float.NEGATIVE_INFINITY
            var sum = 0.0
            for (i in 0 until n) {
                val v = buf[i] / 32768.0
                sum += v * v
            }
            val rms = sqrt(sum / n)
            // A dead mic gives rms 0, and log10 of that is -Infinity.
            // AdaptiveGate is built to eat that, so pass it on honestly.
            return (20.0 * log10(rms)).toFloat()
        }

        /** Canonical 44-byte RIFF/WAVE header for PCM16 mono. */
        fun wavHeader(dataBytes: Long): ByteArray {
            val b = ByteArray(44)
            fun ascii(off: Int, s: String) {
                s.forEachIndexed { i, c -> b[off + i] = c.code.toByte() }
            }
            fun le32(off: Int, v: Long) {
                for (i in 0 until 4) b[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
            }
            fun le16(off: Int, v: Int) {
                for (i in 0 until 2) b[off + i] = ((v shr (8 * i)) and 0xFF).toByte()
            }
            ascii(0, "RIFF")
            le32(4, 36 + dataBytes)
            ascii(8, "WAVE")
            ascii(12, "fmt ")
            le32(16, 16)
            le16(20, 1)
            le16(22, 1)
            le32(24, SAMPLE_RATE.toLong())
            le32(28, (SAMPLE_RATE * 2).toLong())
            le16(32, 2)
            le16(34, 16)
            ascii(36, "data")
            le32(40, dataBytes)
            return b
        }
    }
}
