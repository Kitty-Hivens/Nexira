package hivens.launcher.runtime

import hivens.core.api.HttpClientProvider
import hivens.launcher.runtime.loader.DownloadProgress
import hivens.launcher.runtime.loader.LibrarySpec
import hivens.launcher.runtime.loader.LoaderRegistry
import hivens.launcher.runtime.loader.ResolvedLibrary
import hivens.launcher.runtime.loader.ResolvedRuntime
import hivens.launcher.runtime.loader.mergeLibraries
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Provisions the canonical Minecraft runtime (vanilla libraries, the
 * vanilla client jar, and the game asset set) from Mojang's OFFICIAL
 * CDNs into the SHARED roots, so every pack of the same MC version reuses
 * one copy instead of re-downloading per instance.
 *
 * Sibling of [hivens.launcher.JavaManagerService], which provisions the
 * JDK the same way (versioned shared dir, idempotent, official upstream).
 * The mirror never hosts these copyrighted bits -- they flow straight
 * from the rights holder's CDN.
 *
 * Forge libraries are layered on top of this by a later step; natives are
 * handled separately by [hivens.launcher.component.EnvironmentPreparer].
 *
 * Layout produced:
 * - `<librariesDir>/<maven-path>.jar`           vanilla libraries
 * - `<librariesDir>/net/minecraft/minecraft/<mc>/minecraft-<mc>.jar`  client
 * - `<assetsDir>/indexes/<assetIndexId>.json`   asset index
 * - `<assetsDir>/objects/<2>/<hash>`            content-addressed objects
 */
