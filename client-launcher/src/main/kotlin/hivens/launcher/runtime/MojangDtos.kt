package hivens.launcher.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Minimal models of Mojang's official launcher-meta wire formats -- only
 * the fields the runtime provisioner consumes. The shared [kotlinx.serialization.json.Json]
 * is configured with `ignoreUnknownKeys = true`, so the dozens of fields
 * we omit (compliance level, logging config, java version, native
 * classifiers, ...) parse and are discarded.
 */

// -- version_manifest_v2.json (piston-meta) --------------------------------

@Serializable
data class MojangVersionManifest(
    val versions: List<MojangVersionRef> = emptyList(),
)

@Serializable
data class MojangVersionRef(
    val id: String,
    val url: String,
)

// -- the per-version json (e.g. 1.12.2.json) -------------------------------

@Serializable
data class MojangVersion(
    val assetIndex: MojangAssetIndexRef,
    val downloads: MojangDownloads,
    val libraries: List<MojangLibrary> = emptyList(),
    /**
     * Modern-format launch args (1.13+). Null on legacy versions, which
     * instead carry a flat `minecraftArguments` string. A modern loader
     * overlay inheritsFrom this vanilla base, so the merged jvm args are
     * vanilla's (here) + the loader's.
     */
    val arguments: MojangArguments? = null,
    /**
     * Mojang's declared Java major (present 1.17+; absent on legacy versions
     * -> null, where the launcher falls back to its version heuristic).
     * Authoritative when present -- we should not guess what Mojang already states.
     */
    val javaVersion: MojangJavaVersion? = null,
)

@Serializable
data class MojangJavaVersion(
    val majorVersion: Int,
)

/**
 * The modern `arguments` block: `jvm` and `game` are each a heterogeneous
 * list where an entry is either a bare string token or a conditional object
 * `{rules:[...], value: String|[String]}`. [flattenArguments] resolves the
 * conditionals for the current platform.
 */
@Serializable
data class MojangArguments(
    val jvm: List<JsonElement> = emptyList(),
    val game: List<JsonElement> = emptyList(),
)

@Serializable
data class MojangAssetIndexRef(
    val id: String,
    val sha1: String,
    val size: Long = 0,
    val totalSize: Long = 0,
    val url: String,
)

@Serializable
data class MojangDownloads(
    val client: MojangArtifact,
)

/**
 * A single downloadable file. Library artifacts carry a maven-relative
 * [path]; the client jar has no path (placed at a synthetic coord by the
 * provisioner), so [path] defaults to empty.
 */
@Serializable
data class MojangArtifact(
    val path: String = "",
    val sha1: String,
    val size: Long = 0,
    val url: String = "",
)

@Serializable
data class MojangLibrary(
    val name: String,
    val downloads: MojangLibraryDownloads? = null,
    val rules: List<MojangRule> = emptyList(),
)

/**
 * [artifact] is the main jar that belongs on the classpath. [classifiers]
 * carries the platform-native jars (lwjgl `.so`/`.dll`/`.dylib`) on pre-1.19
 * versions, keyed by classifier (`natives-linux`, `natives-windows`,
 * `natives-osx`, ...); 1.19+ instead lists each native as its own library
 * whose `name` ends in a `natives-<os>` classifier. The provisioner picks the
 * host-matching ones so [hivens.launcher.component.EnvironmentPreparer]
 * extracts the exact LWJGL version the classpath references -- a fixed
 * fallback version would mismatch the bindings and LWJGL refuses to start.
 */
@Serializable
data class MojangLibraryDownloads(
    val artifact: MojangArtifact? = null,
    val classifiers: Map<String, MojangArtifact> = emptyMap(),
)

@Serializable
data class MojangRule(
    val action: String,
    val os: MojangOs? = null,
)

@Serializable
data class MojangOs(
    val name: String? = null,
)

/**
 * Whether a library with these [rules] belongs on [mojangOs].
 *
 * Rules are evaluated in order and the last matching one wins, which is how a
 * version json expresses "allow everywhere, disallow on osx" and the reverse.
 * No rules at all means the library is unconditional.
 *
 * Anyone reading a version json has to apply this, not just the classpath
 * builder: a mac-only entry like `ca.weblite:java-objc-bridge` is absent from a
 * Windows install by design, and treating its absence as a failed install
 * blocks the pack over a file that must not be there.
 */
fun libraryRulesAllow(rules: List<MojangRule>, mojangOs: String): Boolean {
    if (rules.isEmpty()) return true
    var allowed = false
    for (rule in rules) {
        val matches = rule.os?.name?.let { it == mojangOs } ?: true
        if (matches) allowed = rule.action == "allow"
    }
    return allowed
}

// -- the asset index (e.g. 1.12.json) --------------------------------------

@Serializable
data class MojangAssetIndex(
    val objects: Map<String, MojangAssetObject> = emptyMap(),
)

@Serializable
data class MojangAssetObject(
    val hash: String,
    val size: Long = 0,
)

// -- modern argument flattening --------------------------------------------

/**
 * Resolves a modern `arguments.jvm` / `arguments.game` list to concrete
 * tokens for [mojangOs] (a [hivens.core.platform.Platform.mojang] value).
 * `${...}` placeholders are PRESERVED -- the command builder substitutes
 * them once the final paths are known. A bare string passes through; a
 * conditional object contributes its value only when its rules allow.
 * Feature-gated entries (demo, custom resolution, quick-play) are dropped:
 * we enable no optional features, so their rules never apply.
 */
fun flattenArguments(elements: List<JsonElement>, mojangOs: String): List<String> {
    val out = ArrayList<String>()
    for (el in elements) {
        when (el) {
            is JsonPrimitive -> if (el.isString) out += el.content
            is JsonObject -> {
                val rules = el["rules"]?.jsonArray
                if (rules != null && !argumentRulesAllow(rules, mojangOs)) continue
                when (val value = el["value"]) {
                    is JsonPrimitive -> if (value.isString) out += value.content
                    is JsonArray -> value.forEach { if (it is JsonPrimitive && it.isString) out += it.content }
                    else -> {}
                }
            }
            else -> {}
        }
    }
    return out
}

/**
 * Last-match-wins rule evaluation for a modern argument entry: a rule with
 * no `os` matches any platform; an `os.name` must equal [mojangOs]. Any rule
 * carrying a `features` block is skipped (we enable no features, so it can
 * neither grant nor deny), which drops feature-only-gated args entirely.
 */
private fun argumentRulesAllow(rules: JsonArray, mojangOs: String): Boolean {
    var allowed = false
    for (rule in rules) {
        val obj = rule as? JsonObject ?: continue
        if (obj.containsKey("features")) continue
        val action = (obj["action"] as? JsonPrimitive)?.content ?: "allow"
        val osName = (obj["os"] as? JsonObject)?.get("name")?.jsonPrimitive?.content
        val matches = osName == null || osName == mojangOs
        if (matches) allowed = action == "allow"
    }
    return allowed
}
