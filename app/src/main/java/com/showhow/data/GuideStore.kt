package com.showhow.data

import com.showhow.ai.captionOf
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
class GuideStore(
    private val root: File,
    /**
     * Where a failed write is reported.
     *
     * A callback and not a `Log.e`, for the same reason
     * [com.showhow.core.PolicyStore] takes one: this class holds the only copy
     * of an expert's work and it must stay testable on the JVM, which means it
     * must stay free of `android.*`. The ViewModel passes the logger.
     */
    private val onError: (String, Throwable) -> Unit = { _, _ -> },
) {

    fun dir(id: String): File = File(root, id).apply { mkdirs() }

    fun guideFile(id: String): File = File(dir(id), "guide.json")

    /**
     * The last version an expert verified, kept beside the working copy.
     *
     * Two files because the expert edits after verifying -- that is the whole
     * point of a Review screen -- and a half-finished edit must not be what a
     * learner is following in the meantime. Saving a draft writes only
     * guide.json; this file changes on verification alone, so the Player always
     * has a checked guide to fall back to if one was ever checked.
     */
    fun verifiedFile(id: String): File = File(dir(id), "guide.verified.json")

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

    /**
     * @return false when the guide could not be written. The caller decides
     *   what to tell the expert; what it must not do is assume it worked.
     */
    fun save(guide: Guide): Boolean =
        writeAtomically(guideFile(guide.id), Policy.json.encodeToString(Guide.serializer(), guide))

    /**
     * Replace the detector's captions in every copy of this guide, keyed by
     * photograph.
     *
     * Keyed by photo name and not by step index because that is the thing that
     * cannot drift: which step owns which snap is decided once, at the end of
     * the take, and the working copy and the verified copy may have been cut
     * differently since.
     *
     * Both files, each read and written on its own, so re-captioning a
     * verified guide cannot quietly overwrite a draft the expert is still
     * editing -- or stamp a fresh verification on something nobody rechecked.
     * A caption is the detector's own observation about a JPEG; it is not a
     * claim the expert made, so refreshing it does not un-verify anything.
     */
    fun recaption(id: String, byPhoto: Map<String, List<String>>) {
        for (file in listOf(guideFile(id), verifiedFile(id))) {
            if (!file.isFile) continue
            val guide = runCatching {
                Policy.json.decodeFromString(Guide.serializer(), file.readText())
            }.getOrNull() ?: continue
            val patched = guide.copy(
                steps = guide.steps.map { s ->
                    byPhoto[s.photo]?.let { s.copy(caption = captionOf(it), objects = it) } ?: s
                },
            )
            writeAtomically(file, Policy.json.encodeToString(Guide.serializer(), patched))
        }
    }

    /**
     * Save, and record this exact version as the one the expert stood behind.
     *
     * Both files, so the working copy and the verified copy agree at the moment
     * of verification and only drift once someone edits again.
     */
    fun saveVerified(guide: Guide): Boolean {
        val stamped = guide.copy(verifiedAt = System.currentTimeMillis())
        val draft = save(stamped)
        val verified = writeAtomically(
            verifiedFile(guide.id),
            Policy.json.encodeToString(Guide.serializer(), stamped),
        )
        return draft && verified
    }

    /**
     * What a learner should be given: the verified guide if one was ever
     * verified, otherwise the working copy.
     *
     * The Player calls this and never [load]. A draft is a fine thing to follow
     * when it is all there is -- an unverified guide still beats no guide -- but
     * it must never quietly replace a version an expert checked just because
     * someone started editing and walked away.
     */
    fun loadForLearner(id: String): Guide? =
        runCatching { Policy.json.decodeFromString(Guide.serializer(), verifiedFile(id).readText()) }
            .getOrNull()
            ?: load(id)

    fun delete(id: String) {
        File(root, id).deleteRecursively()
    }

    /**
     * Write a file so that a failure loses the *new* version rather than the
     * old one.
     *
     * `writeText` truncates first and then writes. A phone that runs out of
     * space, or is killed, halfway through leaves a half a JSON object on
     * disk -- and a guide.json that will not parse is a guide that has
     * disappeared from the library, take, photographs and all. The expert's
     * recording is still in the folder and nothing in the app can reach it.
     *
     * So: write beside it, then rename. Rename within a directory is atomic on
     * every filesystem Android ships, so at every instant guide.json is either
     * entirely the old version or entirely the new one.
     *
     * @return false if nothing was written. Never throws -- a full disk is a
     *   thing to tell someone about, not a thing to crash on.
     */
    private fun writeAtomically(file: File, text: String): Boolean = runCatching {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (tmp.renameTo(file)) return@runCatching true
        // Some filesystems refuse a rename onto an existing file. Falling back
        // to a plain overwrite reopens the truncation window, so it is only
        // done when the safe path is unavailable -- and the temp file is left
        // behind on failure, which is the only surviving copy of the new
        // version if the overwrite dies.
        file.writeText(text)
        tmp.delete()
        true
    }.getOrElse {
        onError("could not write ${file.name}", it)
        false
    }

    fun newId(): String = "g" + System.currentTimeMillis()

    private companion object {
        const val HEADER = 44L

        /** 16 kHz, mono, 16-bit: 32 bytes of PCM per millisecond. */
        const val BYTES_PER_MS = 32L
    }
}
