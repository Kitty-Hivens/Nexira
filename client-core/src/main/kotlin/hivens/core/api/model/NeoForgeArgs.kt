package hivens.core.api.model

import kotlinx.serialization.Serializable

/**
 * The NeoForge launch coordinates a server may pin for its clients, as the
 * SmartyCraft dashboard declares them.
 *
 * Each field is one `--fml.<name>` argument. A field left null defers to what
 * the launcher detects from the installed client, which is the normal case --
 * a server pins one of these only when its build needs a version the bundle's
 * detector would not arrive at on its own.
 *
 * Named fields rather than a `Map<String, String>`: these four are the whole
 * vocabulary the command builder speaks, and a map made every one of them a
 * string key spelled correctly by hope, on a model that is also written to
 * disk.
 */
@Serializable
data class NeoForgeArgs(
    val neoForgeVersion: String? = null,
    val fmlVersion: String? = null,
    val mcVersion: String? = null,
    val neoFormVersion: String? = null,
) {
    /**
     * The pinned arguments as `name -> value`, ready to merge over the
     * launcher's own detection. Absent and blank fields are both left out; a
     * blank would emit `--fml.neoForgeVersion ` with nothing after it.
     */
    fun asFmlArgs(): Map<String, String> = buildMap {
        neoForgeVersion?.takeIf { it.isNotBlank() }?.let { put("neoForgeVersion", it) }
        fmlVersion?.takeIf { it.isNotBlank() }?.let { put("fmlVersion", it) }
        mcVersion?.takeIf { it.isNotBlank() }?.let { put("mcVersion", it) }
        neoFormVersion?.takeIf { it.isNotBlank() }?.let { put("neoFormVersion", it) }
    }
}
