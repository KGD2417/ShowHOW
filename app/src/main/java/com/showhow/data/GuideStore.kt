package com.showhow.data

import com.showhow.core.Policy
import java.io.File

/**
 * A guide is a folder:
 *
 *   guides/<id>/guide.json
 *   guides/<id>/take.wav
 *   guides/<id>/s1.jpg, s2.jpg, ...
 *
 * Deliberately plain file IO -- no Room, no DataStore. A guide you can drag
 * from one phone to another with a file manager is the sharing story.
 */
class GuideStore(private val root: File) {

    fun dir(id: String): File = File(root, id).apply { mkdirs() }

    fun guideFile(id: String): File = File(dir(id), "guide.json")

    fun takeFile(id: String): File = File(dir(id), "take.wav")

    /** Steps are one-based on disk: s1.jpg is step index 0. */
    fun photoFile(id: String, stepIndex: Int): File = File(dir(id), "s${stepIndex + 1}.jpg")

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
}