class RuntimeProvisioner(
    private val librariesDir: Path,
    private val assetsDir: Path,
    private val clientProvider: HttpClientProvider,
    private val json: Json,
    private val loaderRegistry: LoaderRegistry = LoaderRegistry(emptyList()),
    osName: String = System.getProperty("os.name", ""),
    private val versionManifestUrl: String = VERSION_MANIFEST_URL,
    private val resourcesBaseUrl: String = RESOURCES_BASE,
) {
    private val log = LoggerFactory.getLogger(RuntimeProvisioner::class.java)
    private val httpClient get() = clientProvider.current
    private val mojangOs: String = toMojangOs(osName)

    /** Resolved vanilla layout: the client jar, the asset index id, and the
     *  vanilla library set (with coords, for merging a loader overlay). */
    data class VanillaRuntime(
        val clientJar: Path,
        val assetIndexId: String,
        val libraries: List<ResolvedLibrary>,
    )

    /** A single file to fetch into a shared root, verified against [sha1]. */
    data class DownloadTask(
        val url: String,
        val dest: Path,
        val sha1: String,
        val size: Long,
    )

    /**
     * Ensures the full runtime for [mcVersion] + the given loader is present
     * in the shared roots: the vanilla base, plus -- when [loaderName] names a
     * known loader -- that loader's overlay merged on top (loader libraries win
     * on a group:artifact collision). Idempotent; returns the merged,
     * launch-ready runtime.
     */
    suspend fun ensureRuntime(
        mcVersion: String,
        loaderName: String?,
        loaderVersion: String,
        progress: DownloadProgress = { _, _, _ -> },
    ): ResolvedRuntime = withContext(Dispatchers.IO) {
        val vanilla = ensureVanilla(mcVersion, progress)
        val resolver = loaderRegistry.resolverFor(loaderName)
            ?: return@withContext ResolvedRuntime(
                libraries = vanilla.libraries,
                clientJar = vanilla.clientJar,
                mainClass = VANILLA_MAIN_CLASS,
                assetIndexId = vanilla.assetIndexId,
            )

        log.info("resolving loader overlay: {} {}", resolver.loaderId, loaderVersion)
        val profile = resolver.resolve(mcVersion, loaderVersion)
        val overlay = profile.libraries.map { spec ->
            val dest = librariesDir.resolve(spec.coord.relativePath)
            val bytes = spec.bundled
            if (bytes != null) {
                placeBundled(dest, bytes, spec.sha1)
            } else {
                val url = spec.url ?: throw IOException("library ${spec.coord.groupArtifact} has neither url nor bundled bytes")
                fetchIfNeeded(DownloadTask(url, dest, spec.sha1.orEmpty(), spec.size))
            }
            ResolvedLibrary(spec.coord, dest)
        }
        ResolvedRuntime(
            libraries = mergeLibraries(vanilla.libraries, overlay),
            clientJar = vanilla.clientJar,
            mainClass = profile.mainClass,
            assetIndexId = vanilla.assetIndexId,
            jvmArgs = profile.jvmArgs,
            gameArgs = profile.gameArgs,
        )
    }

    /**
     * Ensures the vanilla runtime for [mcVersion] is present in the shared
     * roots, downloading only what is missing (per-file skip on size). Returns
     * the client jar, the asset index id, and the resolved vanilla libraries.
     */
    suspend fun ensureVanilla(
        mcVersion: String,
        progress: DownloadProgress = { _, _, _ -> },
    ): VanillaRuntime = withContext(Dispatchers.IO) {
        val versionUrl = resolveVersionUrl(mcVersion)
        val version = json.decodeFromString(MojangVersion.serializer(), fetchText(versionUrl))
        val assetIndexId = version.assetIndex.id

        // Fetch + persist the asset index, then enumerate its objects.
        val indexBytes = fetchBytes(version.assetIndex.url)
        verifyOrThrow(indexBytes, version.assetIndex.sha1, "asset index $assetIndexId")
        writeBytes(assetsDir.resolve(assetIndexRelPath(assetIndexId)), indexBytes)
        val assetIndex = json.decodeFromString(MojangAssetIndex.serializer(), indexBytes.decodeToString())

        val tasks = planVanillaDownloads(mcVersion, version, assetIndex)
        log.info("vanilla runtime {}: {} files to verify/fetch (assetIndex={})", mcVersion, tasks.size, assetIndexId)
        tasks.forEachIndexed { i, task ->
            progress(i + 1, tasks.size, task.dest.fileName.toString())
            fetchIfNeeded(task)
        }

        VanillaRuntime(
            clientJar = librariesDir.resolve(clientJarRelPath(mcVersion)),
            assetIndexId = assetIndexId,
            libraries = vanillaLibraries(version),
        )
    }

    /**
     * The rule-allowed vanilla libraries with maven coordinates -- the base of
     * the merge. Paths match [planVanillaDownloads]'s library destinations.
     */
    internal fun vanillaLibraries(version: MojangVersion): List<ResolvedLibrary> =
        version.libraries.mapNotNull { lib ->
            if (!isLibraryAllowed(lib.rules)) return@mapNotNull null
            val artifact = lib.downloads?.artifact ?: return@mapNotNull null
            if (artifact.path.isBlank()) return@mapNotNull null
            ResolvedLibrary(MavenCoord.parse(lib.name), librariesDir.resolve(artifact.path))
        }

    // -- pure planning (no IO) ------------------------------------------------

    /**
     * Maps a parsed version + asset index onto the concrete set of files
     * to place in the shared roots: every rule-allowed library artifact,
     * the client jar, and every asset object. Pure -- the unit tests pin
     * the path mapping here without any network.
     */
    internal fun planVanillaDownloads(
        mcVersion: String,
        version: MojangVersion,
        assetIndex: MojangAssetIndex,
    ): List<DownloadTask> {
        val out = ArrayList<DownloadTask>()

        for (lib in version.libraries) {
            if (!isLibraryAllowed(lib.rules)) continue
            val artifact = lib.downloads?.artifact ?: continue
            if (artifact.path.isBlank()) continue
            out += DownloadTask(
                url = artifact.url,
                dest = librariesDir.resolve(artifact.path),
                sha1 = artifact.sha1,
                size = artifact.size,
            )
        }

        version.downloads.client.let { client ->
            out += DownloadTask(
                url = client.url,
                dest = librariesDir.resolve(clientJarRelPath(mcVersion)),
                sha1 = client.sha1,
                size = client.size,
            )
        }

        for ((_, obj) in assetIndex.objects) {
            out += DownloadTask(
                url = assetObjectUrl(obj.hash),
                dest = assetsDir.resolve(assetObjectRelPath(obj.hash)),
                sha1 = obj.hash,
                size = obj.size,
            )
        }

        return out
    }

    /**
     * Mojang rule evaluation: no rules means allowed; otherwise rules are
     * applied in order and the last one whose `os` matches the current
     * platform (or which has no `os`, matching all) decides. Keeps
     * wrong-platform library artifacts (rare for non-native 1.12.2 libs)
     * out of the shared root.
     */
    internal fun isLibraryAllowed(rules: List<MojangRule>): Boolean {
        if (rules.isEmpty()) return true
        var allowed = false
        for (rule in rules) {
            val matches = rule.os?.name?.let { it == mojangOs } ?: true
            if (matches) allowed = rule.action == "allow"
        }
        return allowed
    }

    internal fun clientJarRelPath(mcVersion: String): String =
        "net/minecraft/minecraft/$mcVersion/minecraft-$mcVersion.jar"

    internal fun assetIndexRelPath(assetIndexId: String): String = "indexes/$assetIndexId.json"

    internal fun assetObjectRelPath(hash: String): String = "objects/${hash.take(2)}/$hash"

    internal fun assetObjectUrl(hash: String): String =
        "${resourcesBaseUrl.trimEnd('/')}/${hash.take(2)}/$hash"

    // -- IO -------------------------------------------------------------------

    private suspend fun resolveVersionUrl(mcVersion: String): String {
        val manifest = json.decodeFromString(MojangVersionManifest.serializer(), fetchText(versionManifestUrl))
        return manifest.versions.firstOrNull { it.id == mcVersion }?.url
            ?: throw IOException("Minecraft version $mcVersion not found in Mojang version manifest")
    }

    private suspend fun fetchText(url: String): String =
        httpClient.prepareGet(url).execute { resp ->
            if (!resp.status.isSuccess()) throw IOException("GET $url -> HTTP ${resp.status}")
            resp.bodyAsText()
        }

    private suspend fun fetchBytes(url: String): ByteArray =
        httpClient.prepareGet(url).execute { resp ->
            if (!resp.status.isSuccess()) throw IOException("GET $url -> HTTP ${resp.status}")
            val buf = java.io.ByteArrayOutputStream()
            resp.bodyAsChannel().copyTo(buf)
            buf.toByteArray()
        }

    /**
     * Skip when the file is already present at the right size (content is
     * addressed by hash in its path for objects, and pinned by the manifest
     * sha1 for libraries -- a same-size collision is not a realistic threat,
     * and re-hashing thousands of objects on every launch is not worth it).
     * Freshly downloaded bytes are always sha1-verified.
     */
    private suspend fun fetchIfNeeded(task: DownloadTask) {
        if (Files.isRegularFile(task.dest) && (task.size <= 0 || Files.size(task.dest) == task.size)) {
            return
        }
        val tmp = task.dest.resolveSibling("${task.dest.fileName}.tmp")
        Files.createDirectories(task.dest.parent)
        runCatching { Files.deleteIfExists(tmp) }
        httpClient.prepareGet(task.url).execute { resp ->
            if (!resp.status.isSuccess()) throw IOException("GET ${task.url} -> HTTP ${resp.status}")
            FileOutputStream(tmp.toFile()).use { out -> resp.bodyAsChannel().copyTo(out) }
        }
        if (task.sha1.isNotBlank()) {
            val actual = sha1Of(tmp)
            if (!actual.equals(task.sha1, ignoreCase = true)) {
                runCatching { Files.deleteIfExists(tmp) }
                throw IOException("sha1 mismatch for ${task.url}: expected ${task.sha1}, got $actual")
            }
        }
        moveAtomic(tmp, task.dest)
    }

    private fun writeBytes(dest: Path, bytes: ByteArray) {
        Files.createDirectories(dest.parent)
        val tmp = dest.resolveSibling("${dest.fileName}.tmp")
        Files.write(tmp, bytes)
        moveAtomic(tmp, dest)
    }

    /** Places installer-bundled jar bytes into the shared root, skip-if-present. */
    private fun placeBundled(dest: Path, bytes: ByteArray, sha1: String?) {
        if (Files.isRegularFile(dest) && (sha1 == null || sha1Of(dest).equals(sha1, ignoreCase = true))) return
        if (sha1 != null && !sha1Of(bytes).equals(sha1, ignoreCase = true)) {
            throw IOException("bundled library sha1 mismatch at $dest: expected $sha1, got ${sha1Of(bytes)}")
        }
        writeBytes(dest, bytes)
    }

    private fun moveAtomic(tmp: Path, dest: Path) {
        try {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun verifyOrThrow(bytes: ByteArray, expectedSha1: String, label: String) {
        val actual = sha1Of(bytes)
        if (!actual.equals(expectedSha1, ignoreCase = true)) {
            throw IOException("sha1 mismatch for $label: expected $expectedSha1, got $actual")
        }
    }

    private fun sha1Of(path: Path): String {
        val md = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(path).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha1Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        const val RESOURCES_BASE = "https://resources.download.minecraft.net"

        /** Pure-vanilla launch entry point (no loader overlay). */
        const val VANILLA_MAIN_CLASS = "net.minecraft.client.main.Main"

        internal fun toMojangOs(osName: String): String {
            val lower = osName.lowercase()
            return when {
                lower.contains("win") -> "windows"
                lower.contains("mac") || lower.contains("darwin") -> "osx"
                else -> "linux"
            }
        }
    }
}
