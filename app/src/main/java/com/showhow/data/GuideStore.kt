package com.showhow.data

import com.showhow.core.Policy
import java.io.File

/**
 * A guide is a folder:
 *
 *   guides/<id>/guide.json
 *   guides/<id>/take.wav
 *   guides/<id>/snap0.jpg, snap1.jpg, ...
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
