package hivens.launcher.imports

import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.InstanceRuntime
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.launcher.runtime.RuntimeProvisioner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID

/**
 * Turns a [DiscoveredInstance] found in a foreign launcher into a Nexira
 * [PackInstance]. Sibling of the archive installers (CurseForge / mrpack): same
 * end state (a registered Local instance that launches through the shared
 * [RuntimeProvisioner]), but the source is an on-disk game directory rather than
 * an archive.
 *
 * Two move policies, deliberately different:
 *  - **Instance content** (mods / config / saves / resource+shader packs / loose
 *    files) is COPIED. The new instance owns it; the foreign launcher keeps its
 *    own copy, and an edit on one side must not bleed into the other.
 *  - **The shared runtime** (vanilla assets + libraries) is HARDLINKED into
 *    Nexira's shared roots when the source carries a vanilla-layout tree (the
 *    Mojang launcher / TLauncher `.minecraft`). Those files are immutable and
 *    content-addressed, so sharing the inode is safe and skips a multi-GB
 *    re-download -- "migrate the game itself". [RuntimeProvisioner.ensureRuntime]
 *    then fills only the gaps, since it is idempotent on already-present files.
 *
 * The MC version must be known ([DiscoveredInstance.mcVersion]); FTB and Prism
 * carry it, so those import today. Sources that cannot report it yet (a bare
 * vanilla `.minecraft`, Modrinth App whose metadata lives in `app.db`) fail with
 * a clear message rather than guessing.
 */
