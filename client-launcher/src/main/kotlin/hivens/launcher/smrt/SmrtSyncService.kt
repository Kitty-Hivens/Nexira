package hivens.launcher.smrt

import hivens.core.api.dto.smrt.SmrtAssetEntry
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtSource
import hivens.launcher.ProtectedPaths
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

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
    private val protectedPaths: ProtectedPaths,
) {
    private val log = LoggerFactory.getLogger(SmrtSyncService::class.java)

    suspend fun sync(
        packId: String,
        clientDir: Path,
        progress: ((current: Int, total: Int, filename: String) -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
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
        val total = manifest.mods.size + manifest.assets.size
        var current = 0

        for (mod in manifest.mods) {
            current++
            progress?.invoke(current, total, mod.filename)
            syncMod(mod, clientDir)
        }
        for (asset in manifest.assets) {
            current++
            progress?.invoke(current, total, asset.dest)
            syncAsset(asset, clientDir)
        }

        // Prune jars in mods/ that the manifest does not declare. Without
        // this, a switch from the SC sync to the mirror sync leaves the
        // previous SC payload (e.g. SC's proprietary Smarty jar) sitting
        // next to the mirror-published files; both register the same
        // FML channel and the game crashes with "That channel is already
        // registered". Scope is mods/ only; static-asset trees are left
        // alone so user-added resource packs and configs survive.
        pruneOrphanMods(clientDir, manifest.mods.map { it.filename }.toSet())
    }

    private fun pruneOrphanMods(clientDir: Path, expected: Set<String>) {
        val modsDir = clientDir.resolve("mods")
        if (!Files.isDirectory(modsDir)) return
        var removed = 0
        Files.walk(modsDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                .forEach { jar ->
                    if (jar.fileName.toString() !in expected) {
                        runCatching {
                            Files.delete(jar)
                            removed++
                            log.debug("smrt sync: pruned orphan jar {}", jar)
                        }.onFailure { log.warn("smrt sync: failed to prune {}", jar, it) }
                    }
                }
        }
        if (removed > 0) log.info("smrt sync: pruned {} orphan jar(s) from mods/", removed)
    }

    /**
     * Mods land at `mods/{filename}` regardless of SC's dual-tier
     * convention. Forge 1.12.2 scans both `mods/` and
     * `mods/{mcversion}/`, so flat placement still loads. Optional
     * mods that the user didn't enable (required=false and no future
     * toggle infrastructure yet) are still pulled in this v0 cut --
     * later iterations will respect a per-user opt-out map.
     */
    private suspend fun syncMod(mod: SmrtModEntry, clientDir: Path) {
        val dest = clientDir.resolve("mods").resolve(mod.filename)
        downloadIfNeeded(dest, mod.sha1, mod.sizeBytes, mod.source, "mod ${mod.filename}")
    }

    private suspend fun syncAsset(asset: SmrtAssetEntry, clientDir: Path) {
        // Protected paths (e.g. user-edited options.txt) are honored
        // here just like in the SC code path -- if the user has tuned
        // their FOV, sync must not overwrite. Protection only kicks in
        // when the file is already present and non-empty.
        if (protectedPaths.isProtected(asset.dest) && fileIsPresentAndNonEmpty(clientDir.resolve(asset.dest))) {
            log.debug("smrt sync: skipping protected {}", asset.dest)
            return
        }
        val dest = clientDir.resolve(asset.dest)
        downloadIfNeeded(dest, asset.sha1, asset.sizeBytes, asset.source, "asset ${asset.dest}")
    }

    private suspend fun downloadIfNeeded(
        dest: Path,
        expectedSha1: String,
        expectedSize: Long,
        source: SmrtSource,
        label: String,
    ) {
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
            runCatching { Files.deleteIfExists(dest) }
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
            val v = client.resolveModrinthVersion(source.projectId, source.versionId)
            v.primaryFile().url
        }
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
        Files.move(tmp, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun sha1Of(p: Path): String {
        val md = MessageDigest.getInstance("SHA-1")
        Files.newInputStream(p).use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /**
         * The wire-format generation this client understands. Mirror
         * may serve a higher schema_version after a wire-incompatible
         * change; this client must refuse rather than misinterpret.
         */
        const val EXPECTED_SCHEMA = 2
    }
}
