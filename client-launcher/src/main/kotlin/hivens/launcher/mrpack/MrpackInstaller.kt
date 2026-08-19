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
import hivens.launcher.update.PackFileEntry
import hivens.launcher.update.PackFileRecord
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
            //    Same applier the update uses: against an empty record nothing
            //    counts as unchanged, so every entry is written.
            val overrides = overrideEntries(zip, OVERRIDES) + overrideEntries(zip, CLIENT_OVERRIDES)
            applyOverrides(zip, clientDir, overrides, emptyMap())
            val overrideCrcs = overrides.mapNotNull { (path, e) -> e.crc?.let { path to it } }.toMap()

            // 3. What the pack put here, before anything else can have been here.
            //    An update reads this to tell the pack's own files from the
            //    player's; without it the only safe answer is to reinstall, and
            //    that takes their worlds and configs with it. Written before the
            //    instance is registered, so a crash in between leaves an orphan
            //    directory rather than a registered instance we know nothing about.
            PackFileRecord.write(
                clientDir,
                PackFileRecord.captureAll(
                    clientDir = clientDir,
                    publishedSha1 = files.mapNotNull { f -> f.hashes[HASH_SHA1]?.let { f.path to it } }.toMap(),
                    archiveCrc32 = overrideCrcs,
                ),
            )

            // 4. Canonical runtime into the shared roots (idempotent).
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
                installedBuildKey = source?.buildKey,
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
     * Moves an installed instance to the pack version in [mrpack], touching only
     * what the pack itself owns.
     *
     * Files the previous version shipped and this one does not are removed;
     * files this one changes are written; files it does not change are left
     * exactly as they are, edits included. Anything the player put here is never
     * considered: it is not in the record, so it is not the pack's to move.
     *
     * A conflict -- the player edited a file that this version also changes --
     * resolves to the pack's copy and a line in the log. Deciding it any other
     * way makes the launcher answerable for someone else's pack, and a merge in
     * a directory nobody controls is worse than a plain rule loudly stated.
     *
     * An instance with no record (installed before records existed) updates as a
     * full write of the pack's content and deletes nothing at all: not knowing
     * what was ours, the safe reading is that none of it was.
     */
    suspend fun update(
        instance: PackInstance,
        mrpack: Path,
        source: MrpackSource? = null,
        /**
         * The archive of the version currently installed, for an instance that
         * predates the record. Its file list stands in for the baseline, which is
         * the only way to know what the pack put here when nothing wrote it down.
         */
        installedArchive: Path? = null,
        progress: (current: Int, total: Int, filename: String) -> Unit = { _, _, _ -> },
    ): PackInstance = withContext(Dispatchers.IO) {
        val clientDir = dataDir.resolve("instances").resolve(instance.instanceDirName)
        if (!Files.isDirectory(clientDir)) throw IOException("instance dir is gone: $clientDir")

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

            // Without a baseline an update cannot retire what the previous
            // version shipped, and a pack that renames its jars per version --
            // which is every pack -- ends up with both copies installed and a
            // game that will not start. Reading the old archive costs one small
            // download and only happens until a record exists.
            val old = PackFileRecord.read(clientDir).ifEmpty {
                installedArchive?.let { baselineFrom(it) }.orEmpty()
            }
            val files = index.files.filter { it.env?.client != ENV_UNSUPPORTED }
            // client-overrides wins on a clash, so it is read second.
            val overrides = overrideEntries(zip, OVERRIDES) + overrideEntries(zip, CLIENT_OVERRIDES)
            val ours = files.map { it.path }.toSet() + overrides.keys

            val wanted = files.filterNot { file ->
                val rec = old[file.path] ?: return@filterNot false
                val dest = safeResolve(clientDir, file.path)
                // The pack did not move it and it is still here as we left it.
                rec.sha1 == file.hashes[HASH_SHA1] && Files.isRegularFile(dest) && Files.size(dest) == rec.size
            }
            log.info(
                "mrpack update: {} of {} indexed file(s) need fetching for {}",
                wanted.size, files.size, instance.instanceDirName,
            )
            if (wanted.isNotEmpty()) {
                transfers.fetchAll(wanted.map { transferFor(it, clientDir) }) { p ->
                    progress(p.filesDone, p.filesTotal, p.current)
                }
            }

            applyOverrides(zip, clientDir, overrides, old)

            // Retiring last, not first. A file this version drops is one the old
            // version was still using, so removing it before the replacements are
            // down means a failed download leaves an instance that is neither
            // version. The launcher that defines the format deletes first and has
            // no filesystem rollback, and an interrupted update there empties the
            // instance.
            retire(clientDir, old.keys - ours)

            val pinned = (source?.version ?: index.versionId).ifBlank { null }
            val updated = instance.copy(
                packRef = instance.packRef.copy(version = pinned ?: instance.packRef.version),
                pinnedPackVersion = pinned,
                installedBuildKey = source?.buildKey ?: instance.installedBuildKey,
                cachedManifest = CachedManifestSnapshot(
                    minecraftVersion = mcVersion,
                    loaderName = loaderName ?: "vanilla",
                    loaderVersion = loaderVersion,
                    javaMajor = javaManager.detectJavaVersion(mcVersion),
                ),
            )

            runtimeProvisioner.ensureRuntime(mcVersion, loaderName, loaderVersion, progress)

            PackFileRecord.write(
                clientDir,
                PackFileRecord.captureOf(
                    clientDir = clientDir,
                    paths = ours,
                    publishedSha1 = files.mapNotNull { f -> f.hashes[HASH_SHA1]?.let { f.path to it } }.toMap(),
                    archiveCrc32 = overrides.mapNotNull { (path, e) -> e.crc?.let { path to it } }.toMap(),
                ),
            )
            repository.put(updated)
            log.info("mrpack update: {} now at {}", instance.instanceDirName, pinned ?: "an unnamed version")
            updated
        }
    }

    /**
     * What a version of this pack ships, read out of its archive.
     *
     * Deliberately not the same thing as a record: it describes the pack, not
     * what is on anyone's disk, so the hashes are the ones the index publishes
     * and the sizes and times are unknown. That is enough for the only question
     * asked of a baseline -- which paths were the pack's -- and enough for the
     * hash comparison that decides whether a file needs fetching again.
     */
    private fun baselineFrom(archive: Path): Map<String, PackFileEntry> = runCatching {
        ZipFile(archive.toFile()).use { zip ->
            val indexEntry = zip.getEntry(INDEX_NAME) ?: return@use emptyMap()
            val index = json.decodeFromString(
                MrpackIndex.serializer(),
                zip.getInputStream(indexEntry).readBytes().decodeToString(),
            )
            val out = HashMap<String, PackFileEntry>()
            index.files.filter { it.env?.client != ENV_UNSUPPORTED }.forEach { file ->
                out[file.path] = PackFileEntry(file.hashes[HASH_SHA1].orEmpty(), file.fileSize, 0L, null)
            }
            (overrideEntries(zip, OVERRIDES) + overrideEntries(zip, CLIENT_OVERRIDES)).forEach { (path, entry) ->
                out[path] = PackFileEntry("", 0L, 0L, entry.crc)
            }
            log.info("mrpack update: baseline of {} file(s) read from the installed version's archive", out.size)
            out
        }
    }.getOrElse {
        log.warn("mrpack update: could not read the installed version's archive; nothing will be retired", it)
        emptyMap()
    }

    /** An override entry as the archive holds it: where it lands, and its recorded CRC. */
    private data class OverrideEntry(val name: String, val crc: Long?)

    private fun overrideEntries(zip: ZipFile, prefix: String): Map<String, OverrideEntry> {
        val out = HashMap<String, OverrideEntry>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory || !entry.name.startsWith(prefix)) continue
            val relative = entry.name.removePrefix(prefix)
            if (relative.isEmpty()) continue
            out[relative] = OverrideEntry(entry.name, entry.crc.takeIf { it >= 0 })
        }
        return out
    }

    /**
     * Writes the overrides this version changed, and only those.
     *
     * An entry whose CRC matches the record is one the pack has not touched
     * since we placed it, so it is left alone -- which is also what preserves an
     * edit the player made to a config this version does not care about.
     */
    private fun applyOverrides(
        zip: ZipFile,
        clientDir: Path,
        overrides: Map<String, OverrideEntry>,
        old: Map<String, PackFileEntry>,
    ) {
        val budget = UnpackBudget(UnpackLimits.PACK_CONTENT, "mrpack overrides")
        var written = 0
        for ((relative, entry) in overrides) {
            val dest = safeResolve(clientDir, relative)
            val rec = old[relative]
            val packKeptIt = rec?.crc32 != null && entry.crc != null && rec.crc32 == entry.crc
            if (packKeptIt && Files.isRegularFile(dest)) continue

            if (rec != null && Files.isRegularFile(dest) && touchedSince(dest, rec)) {
                log.warn(
                    "mrpack update: overwriting '{}', which was edited after install and this version changes too",
                    relative,
                )
            }
            Files.createDirectories(dest.parent)
            budget.entry()
            zip.getInputStream(zip.getEntry(entry.name)).use { input -> budget.copyTo(input, dest) }
            written++
        }
        log.info("mrpack update: wrote {} of {} override(s)", written, overrides.size)
    }

    /** Cheap edit check: the record's size and mtime, no bytes read. */
    private fun touchedSince(file: Path, rec: PackFileEntry): Boolean = runCatching {
        Files.size(file) != rec.size || Files.getLastModifiedTime(file).toMillis() != rec.mtimeMs
    }.getOrDefault(false)

    /** Drops files this version no longer ships, and any directory they emptied. */
    private fun retire(clientDir: Path, gone: Set<String>) {
        if (gone.isEmpty()) return
        var removed = 0
        for (relative in gone) {
            val target = runCatching { safeResolve(clientDir, relative) }.getOrElse { continue }
            runCatching { if (Files.deleteIfExists(target)) removed++ }
                .onFailure { log.warn("mrpack update: could not retire {}", relative, it) }
            // Only ever succeeds on a directory nothing else is using, so a
            // folder the player has put something in is safe by construction.
            var parent = target.parent
            while (parent != null && parent.startsWith(clientDir) && parent != clientDir) {
                if (runCatching { Files.deleteIfExists(parent) }.getOrDefault(false)) parent = parent.parent else break
            }
        }
        log.info("mrpack update: retired {} file(s) the new version no longer ships", removed)
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
    /**
     * How the source identifies this build, where its version label does not.
     * Recorded onto the instance so "which build is this" survives a source that
     * publishes several under one number.
     */
    val buildKey: String? = null,
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
