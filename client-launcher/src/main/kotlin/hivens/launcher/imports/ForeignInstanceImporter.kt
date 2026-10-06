package hivens.launcher.imports

import hivens.launcher.instance.instanceDirName
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.InstanceRuntime
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.runtime.RuntimeSeed
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
 *  - **The shared runtime** (vanilla assets + libraries + client jar) is OFFERED
 *    to [RuntimeProvisioner.ensureRuntime] when the source carries a
 *    vanilla-layout tree (the Mojang launcher / TLauncher `.minecraft`). The
 *    provisioner hardlinks a file in only when its sha1 is the one Mojang's
 *    manifest gives for that path, and downloads the rest, so a multi-GB
 *    re-download is skipped without trusting foreign bytes. See [RuntimeSeed].
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
        val instanceDirName = instanceDirName(displayName, instanceId)
        val clientDir = dataDir.resolve("instances").resolve(instanceDirName)
        onReserveDir(clientDir)
        Files.createDirectories(clientDir)
        log.info("import: '{}' from {} ({} {} on {}) -> {}",
            displayName, instance.launcher, instance.loader ?: "vanilla", instance.loaderVersion, mc, clientDir)

        copyInstanceContent(instance.gameDir, clientDir, progress)
        val resolved = runtimeProvisioner.ensureRuntime(
            mc, instance.loader, instance.loaderVersion.orEmpty(),
            seed = runtimeSeedOf(instance.gameDir, mc),
            progress = progress,
        )

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
                // What was resolved, which is what the source named when it named one.
                loaderVersion = resolved.loaderVersion ?: instance.loaderVersion.orEmpty(),
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
     * The vanilla-layout runtime under [src], if it carries one, for the
     * provisioner to take what matches from. Null when there is none, so an FTB
     * or Prism instance, whose runtime lives in the launcher's own cache, simply
     * downloads.
     */
    private fun runtimeSeedOf(src: Path, mc: String): RuntimeSeed? {
        val assets = src.resolve("assets").takeIf { Files.isDirectory(it.resolve("objects")) }
        val libraries = src.resolve("libraries").takeIf { Files.isDirectory(it) }
        val client = src.resolve("versions").resolve(mc).resolve("$mc.jar").takeIf { Files.isRegularFile(it) }
        if (assets == null && libraries == null && client == null) return null
        return RuntimeSeed(librariesDir = libraries, assetsDir = assets, clientJar = client)
    }

    private fun sanitize(raw: String): String = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
}
