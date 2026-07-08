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
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Creates an empty local pack from scratch: pick a Minecraft version and loader,
 * get a registered, launchable [PackInstance] with nothing in it -- the starting
 * point for authoring a pack by hand (add mods from the Content tab's Modrinth
 * browser or drop in local jars). The from-nothing sibling of the installers /
 * [ForeignInstanceImporter], which all start from existing content.
 *
 * [loader] is the LoaderRegistry id (`forge` / `neoforge` / `fabric` / `quilt`)
 * or null for vanilla. A blank [loaderVersion] asks the resolver for its default
 * / latest where it supports that (Fabric does; Forge-legacy best-effort) --
 * ensureRuntime surfaces an unresolvable loader as an error rather than a broken
 * instance.
 */
class LocalPackCreator(
    private val runtimeProvisioner: RuntimeProvisioner,
    private val javaManager: IJavaManager,
    private val repository: IPackRepository,
    private val dataDir: Path,
) {
    private val log = LoggerFactory.getLogger(LocalPackCreator::class.java)

    suspend fun create(
        name: String,
        mcVersion: String,
        loader: String?,
        loaderVersion: String = "",
        onReserveDir: (Path) -> Unit = {},
        progress: (current: Int, total: Int, file: String) -> Unit = { _, _, _ -> },
    ): PackInstance = withContext(Dispatchers.IO) {
        val mc = mcVersion.trim().takeIf { it.isNotEmpty() }
            ?: throw IOException("Cannot create a pack without a Minecraft version.")
        val displayName = name.trim().ifEmpty { "New pack" }
        val loaderId = loader?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it != "vanilla" }
        val instanceId = UUID.randomUUID().toString()
        val instanceDirName = sanitize("$displayName-$instanceId")
        val clientDir = dataDir.resolve("instances").resolve(instanceDirName)
        onReserveDir(clientDir)
        // Seed the folders the Content-tab browser writes into, so adding the
        // first mod does not have to create them.
        Files.createDirectories(clientDir.resolve("mods"))
        Files.createDirectories(clientDir.resolve("config"))
        log.info("create: '{}' ({} {} on {}) -> {}", displayName, loaderId ?: "vanilla", loaderVersion, mc, clientDir)

        runtimeProvisioner.ensureRuntime(mc, loaderId, loaderVersion, progress)

        val instance = PackInstance(
            id = instanceId,
            packRef = PackReference(origin = PackOrigin.Local, id = sanitize(displayName).lowercase(), version = null),
            displayName = displayName,
            instanceDirName = instanceDirName,
            createdAtEpoch = Instant.now().epochSecond,
            runtime = InstanceRuntime(),
            notes = "Created locally.",
            cachedManifest = CachedManifestSnapshot(
                minecraftVersion = mc,
                loaderName = loaderId ?: "vanilla",
                loaderVersion = loaderVersion,
                javaMajor = javaManager.detectJavaVersion(mc),
            ),
        )
        repository.put(instance)
        log.info("create: registered instance {}", instanceId)
        instance
    }

    private fun sanitize(raw: String): String = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
}
