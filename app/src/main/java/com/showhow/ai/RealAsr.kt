package com.showhow.ai

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Real Hindi/Marathi transcription, on device, from a Vosk small model.
 *
 * The model is not in the repo and never will be -- it is unzipped onto the
 * phone under filesDir/models/. Everything here is therefore written around
 * the model being absent: [orNoop] hands back [NoopAsr] instead, and the
 * production path returns an empty word list rather than canned data. A demo
 * that quietly falls back to FakeAsr is a demo that lies to the jury.
 *
 * Lifecycle is the same ladder every model in this app uses:
 * LOAD -> VERIFY -> INIT -> INFER -> RELEASE. Vosk is Kaldi, so it is CPU
 * only; there is no NNAPI or GPU rung to fall down. MediaPipe is where that
 * ladder has more than one step.
 */
class VoskAsr(private val modelDir: File) : Asr, AutoCloseable {

    @Volatile
    private var model: Model? = null

    /** One failed load is enough. Retrying a missing model on every take is noise. */
    @Volatile
    private var dead = false

    override suspend fun transcribe(wav: File): List<Word> = withContext(Dispatchers.IO) {
        val m = model() ?: return@withContext emptyList()
        val started = System.currentTimeMillis()
        val words = runCatching { run(m, wav) }.getOrElse {
            Log.w(TAG, "transcribe failed for ${wav.name}", it)
            return@withContext emptyList()
        }
        Log.i(TAG, "infer ${wav.name}: ${words.size} words in ${System.currentTimeMillis() - started} ms")
        words
    }

    private fun run(m: Model, wav: File): List<Word> {
        val out = mutableListOf<Word>()
        RandomAccessFile(wav, "r").use { raf ->
            val dataStart = pcmOffset(raf)
            if (dataStart < 0) {
                Log.w(TAG, "${wav.name} is not a PCM wav we can read")
                return emptyList()
            }
            raf.seek(dataStart)
            Recognizer(m, SAMPLE_RATE).use { rec ->
                rec.setWords(true)
                val bytes = ByteArray(CHUNK_SAMPLES * 2)
                val shorts = ShortArray(CHUNK_SAMPLES)
                while (true) {
                    val read = raf.read(bytes)
                    if (read < 2) break
                    val n = read / 2
                    for (i in 0 until n) {
                        // The recorder writes little-endian PCM16.
                        shorts[i] = (((bytes[i * 2 + 1].toInt() shl 8)) or
                            (bytes[i * 2].toInt() and 0xFF)).toShort()
                    }
                    if (rec.acceptWaveForm(shorts, n)) out += parse(rec.result)
                }
                out += parse(rec.finalResult)
            }
        }
        return out
    }

    /** LOAD + VERIFY + INIT, once, lazily -- a small model still costs a second or two. */
    private fun model(): Model? {
        model?.let { return it }
        if (dead) return null
        synchronized(this) {
            model?.let { return it }
            if (dead) return null
            if (!verify(modelDir)) {
                dead = true
                return null
            }
            val started = System.currentTimeMillis()
            // UnsatisfiedLinkError on a phone without the native lib is an
            // Error, not an Exception, so this has to catch Throwable.
            val loaded = runCatching { Model(modelDir.absolutePath) }.getOrElse {
                Log.w(TAG, "vosk model at $modelDir would not load, ASR is off", it)
                dead = true
                return null
            }
            Log.i(TAG, "loaded vosk model in ${System.currentTimeMillis() - started} ms")
            model = loaded
            return loaded
        }
    }

    /** RELEASE. Called from ShowHowViewModel.onCleared. */
    override fun close() {
        synchronized(this) {
            runCatching { model?.close() }
            model = null
            dead = true
        }
    }

    private fun parse(json: String): List<Word> {
        val arr = runCatching {
            Json.parseToJsonElement(json).jsonObject["result"]?.jsonArray
        }.getOrNull() ?: return emptyList()
        return arr.mapNotNull { e ->
            val o = runCatching { e.jsonObject }.getOrNull() ?: return@mapNotNull null
            val text = o["word"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Word(
                text = text,
                // Vosk reports seconds from the start of the stream, which is
                // the same clock the sample log and the step ranges use.
                startMs = ((o["start"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
                endMs = ((o["end"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong(),
                confidence = o["conf"]?.jsonPrimitive?.floatOrNull ?: 1f,
            )
        }
    }

    /**
     * Offset of the PCM payload, or -1.
     *
     * Our own recorder always writes a canonical 44-byte header, but a take
     * copied off another phone may not, and reading two bytes into the audio
     * would transcribe noise silently rather than fail loudly.
     */
    private fun pcmOffset(raf: RandomAccessFile): Long {
        val head = ByteArray(12)
        if (raf.read(head) < 12) return -1
        if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return -1
        var pos = 12L
        val end = raf.length()
        while (pos + 8 <= end) {
            raf.seek(pos)
            val hdr = ByteArray(8)
            if (raf.read(hdr) < 8) return -1
            val id = String(hdr, 0, 4)
            val size = (0 until 4).fold(0L) { acc, i ->
                acc or ((hdr[4 + i].toLong() and 0xFF) shl (8 * i))
            }
            if (id == "data") return pos + 8
            pos += 8 + size + (size and 1L)   // chunks are word aligned
        }
        return -1
    }

    companion object {
        private const val TAG = "VoskAsr"

        /** Matches AudioRecorder. Anything else and the timings come out wrong. */
        private const val SAMPLE_RATE = 16_000f

        /** A quarter second per accept call. Big enough to be cheap, small enough to stream. */
        private const val CHUNK_SAMPLES = 4000

        /** Where the model is unzipped on the phone. Gitignored, never committed. */
        const val MODEL_DIR = "models/vosk"

        /** A half-unpacked model directory loads and then segfaults, so check first. */
        fun verify(dir: File): Boolean {
            val ok = dir.isDirectory && File(dir, "conf/model.conf").isFile
            if (!ok) Log.w(TAG, "no usable vosk model at $dir")
            return ok
        }

        /** The real recogniser if the model is on the phone, silence if it is not. */
        fun orNoop(dir: File): Asr = if (verify(dir)) VoskAsr(dir) else NoopAsr
    }
}

/**
 * What the production path falls back to. Returns nothing, which shows up as an
 * empty transcript and a step cutter running on pauses alone -- both honest.
 */
object NoopAsr : Asr {
    override suspend fun transcribe(wav: File): List<Word> = emptyList()
}
