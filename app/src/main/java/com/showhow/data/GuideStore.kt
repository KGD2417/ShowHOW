package com.showhow.data

import com.showhow.core.Policy
import java.io.File

/**
 * A guide is a folder:
 *
 *   guides/<id>/guide.json
 *   guides/<id>/take.wav
 *   guides/<id>/snap0.jpg, snap1.jpg, ...
 *   guides/<id>/step2.wav                (only if a step was re-recorded)
 *
 * Deliberately plain file IO -- no Room, no DataStore. A guide you can drag
 * from one phone to another with a file manager is the sharing story.
 */
class GuideStore(private val root: File) {

    fun dir(id: String): File = File(root, id).apply { mkdirs() }

    fun guideFile(id: String): File = File(dir(id), "guide.json")

    fun takeFile(id: String): File = File(dir(id), "take.wav")

    /**
     * Photos are named by the order they were *snapped*, not by step index --
     * which step ends up owning which snap is decided by time, once, at the end
     * of the take (see core.mapSnapsToSteps). Step.photo carries the name.
     */
    fun snapFile(id: String, snapIndex: Int): File = File(dir(id), "snap$snapIndex.jpg")

    /** Where a re-recorded step's audio lands. Named by step, not by snap. */
    fun stepAudioFile(id: String, stepIndex: Int): File = File(dir(id), "step$stepIndex.wav")

    /**
     * The one photograph that shows what this step should end up looking like,
     * or null.
     *
     * Null and never a placeholder. A step with no usable photo is a real and
     * ordinary thing -- the expert may have had the phone face down, or the
     * frame nearest that step may have been the inside of a pocket -- and the
     * honest answer is to show the instruction and the expert's audio without
     * a picture. A grey box captioned "no photo" is worse than nothing: it
     * takes a third of the screen to say the app has failed, when nothing has.
     *
     * Zero length counts as absent, because a JPEG whose write was interrupted
     * exists on disk and decodes to nothing.
     */
    fun goalImage(id: String, photo: String): File? {
        if (photo.isBlank()) return null
        return File(dir(id), photo).takeIf { it.isFile && it.length() > 0 }
    }

    /**
     * Copy one slice of the take into its own PCM16/16k/mono WAV.
     *
     * For recognisers that hand back sentences with no word clock: the only way
     * to know which words belong to which step is to give the recogniser one
     * step at a time. Written into cacheDir by the caller, never into the guide.
     */
    fun sliceTake(take: File, startMs: Long, endMs: Long, out: File): File? {
        if (!take.exists()) return null
        val from = HEADER + msToBytes(startMs)
        val to = (HEADER + msToBytes(endMs)).coerceAtMost(take.length())
        val bytes = (to - from).coerceAtLeast(0)
        if (bytes < BYTES_PER_MS * 200) return null   // under a fifth of a second
        return runCatching {
            java.io.RandomAccessFile(take, "r").use { raf ->
                raf.seek(from)
                val buf = ByteArray(bytes.toInt())
                raf.readFully(buf)
                out.outputStream().use { o ->
                    o.write(com.showhow.capture.AudioRecorder.wavHeader(bytes))
                    o.write(buf)
                }
            }
            out
        }.getOrNull()
    }

    private fun msToBytes(ms: Long): Long = (ms.coerceAtLeast(0) * BYTES_PER_MS)

    fun ids(): List<String> =
        root.listFiles { f -> f.isDirectory && File(f, "guide.json").exists() }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()

    fun list(): List<Guide> = ids().mapNotNull { load(it) }

    fun load(id: String): Guide? =
        runCatching { Policy.json.decodeFromString(Guide.serializer(), guideFile(id).readText()) }
            .getOrNull()

    fun save(guide: Guide) {
        guideFile(guide.id).writeText(Policy.json.encodeToString(Guide.serializer(), guide))
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    fun newId(): String = "g" + System.currentTimeMillis()

    private companion object {
        const val HEADER = 44L

        /** 16 kHz, mono, 16-bit: 32 bytes of PCM per millisecond. */
        const val BYTES_PER_MS = 32L
    }
}
