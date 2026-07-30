package hivens.launcher.mrpack

import hivens.core.net.Digest
import hivens.core.net.DigestAlgorithm
import hivens.core.net.SkipIfPresent
import hivens.core.net.Transfer
import hivens.core.net.TransferEngine
import hivens.core.io.UnpackBudget
import hivens.core.io.UnpackLimits
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.InstanceRuntime
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.launcher.runtime.RuntimeProvisioner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
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
 * bytes are verified against the strongest hash the index pins (sha512 over
 * sha1); a file entry with downloads but no usable hash is rejected, so a
 * tampered mirror can't substitute content -- not even by omitting sha1.
 */
class MrpackInstaller(
    private val transfers: TransferEngine,
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
        iconUrl: String? = null,
        bannerUrl: String? = null,
        onReserveDir: (Path) -> Unit = {},
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
            // Reserve before createDirectories, so a cancel mid-download can
            // delete exactly this partial dir.
            onReserveDir(clientDir)
            Files.createDirectories(clientDir)
            log.info("mrpack: installing '{}' ({} {} on {}) -> {}", displayName, loaderName ?: "vanilla", loaderVersion, mcVersion, clientDir)

            // 1. Files fetched by URL. Skip anything the pack marks unsupported
            //    on the client; optional client content installs by default
            //    (per-entry opt-out is the optional-mods system's job later).
            val files = index.files.filter { it.env?.client != ENV_UNSUPPORTED }
            transfers.fetchAll(files.map { transferFor(it, clientDir) }) { p ->
                progress(p.filesDone, p.filesTotal, p.current)
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
                iconUrl = iconUrl,
                bannerUrl = bannerUrl,
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
     * Download a `.mrpack` from [url], install it, then drop the archive. [source]
     * stamps the instance's origin/id/version so the update flow can find newer
     * versions later -- this is the Modrinth catalogue install path.
     */
    suspend fun installFromUrl(
        url: String,
        source: MrpackSource,
        iconUrl: String? = null,
        bannerUrl: String? = null,
        onReserveDir: (Path) -> Unit = {},
        progress: (current: Int, total: Int, filename: String) -> Unit = { _, _, _ -> },
    ): PackInstance = withContext(Dispatchers.IO) {
        // Named after the pack rather than a fresh temp file per attempt, and inside
        // the data dir: a pack archive runs to hundreds of megabytes, and a partial
        // that a relaunch cannot find is a download that starts over.
        val archive = dataDir.resolve(DOWNLOADS_DIR).resolve(sanitize("${source.id}-${source.version ?: "latest"}") + ".mrpack")
        transfers.fetch(Transfer(url = url, dest = archive, skip = SkipIfPresent.Never))
        val instance = install(archive, source, iconUrl, bannerUrl, onReserveDir, progress)
        // Dropped only once the install is through. A failure leaves the archive for
        // the next attempt to continue from instead of pulling it again.
        runCatching { Files.deleteIfExists(archive) }
        instance
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

    /**
     * The transfer for one index entry, pinned to the strongest hash the index
     * carries.
     *
     * An entry with downloads but NO usable hash is rejected outright rather than
     * fetched unverified: a hostile index can simply omit sha1, so treating a
     * missing hash as "skip the check" is a content-substitution hole.
     *
     * Every url the entry lists is kept as a mirror, in the index's own order.
     */
    private fun transferFor(file: MrpackFile, clientDir: Path): Transfer {
        val dest = safeResolve(clientDir, file.path)
        if (file.downloads.isEmpty()) throw IOException("mrpack file ${file.path} has no download URL")
        val expect = file.hashes[HASH_SHA512]?.let { Digest(DigestAlgorithm.SHA512, it) }
            ?: file.hashes[HASH_SHA1]?.let { Digest(DigestAlgorithm.SHA1, it) }
            ?: throw IOException("mrpack file ${file.path} pins no sha1/sha512; refusing unverifiable download")
        return Transfer(
            url = file.downloads.first(),
            dest = dest,
            expect = expect,
            size = file.fileSize,
            mirrors = file.downloads.drop(1),
        )
    }

    private fun extractOverrides(zip: ZipFile, prefix: String, clientDir: Path) {
        val budget = UnpackBudget(UnpackLimits.PACK_CONTENT, "mrpack overrides")
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
            val relative = entry.name.removePrefix(prefix)
            if (relative.isEmpty()) continue
            val dest = safeResolve(clientDir, relative)
            Files.createDirectories(dest.parent)
            budget.entry()
            zip.getInputStream(entry).use { input -> budget.copyTo(input, dest) }
        }
    }

    /** Resolves [relative] under [base], rejecting traversal that escapes it. */
    internal fun safeResolve(base: Path, relative: String): Path {
        val root = base.normalize()
        val resolved = root.resolve(relative).normalize()
        if (!resolved.startsWith(root)) {
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
        const val HASH_SHA512 = "sha512"
        const val DOWNLOADS_DIR = "downloads"

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
