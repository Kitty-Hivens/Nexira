package hivens.launcher.smrt

import hivens.core.api.dto.smrt.SmrtAssetEntry
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtSource
import hivens.core.api.interfaces.IPackSyncService
import hivens.core.io.InstanceMutationLock
import hivens.core.io.fileOpRetry
import hivens.launcher.FileDownloadService
import hivens.launcher.ProtectedPaths
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.util.sha1Of
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator

/**
 * v2-manifest sync. Parallel to [FileDownloadService] but speaks the
 * smrt mirror's flat `mods[] + assets[]` shape with a per-entry source
 * pointer, instead of SC's recursive `{directories,files}` tree.
 *
 * Throws on any download error or sha1 mismatch. The caller does not
 * get a partial-success indicator and there is no silent fallback to
 * the SC sync path -- mirror failures must surface, otherwise a broken
 * mirror is masked by a stale-but-working SC sync and the regression
 * stays invisible.
 */
class SmrtSyncService(
    private val client: SmrtPackClient,
    private val modrinth: ModrinthClient,
    private val protectedPaths: ProtectedPaths,
) : IPackSyncService {
    private val log = LoggerFactory.getLogger(SmrtSyncService::class.java)

    /**
     * [enabledState] maps a mod `filename` to whether it should be active.
     * Required mods are always active regardless; an optional absent from the
     * map falls back to its manifest `default_enabled`. Empty map = install
     * every mod at its manifest default (the pre-toggle behaviour).
     */
    suspend fun sync(
        packId: String,
        clientDir: Path,
        progress: ((current: Int, total: Int, filename: String) -> Unit)? = null,
        enabledState: Map<String, Boolean> = emptyMap(),
    ) = withContext(Dispatchers.IO) {
        // Serialize against a concurrent structural mutation of this instance (an
        // optional-content toggle relabel), so a rename can't land between the
        // existence check and the move below. Reads are not gated -- they open
        // delete-shared and cannot corrupt a rename.
        InstanceMutationLock.withLock(clientDir) {
            val manifest = client.fetchManifest(packId)
            log.info(
                "smrt sync: pack={}, pack_version={}, mods={}, assets={}",
                manifest.packId, manifest.packVersion,
                manifest.mods.size, manifest.assets.size,
            )

            if (manifest.schemaVersion != EXPECTED_SCHEMA) {
                throw IOException(
                    "smrt mirror manifest schema_version=${manifest.schemaVersion}, " +
                        "expected $EXPECTED_SCHEMA. Update Nexira or the mirror version mismatched."
                )
            }

            Files.createDirectories(clientDir)

            // Wipe mods/ when the previous sync used a different source
            // (SC's mods/{mcversion}/ layout vs mirror's flat mods/).
            // Forge scans both trees, a duplicate jar loads its coremod
            // twice, and stacking ASM transformers (FoamFix hashCode
            // patch) recurse into StackOverflowError on the second pass.
            // The marker is per-clientDir so each pack tracks its own
            // source independently.
            val marker = clientDir.resolve(SOURCE_MARKER_FILE)
            val previousSource = readSourceMarker(marker)
            if (previousSource != SOURCE_MIRROR) {
                log.info(
                    "smrt sync: source change ({} -> {}), wiping mods/",
                    previousSource ?: "<none>", SOURCE_MIRROR,
                )
                wipeModsDir(clientDir)
            }

            val total = manifest.mods.size + manifest.assets.size
            var current = 0

            for (mod in manifest.mods) {
                current++
                progress?.invoke(current, total, mod.filename)
                val enabled = enabledState[mod.filename] ?: (mod.required || mod.defaultEnabled)
                syncMod(mod, clientDir, enabled)
            }
            for (asset in manifest.assets) {
                current++
                progress?.invoke(current, total, asset.dest)
                syncAsset(asset, clientDir)
            }

            // Drop manifest-removed mods and catch foreign payloads that
            // the wipe missed (an SC sync ran between two mirror syncs
            // without touching the marker, so the wipe gate saw a stale
            // "mirror" value). Only top-level mods/{expected_filename}
            // entries survive.
            pruneOrphanMods(clientDir, manifest.mods.flatMap { listOf(it.filename, "${it.filename}.disabled") }.toSet())

            writeSourceMarker(marker, SOURCE_MIRROR)
        }
    }

    /**
     * Re-labels already-downloaded optional mods to match [enabledState] with NO
     * network: an active jar that should be off becomes `.disabled` and vice
     * versa. The toggle UI calls this -- the bytes are already on disk, only the
     * name (and thus whether Forge loads it) changes. A variant that is missing
     * on disk is left for the next full sync to fetch.
     */
    override fun relabel(clientDir: Path, mods: List<SmrtModEntry>, enabledState: Map<String, Boolean>): List<String> {
        val modsDir = clientDir.resolve("mods")
        if (!Files.isDirectory(modsDir)) return emptyList()
        val failed = mutableListOf<String>()
        for (mod in mods) {
            val enabled = enabledState[mod.filename] ?: (mod.required || mod.defaultEnabled)
            val active = resolveSafe(modsDir, mod.filename, "mod ${mod.filename}")
            val disabled = resolveSafe(modsDir, "${mod.filename}.disabled", "mod ${mod.filename}")
            val from = if (enabled) disabled else active
            val to = if (enabled) active else disabled
            if (Files.exists(from) && !Files.exists(to)) {
                runCatching {
                    fileOpRetry("smrt relabel ${mod.filename}") {
                        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
                    }
                }.onFailure {
                    // A lock that outlives the retry means a holder we can't evict --
                    // typically the running game's classloader, which on Windows keeps
                    // the jar open without delete-sharing. The intent is already
                    // persisted in optionalContent, so the next launch's sync applies
                    // it; record the file instead of pretending the flip took effect.
                    failed += mod.filename
                    log.warn("smrt relabel: {} still held after retries; applies on next launch", mod.filename)
                }
            }
        }
        return failed
    }

    private fun pruneOrphanMods(clientDir: Path, expected: Set<String>) {
        val modsDir = clientDir.resolve("mods")
        if (!Files.isDirectory(modsDir)) return
        var removed = 0
        Files.walk(modsDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                .forEach { jar ->
                    val isCanonical = jar.parent == modsDir &&
                        jar.fileName.toString() in expected
                    if (!isCanonical) {
                        runCatching {
                            fileOpRetry("smrt prune $jar") { Files.delete(jar) }
                            removed++
                            log.debug("smrt sync: pruned orphan jar {}", jar)
                        }.onFailure { log.warn("smrt sync: failed to prune {}", jar, it) }
                    }
                }
        }
        if (removed > 0) log.info("smrt sync: pruned {} orphan jar(s) from mods/", removed)
    }

    private fun wipeModsDir(clientDir: Path) {
        val modsDir = clientDir.resolve("mods")
        if (!Files.isDirectory(modsDir)) return
        var removed = 0
        Files.walk(modsDir).use { stream ->
            stream.sorted(Comparator.reverseOrder())
                .forEach { p ->
                    if (p == modsDir) return@forEach
                    runCatching {
                        fileOpRetry("smrt wipe $p") { Files.delete(p) }
                        removed++
                    }.onFailure { log.warn("smrt sync: failed to wipe {}", p, it) }
                }
        }
        if (removed > 0) log.info("smrt sync: wiped {} entries from mods/", removed)
    }

    private fun readSourceMarker(marker: Path): String? =
        marker.toFile()
            .takeIf { it.exists() }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun writeSourceMarker(marker: Path, value: String) {
        runCatching { marker.toFile().writeText(value) }
            .onFailure { log.warn("smrt sync: failed to write source marker", it) }
    }

    /**
     * Mods land at `mods/{filename}`; an optional mod toggled OFF lands at
     * `mods/{filename}.disabled` (Forge ignores non-`.jar` names), so flipping a
     * toggle is a rename rather than a re-download. When the stale variant
     * already holds the right bytes it is moved into place; otherwise it is
     * removed and the active variant fetched. Forge 1.12.2 scans both `mods/`
     * and `mods/{mcversion}/`, so flat placement still loads.
     */
    private suspend fun syncMod(mod: SmrtModEntry, clientDir: Path, enabled: Boolean) {
        val modsDir = clientDir.resolve("mods")
        val activeDest = resolveSafe(modsDir, mod.filename, "mod ${mod.filename}")
        val disabledDest = resolveSafe(modsDir, "${mod.filename}.disabled", "mod ${mod.filename}")
        val dest = if (enabled) activeDest else disabledDest
        val stale = if (enabled) disabledDest else activeDest

        if (!isUpToDate(dest, mod.sha1, mod.sizeBytes) && isUpToDate(stale, mod.sha1, mod.sizeBytes)) {
            Files.createDirectories(dest.parent)
            fileOpRetry("smrt sync move ${mod.filename}") { Files.move(stale, dest, StandardCopyOption.REPLACE_EXISTING) }
            return
        }
        runCatching { fileOpRetry("smrt sync drop stale ${mod.filename}") { Files.deleteIfExists(stale) } }
        downloadIfNeeded(dest, mod.sha1, mod.sizeBytes, mod.source, "mod ${mod.filename}")
    }

    private suspend fun syncAsset(asset: SmrtAssetEntry, clientDir: Path) {
        // resolveSafe FIRST: a manifest entry like
        // `../../config/servers.dat` happens to match the protected-
        // suffix list (ProtectedPaths.isProtected lowercases + checks
        // endsWith/contains on the raw string), so running the
        // isProtected gate before path normalisation would silently
        // skip a path-escape attempt as "protected" instead of loudly
        // failing the manifest. The traversal IOException needs to
        // win over the protected-path debug log.
        val dest = resolveSafe(clientDir, asset.dest, "asset ${asset.dest}")
        // Protected paths (e.g. user-edited options.txt) are honored
        // here just like in the SC code path -- if the user has tuned
        // their FOV, sync must not overwrite. Protection only kicks in
        // when the file is already present and non-empty.
        if (protectedPaths.isProtected(asset.dest) && fileIsPresentAndNonEmpty(dest)) {
            log.debug("smrt sync: skipping protected {}", asset.dest)
            return
        }
        downloadIfNeeded(dest, asset.sha1, asset.sizeBytes, asset.source, "asset ${asset.dest}")
    }

    /**
     * Resolves [relative] against [root] and rejects entries that
     * escape the root via `..` segments or absolute paths. A hostile
     * or buggy manifest could otherwise hand the launcher
     * `../../../etc/cron.d/payload` and end up overwriting arbitrary
     * files writable by the launcher process. The mirror is trusted
     * but the boundary check is cheap and means a single bad
     * manifest entry can never escape the per-instance directory.
     *
     * **Threat model**: defends against MANIFEST-DRIVEN traversal
     * (a bad/hostile mirror manifest entry). Does NOT defend against
     * a pre-existing symlink inside `root` that points outside --
     * the lexical [Path.normalize] check is purely string-based, so
     * `<root>/config -> /opt/shared-configs` followed by manifest
     * entry `config/foo.cfg` writes to /opt/shared-configs/foo.cfg
     * even though the lexical check passes. Symlinks under `<root>`
     * are assumed user-installed and trusted; if the threat model
     * ever broadens (multi-tenant installs, sandboxed sync), switch
     * to `toRealPath(NOFOLLOW_LINKS)` per parent component before
     * the startsWith comparison.
     */
    private fun resolveSafe(root: Path, relative: String, label: String): Path {
        val resolved = root.resolve(relative).normalize()
        val rootNormalized = root.normalize()
        if (!resolved.startsWith(rootNormalized)) {
            throw IOException(
                "smrt manifest entry $label resolves outside the instance " +
                    "directory ($resolved); refusing to write."
            )
        }
        return resolved
    }

    private suspend fun downloadIfNeeded(
        dest: Path,
        expectedSha1: String,
        expectedSize: Long,
        source: SmrtSource,
        label: String,
    ) {
        if (source is SmrtSource.Unknown) {
            // Forward-compat: a source type this launcher version does not
            // understand. Skip the entry instead of failing the whole sync.
            log.warn("smrt sync: skipping {} -- unsupported source type; update the launcher to install it", label)
            return
        }
        if (isUpToDate(dest, expectedSha1, expectedSize)) {
            return
        }
        val url = resolveUrl(source)
        log.debug("smrt sync: fetching {} <- {}", label, url)
        Files.createDirectories(dest.parent)
        downloadToFile(url, dest)
        val onDiskSha = sha1Of(dest)
        if (!onDiskSha.equals(expectedSha1, ignoreCase = true)) {
            // Loud failure: delete the bad bytes so a retry refetches,
            // and surface the mismatch to the user instead of silently
            // serving wrong content.
            runCatching { fileOpRetry("smrt drop bad download $label") { Files.deleteIfExists(dest) } }
            throw IOException(
                "$label sha1 mismatch after download: expected $expectedSha1, got $onDiskSha"
            )
        }
    }

    /**
     * Resolves a [SmrtSource] to an actual download URL. The two
     * mirror-hosted variants carry the URL inline in the manifest;
     * `modrinth` needs a round-trip to Modrinth's API to fetch the
     * version's primary file URL. Picks the file flagged `primary:
     * true`, falling back to `files[0]` only if no entry is marked
     * primary -- Modrinth versions often ship multiple artifacts
     * (sources, deobf, signatures) and the first is not guaranteed
     * to be the installable one.
     */
    private suspend fun resolveUrl(source: SmrtSource): String = when (source) {
        is SmrtSource.SmrtCache  -> source.url
        is SmrtSource.SmrtStatic -> source.url
        is SmrtSource.Modrinth   -> {
            val v = modrinth.resolveVersion(source.projectId, source.versionId)
            v.primaryFile().url
        }
        // Unreachable: downloadIfNeeded skips Unknown before resolving a URL.
        // Kept exhaustive so a new SmrtSource variant forces a decision here.
        is SmrtSource.Unknown    -> error("resolveUrl called on an unsupported source")
    }

    /**
     * Up-to-date check: file exists, right size, right sha1. Cheap
     * shortcut to skip downloads on re-sync of the same manifest.
     * Size check first so a totally wrong file fails fast without a
     * full hash walk.
     */
    private fun isUpToDate(dest: Path, expectedSha1: String, expectedSize: Long): Boolean {
        if (!Files.exists(dest) || !Files.isRegularFile(dest)) return false
        if (Files.size(dest) != expectedSize) return false
        return sha1Of(dest).equals(expectedSha1, ignoreCase = true)
    }

    private fun fileIsPresentAndNonEmpty(p: Path): Boolean =
        Files.exists(p) && Files.isRegularFile(p) && Files.size(p) > 0L

    private suspend fun downloadToFile(url: String, dest: Path) {
        val tmp = dest.resolveSibling("${dest.fileName}.tmp")
        runCatching { Files.deleteIfExists(tmp) }
        client.downloadStreaming(url) { channel ->
            FileOutputStream(tmp.toFile()).use { out ->
                val buf = ByteArray(64 * 1024)
                while (!channel.isClosedForRead) {
                    val n = channel.readAvailable(buf, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                }
            }
        }
        // Non-atomic REPLACE_EXISTING on FAT32/SMB can leave a 0-byte
        // dest after power loss; Forge then classloads garbage.
        fileOpRetry("smrt download commit ${dest.fileName}") {
            try {
                Files.move(
                    tmp,
                    dest,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                log.warn(
                    "Filesystem at {} does not support ATOMIC_MOVE; non-atomic fallback may leave a 0-byte file on crash",
                    dest.parent,
                )
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: FileAlreadyExistsException) {
                // Java spec allows ATOMIC_MOVE to ignore REPLACE_EXISTING; some
                // providers then refuse and raise FileAlreadyExistsException
                // when dest already exists. Re-sync over an existing jar would
                // hard-fail without this fallback.
                Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }

    companion object {
        /**
         * The wire-format generation this client understands. Mirror
         * may serve a higher schema_version after a wire-incompatible
         * change; this client must refuse rather than misinterpret.
         */
        const val EXPECTED_SCHEMA = 2

        private const val SOURCE_MARKER_FILE = ".nexira-sync-source"
        private const val SOURCE_MIRROR = "mirror"
    }
}
