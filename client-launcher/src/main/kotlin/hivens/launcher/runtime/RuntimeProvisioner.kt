package hivens.launcher.runtime

import hivens.core.api.HttpClientProvider
import hivens.core.io.resolveWithinRoot
import hivens.core.net.Digest
import hivens.core.net.DigestAlgorithm
import hivens.core.net.SkipIfPresent
import hivens.core.net.Transfer
import hivens.core.net.TransferEngine
import hivens.core.platform.Platform
import hivens.launcher.runtime.loader.DownloadProgress
import hivens.launcher.runtime.loader.LibrarySpec
import hivens.launcher.runtime.loader.LoaderRegistry
import hivens.launcher.runtime.loader.ResolvedLibrary
import hivens.launcher.runtime.loader.ResolvedRuntime
import hivens.launcher.runtime.loader.mergeLibraries
import hivens.launcher.util.sha1Of
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
 * Forge libraries are layered on top of this by a later step. Native jars
 * (lwjgl `.so`/`.dll`/`.dylib`) are resolved here too -- the host-matching set
 * is picked from the same manifest as the classpath and extracted per-instance
 * by [hivens.launcher.component.EnvironmentPreparer].
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
    private val transfers: TransferEngine,
    private val json: Json,
    private val loaderRegistry: LoaderRegistry = LoaderRegistry(emptyList()),
    osName: String = System.getProperty("os.name", ""),
    osArch: String = System.getProperty("os.arch", ""),
    private val versionManifestUrl: String = VERSION_MANIFEST_URL,
    private val resourcesBaseUrl: String = RESOURCES_BASE,
) {
    private val log = LoggerFactory.getLogger(RuntimeProvisioner::class.java)
    private val httpClient get() = clientProvider.current
    private val mojangOs: String = Platform.classify(osName).mojang

    /**
     * The Mojang native classifiers to keep for THIS host, matched exactly so a
     * machine never picks up a foreign arch (`-arm64` on x64 and vice versa) --
     * which on the module path would collide as a second `org.lwjgl.natives`.
     * Arch-symmetric across all three OSes: an arm64 host (incl. Linux/ARM)
     * takes the `-arm64` classifier, an x64 host the bare one. Covers both wire
     * shapes (pre-1.19 `downloads.classifiers` key, 1.19+ coord classifier).
     * macOS keeps both the modern (`natives-macos`) and legacy (`natives-osx`)
     * x64 spellings.
     */
    private val acceptedNativeClassifiers: Set<String> = run {
        val arch = osArch.lowercase()
        val arm64 = arch.contains("aarch64") || arch.contains("arm64")
        when (mojangOs) {
            "windows" -> if (arm64) setOf("natives-windows-arm64") else setOf("natives-windows")
            "osx" -> if (arm64) setOf("natives-macos-arm64") else setOf("natives-macos", "natives-osx")
            else -> if (arm64) setOf("natives-linux-arm64") else setOf("natives-linux")
        }
    }

    /** Resolved vanilla layout: the client jar, the asset index id, the
     *  vanilla library set (with coords, for merging a loader overlay), and
     *  the modern jvm/game arg tokens (empty on legacy versions) that a
     *  modern loader overlay inherits. */
    data class VanillaRuntime(
        val clientJar: Path,
        val assetIndexId: String,
        val libraries: List<ResolvedLibrary>,
        val jvmArgs: List<String> = emptyList(),
        val gameArgs: List<String> = emptyList(),
        /** Host-matching native jars (lwjgl etc.) resolved from the manifest,
         *  on disk in the shared root after [ensureVanilla]. Carries coords so a
         *  loader that swaps natives can drop the ones its removeFromBase matches
         *  (LWJGL2) while keeping the rest (jinput). */
        val natives: List<ResolvedLibrary> = emptyList(),
        /** Mojang's declared Java major (1.17+ vanilla json carries
         *  `javaVersion.majorVersion`); null on legacy / when absent. */
        val javaMajor: Int? = null,
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
                natives = vanilla.natives.map { it.path },
                javaMajor = vanilla.javaMajor,
            )

        log.info("resolving loader overlay: {} {}", resolver.loaderId, loaderVersion)
        val profile = resolver.resolve(mcVersion, loaderVersion)
        val overlay = profile.libraries.map { ResolvedLibrary(it.coord, provision(it)) }
        // Host natives the loader adds on top of vanilla's -- a LWJGL swap
        // (Cleanroom / lwjgl3ify) contributes its own LWJGL3 .so/.dll here. The
        // loader lists all platforms (as the MMC instance does); the same host
        // filter as the vanilla natives keeps only this machine's set, so the
        // resolver stays platform-agnostic.
        val overrideNatives = profile.nativesOverride
            ?.filterNot { isForeignNative(it.coord) }
            ?.map { provision(it) }
            .orEmpty()
        // Processor outputs FML resolves by path under libraryDirectory (the
        // patched/SRG client, neoforge universal) -- on disk, never on -cp.
        profile.placeOnlyFiles.forEach { pf ->
            placeLocal(librariesDir.resolve(pf.relPath), pf.source, null)
        }
        // The installer's resources-only client output (client-<neoform>-extra.jar:
        // version.json + assets, no classes). Placed above like every other output;
        // singled out here so the command builder can add it to -cp for its
        // version.json (see [ResolvedRuntime.clientResourcesJar]).
        val clientResources = profile.placeOnlyFiles
            .firstOrNull { it.relPath.startsWith("net/minecraft/client/") && it.relPath.endsWith("-extra.jar") }
            ?.let { librariesDir.resolve(it.relPath) }
        // A self-contained loader (Cleanroom) supplies the whole classpath, so
        // the vanilla libraries are dropped -- keeping them leaks cross-coord
        // twins the merge cannot dedup (old oshi/icu/netty shadowing the new).
        // Additive loaders keep the vanilla base minus whatever they swap out.
        val base = if (profile.replacesVanillaLibraries) {
            emptyList()
        } else {
            vanilla.libraries.filterNot { profile.removeFromBase(it.coord) }
        }
        val baseNatives = if (profile.replacesVanillaLibraries) {
            emptyList()
        } else {
            vanilla.natives.filterNot { profile.removeFromBase(it.coord) }.map { it.path }
        }
        ResolvedRuntime(
            // removeFromBase is a no-op for every additive loader, so their merged
            // set is byte-identical to before.
            libraries = mergeLibraries(base, overlay),
            clientJar = vanilla.clientJar,
            clientResourcesJar = clientResources,
            mainClass = profile.mainClass,
            assetIndexId = vanilla.assetIndexId,
            // Modern (BootstrapLauncher) overlays need vanilla's jvm args (the
            // --add-opens macros etc.); the command builder strips the inherited
            // -cp/-p/-Djava.library.path it rebuilds. Game args are NEVER
            // inherited -- the standard --username/--uuid/... set is emitted by
            // the command builder, and only the loader's own additions
            // (--launchTarget, --fml.*) come from the profile.
            jvmArgs = if (profile.inheritsVanillaArguments) vanilla.jvmArgs + profile.jvmArgs else profile.jvmArgs,
            gameArgs = profile.gameArgs,
            // Additive: vanilla natives minus the swapped-out ones (LWJGL2) plus
            // the loader's own (LWJGL3), keeping unrelated vanilla natives
            // (jinput). Self-contained: only the loader's own natives.
            natives = baseNatives + overrideNatives,
            // Loader override (Cleanroom -> 25) wins; else inherit vanilla's declared.
            javaMajor = profile.javaMajor ?: vanilla.javaMajor,
        )
    }

    /**
     * Materialises one [LibrarySpec] into the shared libraries root and returns
     * its on-disk path: a local file copy, bundled bytes, or a download, in that
     * precedence. Shared by the loader overlay and the loader's native-override
     * set so both go through the same verify/skip path.
     */
    private suspend fun provision(spec: LibrarySpec): Path {
        val dest = librariesDir.resolve(spec.coord.relativePath)
        when {
            spec.localFile != null -> placeLocal(dest, spec.localFile, spec.sha1)
            spec.bundled != null -> placeBundled(dest, spec.bundled, spec.sha1)
            else -> {
                val url = spec.url ?: throw IOException("library ${spec.coord.groupArtifact} has neither url, bundled bytes, nor a local file")
                fetchIfNeeded(DownloadTask(url, dest, spec.sha1.orEmpty(), spec.size))
            }
        }
        return dest
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
        // Offline-friendly resolve. A Mojang version json is immutable per MC
        // version, so cache it and reuse the on-disk copy -- a relaunch then skips
        // BOTH the version-manifest and the version fetch. The asset index is
        // reused when the on-disk copy already matches its sha (no refetch, no
        // rewrite). With every library/object already present (fetchIfNeeded skips
        // on size), a relaunch needs ZERO network -- the last thing that blocked
        // offline launch.
        val version = loadOrFetchVersion(mcVersion)
        val assetIndexId = version.assetIndex.id
        val assetIndex = ensureAssetIndex(assetIndexId, version.assetIndex.url, version.assetIndex.sha1)

        val tasks = planVanillaDownloads(mcVersion, version, assetIndex)
        log.info("vanilla runtime {}: {} files to verify/fetch (assetIndex={})", mcVersion, tasks.size, assetIndexId)
        // One request at a time over a few thousand asset objects was the slowest
        // part of a first launch, and a single reset anywhere in it failed the whole
        // provisioning run with nothing retried.
        transfers.fetchAll(tasks.map { it.toTransfer() }) { p ->
            progress(p.filesDone, p.filesTotal, p.current)
        }

        VanillaRuntime(
            clientJar = librariesDir.resolve(clientJarRelPath(mcVersion)),
            assetIndexId = assetIndexId,
            libraries = vanillaLibraries(version),
            jvmArgs = version.arguments?.let { flattenArguments(it.jvm, mojangOs) } ?: emptyList(),
            gameArgs = version.arguments?.let { flattenArguments(it.game, mojangOs) } ?: emptyList(),
            natives = nativeLibraries(version),
            javaMajor = version.javaVersion?.majorVersion,
        )
    }

    /**
     * The rule-allowed vanilla libraries with maven coordinates -- the base of
     * the merge. Paths match [planVanillaDownloads]'s library destinations.
     */
    /**
     * Every Minecraft version id from Mojang's manifest, newest-first -- the
     * source list for a version picker when creating a pack from scratch.
     */
    suspend fun availableMinecraftVersions(): List<String> =
        json.decodeFromString(MojangVersionManifest.serializer(), fetchText(versionManifestUrl)).versions.map { it.id }

    internal fun vanillaLibraries(version: MojangVersion): List<ResolvedLibrary> =
        version.libraries.mapNotNull { lib ->
            if (!isLibraryAllowed(lib.rules)) return@mapNotNull null
            val artifact = lib.downloads?.artifact ?: return@mapNotNull null
            if (artifact.path.isBlank()) return@mapNotNull null
            val coord = MavenCoord.parse(lib.name)
            if (isForeignNative(coord)) return@mapNotNull null
            ResolvedLibrary(coord, resolveWithinRoot(librariesDir, artifact.path, lib.name))
        }

    /**
     * A `natives-<os>[-<arch>]` library that does NOT match this host. Skipped
     * everywhere -- download, classpath/module-path, extraction -- so a foreign
     * platform's native module can't collide with the host's on the module path
     * (two `org.lwjgl.natives`) or bloat `-cp`. The host's OWN native is kept:
     * the modern (BootstrapLauncher) module graph requires it. `os.name` rules
     * alone do not separate arch variants (mac x64 + arm64 both pass `osx`), so
     * the gate is on the classifier, not on Mojang's inconsistent arch rules.
     */
    private fun isForeignNative(coord: MavenCoord): Boolean {
        val classifier = coord.nativeClassifier ?: return false
        return classifier !in acceptedNativeClassifiers
    }

    /**
     * The host's native jars for [version], resolved from the same manifest as
     * the classpath. Two wire shapes are covered:
     *  - pre-1.19: a library carries the native under
     *    `downloads.classifiers["natives-<os>"]`.
     *  - 1.19+: the native is its own library whose coord classifier is
     *    `natives-<os>`, taken from `downloads.artifact`.
     * Only [acceptedNativeClassifiers] (host OS + arch) are kept. Destinations
     * match [planVanillaDownloads], so the jars are on disk by the time
     * [hivens.launcher.component.EnvironmentPreparer] extracts them.
     */
    internal fun nativeLibraries(version: MojangVersion): List<ResolvedLibrary> {
        val out = ArrayList<ResolvedLibrary>()
        for (lib in version.libraries) {
            if (!isLibraryAllowed(lib.rules)) continue
            val downloads = lib.downloads ?: continue
            val base = MavenCoord.parse(lib.name)
            for ((classifier, art) in downloads.classifiers) {
                if (classifier in acceptedNativeClassifiers && art.path.isNotBlank()) {
                    out.add(ResolvedLibrary(base.copy(classifier = classifier), resolveWithinRoot(librariesDir, art.path, lib.name)))
                }
            }
            if (base.classifier != null && base.classifier in acceptedNativeClassifiers) {
                downloads.artifact?.takeIf { it.path.isNotBlank() }
                    ?.let { out.add(ResolvedLibrary(base, resolveWithinRoot(librariesDir, it.path, lib.name))) }
            }
        }
        return out.distinctBy { it.path }
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
            val downloads = lib.downloads ?: continue
            val coord = MavenCoord.parse(lib.name)
            downloads.artifact?.takeIf { it.path.isNotBlank() && !isForeignNative(coord) }?.let { artifact ->
                out += DownloadTask(
                    url = artifact.url,
                    dest = resolveWithinRoot(librariesDir, artifact.path, lib.name),
                    sha1 = artifact.sha1,
                    size = artifact.size,
                )
            }
            // pre-1.19 natives live under classifiers, not the main artifact;
            // fetch only the host-matching one (1.19+ natives are their own
            // library and arrive via the artifact branch above).
            for ((classifier, nativeArt) in downloads.classifiers) {
                if (classifier in acceptedNativeClassifiers && nativeArt.path.isNotBlank()) {
                    out += DownloadTask(
                        url = nativeArt.url,
                        dest = resolveWithinRoot(librariesDir, nativeArt.path, lib.name),
                        sha1 = nativeArt.sha1,
                        size = nativeArt.size,
                    )
                }
            }
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
                dest = resolveWithinRoot(assetsDir, assetObjectRelPath(obj.hash), obj.hash),
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
    internal fun isLibraryAllowed(rules: List<MojangRule>): Boolean = libraryRulesAllow(rules, mojangOs)

    internal fun clientJarRelPath(mcVersion: String): String =
        "net/minecraft/minecraft/$mcVersion/minecraft-$mcVersion.jar"

    /** Cached Mojang version json, next to the client jar in the shared root. */
    internal fun versionJsonRelPath(mcVersion: String): String =
        "net/minecraft/minecraft/$mcVersion/$mcVersion.json"

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

    /**
     * The Mojang version json for [mcVersion], from the on-disk cache when
     * present (a released version's json never changes), else fetched from the
     * manifest + persisted. The cache is what lets a relaunch skip the network.
     * A corrupt/partial cache (rare; writes are atomic) falls back to a refetch.
     */
    private suspend fun loadOrFetchVersion(mcVersion: String): MojangVersion {
        val cachePath = librariesDir.resolve(versionJsonRelPath(mcVersion))
        if (Files.isRegularFile(cachePath)) {
            runCatching { json.decodeFromString(MojangVersion.serializer(), Files.readString(cachePath)) }
                .onSuccess { return it }
                .onFailure { log.warn("cached version json for {} unreadable; refetching", mcVersion) }
        }
        val text = fetchText(resolveVersionUrl(mcVersion))
        val parsed = json.decodeFromString(MojangVersion.serializer(), text)
        runCatching { writeBytes(cachePath, text.toByteArray()) }
            .onFailure { log.warn("could not cache version json for {}", mcVersion, it) }
        return parsed
    }

    /**
     * Returns the parsed asset index for [id], reusing the on-disk copy when it
     * already matches [sha1] (no refetch, no rewrite -- the offline path), else
     * fetching + verifying + persisting it.
     */
    private suspend fun ensureAssetIndex(id: String, url: String, sha1: String): MojangAssetIndex {
        val indexPath = assetsDir.resolve(assetIndexRelPath(id))
        val onDisk = if (Files.isRegularFile(indexPath)) runCatching { Files.readAllBytes(indexPath) }.getOrNull() else null
        val bytes = if (onDisk != null && sha1Of(onDisk).equals(sha1, ignoreCase = true)) {
            onDisk
        } else {
            val fetched = fetchBytes(url)
            verifyOrThrow(fetched, sha1, "asset index $id")
            writeBytes(indexPath, fetched)
            fetched
        }
        return json.decodeFromString(MojangAssetIndex.serializer(), bytes.decodeToString())
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

    private suspend fun fetchIfNeeded(task: DownloadTask) {
        transfers.fetch(task.toTransfer())
    }

    /**
     * Skip when the file is already present at the right size: content here is
     * addressed by hash in its own path (asset objects) or pinned by the manifest
     * sha1 at a maven coordinate (libraries), a same-size collision is not a
     * realistic threat, and re-hashing thousands of objects on every launch is
     * minutes of disk for an answer the paths already gave. An entry whose upstream
     * declares no size falls back to presence for the same reason -- refetching it
     * every launch would cost the network instead.
     *
     * Freshly downloaded bytes are always verified against the sha1 when there is
     * one, by the engine, before anything is published.
     */
    private fun DownloadTask.toTransfer(): Transfer = Transfer(
        url = url,
        dest = dest,
        expect = sha1.takeIf { it.isNotBlank() }?.let { Digest(DigestAlgorithm.SHA1, it) },
        size = size,
        skip = if (size > 0L) SkipIfPresent.BySize else SkipIfPresent.Presence,
    )

    private fun writeBytes(dest: Path, bytes: ByteArray) {
        Files.createDirectories(dest.parent)
        val tmp = Files.createTempFile(dest.parent, "${dest.fileName}.", ".tmp")
        try {
            Files.write(tmp, bytes)
            moveAtomic(tmp, dest)
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
    }

    /** Places installer-bundled jar bytes into the shared root, skip-if-present. */
    private fun placeBundled(dest: Path, bytes: ByteArray, sha1: String?) {
        if (Files.isRegularFile(dest) && (sha1 == null || sha1Of(dest).equals(sha1, ignoreCase = true))) return
        if (sha1 != null && !sha1Of(bytes).equals(sha1, ignoreCase = true)) {
            throw IOException("bundled library sha1 mismatch at $dest: expected $sha1, got ${sha1Of(bytes)}")
        }
        writeBytes(dest, bytes)
    }

    /** Copies an installer-produced jar from the loader cache into the shared
     *  root, skip-if-present, verifying [sha1] against the source when known. */
    private fun placeLocal(dest: Path, src: Path, sha1: String?) {
        if (Files.isRegularFile(dest) && (sha1 == null || sha1Of(dest).equals(sha1, ignoreCase = true))) return
        if (sha1 != null && !sha1Of(src).equals(sha1, ignoreCase = true)) {
            throw IOException("local library sha1 mismatch at $src: expected $sha1, got ${sha1Of(src)}")
        }
        Files.createDirectories(dest.parent)
        val tmp = Files.createTempFile(dest.parent, "${dest.fileName}.", ".tmp")
        try {
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING)
            moveAtomic(tmp, dest)
        } finally {
            runCatching { Files.deleteIfExists(tmp) }
        }
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

    private fun sha1Of(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        const val VERSION_MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
        const val RESOURCES_BASE = "https://resources.download.minecraft.net"

        /** Pure-vanilla launch entry point (no loader overlay). */
        const val VANILLA_MAIN_CLASS = "net.minecraft.client.main.Main"
    }
}
