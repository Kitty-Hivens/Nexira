package hivens.launcher.runtime

import kotlinx.serialization.Serializable

/**
 * Minimal models of Mojang's official launcher-meta wire formats -- only
 * the fields the runtime provisioner consumes. The shared [kotlinx.serialization.json.Json]
 * is configured with `ignoreUnknownKeys = true`, so the dozens of fields
 * we omit (arguments, compliance level, logging config, java version,
 * native classifiers, ...) parse and are discarded.
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
 * Only [artifact] is taken -- the main jar that belongs on the classpath.
 * Native classifiers (lwjgl `.so`/`.dll`) are intentionally ignored here;
 * [hivens.launcher.component.EnvironmentPreparer] sources natives from
 * Mojang's CDN separately, per instance.
 */
@Serializable
data class MojangLibraryDownloads(
    val artifact: MojangArtifact? = null,
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
