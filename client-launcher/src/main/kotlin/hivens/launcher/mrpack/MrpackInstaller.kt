package hivens.launcher.mrpack

import hivens.core.api.HttpClientProvider
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.InstanceRuntime
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.util.sha1Of
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipFile

/**
 * Installs a Modrinth modpack (`.mrpack`) into a launcher instance -- the
 * file-based sibling of [hivens.launcher.smrt.SmrtSyncService]. Both end at a
 * [PackInstance] that launches through the same runtime resolution.
 *
 * A `.mrpack` is a zip with `modrinth.index.json` (mc + loader dependencies and
 * a list of files to fetch by URL + hash) plus `overrides/` and
 * `client-overrides/` trees copied verbatim into the instance. The loader
 * dependency key is mapped to this project's [hivens.launcher.runtime.loader.LoaderRegistry]
 * id and fed to [RuntimeProvisioner.ensureRuntime].
 *
 * Security: every `files[].path` and override entry is resolved under the
 * instance dir and rejected if it escapes (zip-slip / traversal). Downloaded
 * bytes are sha1-verified against the index when a sha1 is present, so a
 * tampered mirror can't substitute content the index didn't pin.
 */
class MrpackInstaller(
    private val clientProvider: HttpClientProvider,
    private val json: Json,
    private val javaManager: IJavaManager,
    private val runtimeProvisioner: RuntimeProvisioner,
    private val repository: IPackRepository,
    private val dataDir: Path,
) {
    private val log = LoggerFactory.getLogger(MrpackInstaller::class.java)

    suspend fun install(
        mrpack: Path,
        source: MrpackSource? = null,
        progress: (current: Int, total: Int, filename: String) -> Unit = { _, _, _ -> },
    ): PackInstance = withContext(Dispatchers.IO) {
        ZipFile(mrpack.toFile()).use { zip ->
            val indexEntry = zip.getEntry(INDEX_NAME)
                ?: throw IOException("not a .mrpack: no $INDEX_NAME at the archive root")
            val index = json.decodeFromString(
                MrpackIndex.serializer(),
                zip.getInputStream(indexEntry).readBytes().decodeToString(),
            )
            val mcVersion = index.dependencies[DEP_MINECRAFT]
                ?: throw IOException("mrpack has no '$DEP_MINECRAFT' dependency")
            val (loaderName, loaderVersion) = resolveLoader(index.dependencies)
            val displayName = index.name.ifBlank { "Imported pack" }

            val instanceId = UUID.randomUUID().toString()
            val instanceDirName = sanitize("$displayName-$instanceId")
            val clientDir = dataDir.resolve("instances").resolve(instanceDirName)
            Files.createDirectories(clientDir)
            log.info("mrpack: installing '{}' ({} {} on {}) -> {}", displayName, loaderName ?: "vanilla", loaderVersion, mcVersion, clientDir)

            // 1. Files fetched by URL. Skip anything the pack marks unsupported
            //    on the client; optional client content installs by default
            //    (per-entry opt-out is the optional-mods system's job later).
            val files = index.files.filter { it.env?.client != ENV_UNSUPPORTED }
            files.forEachIndexed { i, file ->
                progress(i + 1, files.size, file.path)
                downloadFile(file, clientDir)
            }

            // 2. Verbatim trees. client-overrides wins over overrides on a clash.
            extractOverrides(zip, OVERRIDES, clientDir)
            extractOverrides(zip, CLIENT_OVERRIDES, clientDir)

            // 3. Canonical runtime into the shared roots (idempotent).
            runtimeProvisioner.ensureRuntime(mcVersion, loaderName, loaderVersion, progress)

            // Provenance: a Modrinth install stamps origin/id/version from [source]
            // so the update flow can find newer versions; a plain local import
            // (source == null) stays Local with an id derived from the pack name.
            val pinned = (source?.version ?: index.versionId).ifBlank { null }
            val instance = PackInstance(
                id = instanceId,
                packRef = PackReference(
                    origin = source?.origin ?: PackOrigin.Local,
                    id = source?.id ?: sanitize(displayName).lowercase(),
                    version = pinned,
                ),
                displayName = displayName,
                instanceDirName = instanceDirName,
                createdAtEpoch = Instant.now().epochSecond,
                pinnedPackVersion = pinned,
                runtime = InstanceRuntime(),  // heap left to the global adaptive sizer
                cachedManifest = CachedManifestSnapshot(
                    minecraftVersion = mcVersion,
                    loaderName = loaderName ?: "vanilla",
                    loaderVersion = loaderVersion,
                    javaMajor = javaManager.detectJavaVersion(mcVersion),
                ),
            )
            repository.put(instance)
            log.info("mrpack: registered instance {}", instanceId)
            instance
        }
    }

    /**
     * Download a `.mrpack` from [url] to a temp file, install it, then delete
     * the temp. [source] stamps the instance's origin/id/version so the update
     * flow can find newer versions later -- this is the Modrinth catalogue
     * install path. The download uses the same streaming + close pattern as the
     * per-file fetch so a large pack archive never lands wholly in memory.
     */
    suspend fun installFromUrl(
        url: String,
        source: MrpackSource,
        progress: (current: Int, total: Int, filename: String) -> Unit = { _, _, _ -> },
    ): PackInstance = withContext(Dispatchers.IO) {
        val tmp = Files.createTempFile("nexira-mrpack-", ".mrpack")
        try {
            clientProvider.current.prepareGet(url).execute { resp ->
                if (!resp.status.isSuccess()) throw IOException("GET $url -> HTTP ${resp.status}")
                FileOutputStream(tmp.toFile()).use { out -> resp.bodyAsChannel().copyTo(out) }
            }
            install(tmp, source, progress)
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /**
     * Maps the index `dependencies` to (registry loader id, version). Modrinth
     * names the loader entry `forge` / `neoforge` / `fabric-loader` /
     * `quilt-loader`; this project's registry uses `forge` / `neoforge` /
     * `fabric` / `quilt`. Returns (null, "") for a vanilla pack (no loader dep).
     */
    internal fun resolveLoader(dependencies: Map<String, String>): Pair<String?, String> {
        for ((depKey, loaderId) in LOADER_KEYS) {
            dependencies[depKey]?.let { return loaderId to it }
        }
        return null to ""
    }

    private suspend fun downloadFile(file: MrpackFile, clientDir: Path) {
        val dest = safeResolve(clientDir, file.path)
        if (file.downloads.isEmpty()) throw IOException("mrpack file ${file.path} has no download URL")
        val sha1 = file.hashes[HASH_SHA1]
        Files.createDirectories(dest.parent)
        val tmp = dest.resolveSibling("${dest.fileName}.part")

        var lastError: Exception? = null
        for (url in file.downloads) {
            try {
                clientProvider.current.prepareGet(url).execute { resp ->
                    if (!resp.status.isSuccess()) throw IOException("GET $url -> HTTP ${resp.status}")
                    FileOutputStream(tmp.toFile()).use { out -> resp.bodyAsChannel().copyTo(out) }
                }
                if (sha1 != null) {
                    val actual = sha1Of(tmp)
                    if (!actual.equals(sha1, ignoreCase = true)) {
                        throw IOException("sha1 mismatch for ${file.path}: expected $sha1, got $actual")
                    }
                }
                moveAtomic(tmp, dest)
                return
            } catch (e: Exception) {
                lastError = e
                runCatching { Files.deleteIfExists(tmp) }
                log.warn("mrpack: download {} from {} failed: {}", file.path, url, e.message)
            }
        }
        throw IOException("all downloads failed for ${file.path}", lastError)
    }

    private fun extractOverrides(zip: ZipFile, prefix: String, clientDir: Path) {
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
            val relative = entry.name.removePrefix(prefix)
            if (relative.isEmpty()) continue
            val dest = safeResolve(clientDir, relative)
            Files.createDirectories(dest.parent)
            zip.getInputStream(entry).use { input ->
                Files.copy(input, dest, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    /** Resolves [relative] under [base], rejecting traversal that escapes it. */
    internal fun safeResolve(base: Path, relative: String): Path {
        val resolved = base.resolve(relative).normalize()
        if (!resolved.startsWith(base)) {
            throw SecurityException("mrpack entry escapes the instance dir: $relative")
        }
        return resolved
    }

    private fun sanitize(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)

    private fun moveAtomic(tmp: Path, dest: Path) {
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val INDEX_NAME = "modrinth.index.json"
        const val OVERRIDES = "overrides/"
        const val CLIENT_OVERRIDES = "client-overrides/"
        const val DEP_MINECRAFT = "minecraft"
        const val ENV_UNSUPPORTED = "unsupported"
        const val HASH_SHA1 = "sha1"

        /** Modrinth dependency key -> LoaderRegistry id, checked in this order. */
        val LOADER_KEYS = linkedMapOf(
            "neoforge" to "neoforge",
            "forge" to "forge",
            "fabric-loader" to "fabric",
            "quilt-loader" to "quilt",
        )
    }
}

/**
 * Provenance for an installed `.mrpack`: the [PackOrigin] plus a source-local id
 * and version stamped onto the resulting instance. Passed for a Modrinth install
 * (origin Modrinth, id = project id); a plain local import omits it and stays
 * Local with an id derived from the pack name.
 */
data class MrpackSource(
    val origin: PackOrigin,
    val id: String,
    val version: String?,
)

/** The launch-relevant subset of a `.mrpack` `modrinth.index.json`. */
@Serializable
data class MrpackIndex(
    val formatVersion: Int = 1,
    val game: String = "minecraft",
    val versionId: String = "",
    val name: String = "",
    val summary: String = "",
    val files: List<MrpackFile> = emptyList(),
    val dependencies: Map<String, String> = emptyMap(),
)

@Serializable
data class MrpackFile(
    val path: String,
    val hashes: Map<String, String> = emptyMap(),
    val env: MrpackEnv? = null,
    val downloads: List<String> = emptyList(),
    val fileSize: Long = 0,
)

@Serializable
data class MrpackEnv(
    val client: String = "required",
    val server: String = "required",
)
