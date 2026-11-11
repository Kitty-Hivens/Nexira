package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data model (DTO) for the client file manifest.
 */
@Serializable
data class FileManifest(
    @SerialName("directories")
    val directories: Map<String, FileManifest> = emptyMap(),

    @SerialName("files")
    val files: Map<String, FileData> = emptyMap()
)

/**
 * Flattens the directory tree into a single `path -> data` map, joining
 * nested keys with `/` (`mods/foo.jar`, `config/mod/recipes.cfg`) and no
 * leading or trailing slash. Insertion order is preserved so callers that
 * take the first match over the flattened entries get a deterministic pick.
 */
fun FileManifest.flatten(): Map<String, FileData> {
    val result = LinkedHashMap<String, FileData>()
    fun walk(manifest: FileManifest, prefix: String) {
        manifest.files.forEach { (name, data) ->
            result[if (prefix.isEmpty()) name else "$prefix/$name"] = data
        }
        manifest.directories.forEach { (name, sub) ->
            walk(sub, if (prefix.isEmpty()) name else "$prefix/$name")
        }
    }
    walk(this, "")
    return result
}

/**
 * Inverse of [flatten]: rebuild the nested tree from flat `a/b/c` -> [FileData]
 * entries. Every `path -> data` pair round-trips (`flatten(fileManifestOf(m))`
 * reproduces `m`'s entries); iteration order may differ because [flatten] emits
 * root files before recursing into subdirectories. Empty path segments (a
 * leading, trailing, or doubled `/`) are dropped so a stray separator cannot
 * create a nameless node.
 */
fun fileManifestOf(entries: Map<String, FileData>): FileManifest {
    val files = LinkedHashMap<String, FileData>()
    val subtrees = LinkedHashMap<String, LinkedHashMap<String, FileData>>()
    for ((path, data) in entries) {
        val segments = path.split('/').filter { it.isNotEmpty() }
        if (segments.isEmpty()) continue
        if (segments.size == 1) {
            files[segments[0]] = data
        } else {
            subtrees.getOrPut(segments[0]) { LinkedHashMap() }[segments.drop(1).joinToString("/")] = data
        }
    }
    return FileManifest(
        directories = subtrees.mapValues { (_, sub) -> fileManifestOf(sub) },
        files = files,
    )
}
