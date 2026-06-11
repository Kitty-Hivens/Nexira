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
