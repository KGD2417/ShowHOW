package com.showhow.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.log10
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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

    @Volatile
    private var recording = false

    fun stop() {
        recording = false
    }

    @SuppressLint("MissingPermission")
    suspend fun record(out: File): Unit = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        val bufBytes = maxOf(minBuf, HOP_SAMPLES * 2 * 4)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, bufBytes,
        )
        val buf = ShortArray(HOP_SAMPLES)
        var frames = 0L

        out.parentFile?.mkdirs()
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            raf.write(wavHeader(0))
            try {
                rec.startRecording()
                recording = true
                while (recording && this@withContext.isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    _levels.tryEmit(dbfs(buf, n))
                    val bytes = ByteArray(n * 2)
                    for (i in 0 until n) {
                        bytes[i * 2] = (buf[i].toInt() and 0xFF).toByte()
                        bytes[i * 2 + 1] = ((buf[i].toInt() shr 8) and 0xFF).toByte()
                    }
                    raf.write(bytes)
                    frames += n
                }
            } finally {
                recording = false
                runCatching { rec.stop() }
                rec.release()
                // Sizes are only known now, so rewrite the header in place.
                raf.seek(0)
                raf.write(wavHeader(frames * 2))
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT

        /** 20 ms at 16 kHz. Fifty gate updates a second. */
        const val HOP_SAMPLES = 320

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