class ForeignInstanceImporter(
    private val runtimeProvisioner: RuntimeProvisioner,
    private val javaManager: IJavaManager,
    private val repository: IPackRepository,
    private val dataDir: Path,
    private val librariesDir: Path,
    private val assetsDir: Path,
) {
    private val log = LoggerFactory.getLogger(ForeignInstanceImporter::class.java)

    suspend fun import(
        instance: DiscoveredInstance,
        onReserveDir: (Path) -> Unit = {},
        progress: (current: Int, total: Int, file: String) -> Unit = { _, _, _ -> },
    ): PackInstance = withContext(Dispatchers.IO) {
        val mc = instance.mcVersion?.takeIf { it.isNotBlank() }
            ?: throw IOException(
                "Cannot import '${instance.displayName}' from ${instance.launcher.displayName}: " +
                    "its Minecraft version could not be determined.",
            )
        val displayName = instance.displayName.ifBlank { instance.gameDir.fileName.toString() }
        val instanceId = UUID.randomUUID().toString()
        val instanceDirName = sanitize("$displayName-$instanceId")
        val clientDir = dataDir.resolve("instances").resolve(instanceDirName)
        onReserveDir(clientDir)
        Files.createDirectories(clientDir)
        log.info("import: '{}' from {} ({} {} on {}) -> {}",
            displayName, instance.launcher, instance.loader ?: "vanilla", instance.loaderVersion, mc, clientDir)

        copyInstanceContent(instance.gameDir, clientDir, progress)
        seedSharedRuntime(instance.gameDir, mc, progress)
        runtimeProvisioner.ensureRuntime(mc, instance.loader, instance.loaderVersion.orEmpty(), progress)

        val packInstance = PackInstance(
            id = instanceId,
            packRef = PackReference(origin = PackOrigin.Local, id = sanitize(displayName).lowercase(), version = null),
            displayName = displayName,
            instanceDirName = instanceDirName,
            createdAtEpoch = Instant.now().epochSecond,
            runtime = InstanceRuntime(),
            notes = "Imported from ${instance.launcher.displayName}.",
            cachedManifest = CachedManifestSnapshot(
                minecraftVersion = mc,
                loaderName = instance.loader ?: "vanilla",
                loaderVersion = instance.loaderVersion.orEmpty(),
                javaMajor = javaManager.detectJavaVersion(mc),
            ),
        )
        repository.put(packInstance)
        log.info("import: registered instance {}", instanceId)
        packInstance
    }

    // Top-level names never carried into the new instance: the shared runtime
    // (deduped separately), plus logs / caches / launcher bookkeeping.
    private val skipTopLevel = setOf(
        "assets", "libraries", "versions", "bin", "natives", "runtime",
        "logs", "crash-reports", "cache", ".fabric", ".mixin.out", ".quilt",
        "downloads", ".ftba", "instance.cfg", "mmc-pack.json", "instance.json", ".ds_store",
    )

    private suspend fun copyInstanceContent(
        src: Path,
        dest: Path,
        progress: (Int, Int, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var count = 0
        Files.newDirectoryStream(src).use { top ->
            for (child in top) {
                val name = child.fileName.toString()
                val lower = name.lowercase()
                if (lower in skipTopLevel || lower.startsWith("launcher_") || lower.startsWith("bootstrap_log")) continue
                Files.walk(child).use { tree ->
                    for (path in tree) {
                        currentCoroutineContext().ensureActive()
                        val rel = src.relativize(path)
                        val target = dest.resolve(rel.toString())
                        if (Files.isDirectory(path)) {
                            Files.createDirectories(target)
                        } else if (Files.isRegularFile(path)) {
                            Files.createDirectories(target.parent)
                            runCatching { Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING) }
                                .onFailure { log.warn("import: skipped uncopyable file {}", path, it) }
                            progress(++count, 0, rel.toString())
                        }
                    }
                }
            }
        }
        log.info("import: copied {} content files", count)
    }

    /**
     * Hardlink a vanilla-layout runtime under [src] into the shared roots. No-op
     * unless [src] actually carries `assets/objects` (so FTB/Prism instances,
     * whose runtime lives in the launcher's own cache, simply fall through to
     * ensureRuntime). Existing shared-root files are left untouched.
     */
    private suspend fun seedSharedRuntime(
        src: Path,
        mc: String,
        progress: (Int, Int, String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val srcAssets = src.resolve("assets")
        val srcLibs = src.resolve("libraries")
        if (!Files.isDirectory(srcAssets.resolve("objects")) && !Files.isDirectory(srcLibs)) return@withContext

        var linked = 0
        if (Files.isDirectory(srcAssets)) linked += mirrorTree(srcAssets, assetsDir) { progress(it, 0, "assets") }
        if (Files.isDirectory(srcLibs)) linked += mirrorTree(srcLibs, librariesDir) { progress(it, 0, "libraries") }
        // The vanilla client jar lives at versions/<mc>/<mc>.jar in the source but
        // at the maven coordinate net.minecraft:minecraft:<mc> in the shared roots.
        val srcClient = src.resolve("versions").resolve(mc).resolve("$mc.jar")
        val destClient = librariesDir.resolve("net/minecraft/minecraft/$mc/minecraft-$mc.jar")
        if (Files.isRegularFile(srcClient) && !Files.exists(destClient)) {
            Files.createDirectories(destClient.parent)
            linkOrCopy(srcClient, destClient)
            linked++
        }
        log.info("import: seeded {} runtime files into the shared roots from {}", linked, src)
    }

    /** Hardlink-or-copy every regular file under [from] into [to], skipping ones already present. */
    private suspend fun mirrorTree(from: Path, to: Path, onProgress: (Int) -> Unit): Int = withContext(Dispatchers.IO) {
        var n = 0
        Files.walk(from).use { tree ->
            for (path in tree) {
                currentCoroutineContext().ensureActive()
                if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) continue
                val target = to.resolve(from.relativize(path).toString())
                if (Files.exists(target)) continue
                Files.createDirectories(target.parent)
                linkOrCopy(path, target)
                if (++n % 200 == 0) onProgress(n)
            }
        }
        n
    }

    /** Prefer a hardlink (shared inode, zero extra bytes); fall back to a copy across filesystems. */
    private fun linkOrCopy(src: Path, dest: Path) {
        try {
            Files.createLink(dest, src)
        } catch (_: UnsupportedOperationException) {
            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.FileSystemException) {
            // Cross-device link (EXDEV) or a filesystem without hardlinks -> copy.
            runCatching { Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING) }
                .onFailure { log.warn("import: could not seed {}", dest, it) }
        }
    }

    private fun sanitize(raw: String): String = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
}
