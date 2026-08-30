package hivens.launcher.curseforge

import hivens.core.api.dto.curseforge.CfManifest
import hivens.core.io.UnpackBudget
import hivens.core.io.UnpackLimits
import hivens.core.api.dto.curseforge.CfModLoader
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
 * Best-effort install of a CurseForge modpack export (`.zip` with a
 * `manifest.json`). Without the CurseForge API, the `files[]` (project/file ids)
 * cannot be resolved to download URLs, so only the `overrides/` tree (configs
 * and any jars the author bundled) installs; the count of unresolved mods is
 * recorded on the instance notes for the user to fetch manually. The resulting
 * instance is [PackOrigin.Local] -- with no API there is no version feed to
 * track for updates, so a tracked CurseForge origin would promise nothing.
 */
class CurseForgeZipInstaller(
    private val json: Json,
    private val javaManager: IJavaManager,
    private val runtimeProvisioner: RuntimeProvisioner,
    private val repository: IPackRepository,
    private val dataDir: Path,
) {
    private val log = LoggerFactory.getLogger(CurseForgeZipInstaller::class.java)

    suspend fun install(
        zip: Path,
        onReserveDir: (Path) -> Unit = {},
        progress: (current: Int, total: Int, filename: String) -> Unit = { _, _, _ -> },
    ): PackInstance = withContext(Dispatchers.IO) {
        ZipFile(zip.toFile()).use { z ->
            val manifestEntry = z.getEntry(MANIFEST)
                ?: throw IOException("not a CurseForge export: no $MANIFEST at the archive root")
            val manifest = json.decodeFromString(
                CfManifest.serializer(),
                z.getInputStream(manifestEntry).readBytes().decodeToString(),
            )
            val mcVersion = manifest.minecraft.version.ifBlank {
                throw IOException("CurseForge manifest has no minecraft.version")
            }
            val (loaderName, loaderVersion) = resolveCfLoader(manifest.minecraft.modLoaders)
            val displayName = manifest.name.ifBlank { "Imported pack" }

            val instanceId = UUID.randomUUID().toString()
            val instanceDirName = sanitize("$displayName-$instanceId")
            val clientDir = dataDir.resolve("instances").resolve(instanceDirName)
            Files.createDirectories(clientDir)
            onReserveDir(clientDir)

            // Only the overrides tree installs (configs + any bundled jars); the
            // project/file-id mods need the CF API we deliberately don't use.
            val overridesPrefix = manifest.overrides.trimEnd('/') + "/"
            extractOverrides(z, overridesPrefix, clientDir)

            runtimeProvisioner.ensureRuntime(mcVersion, loaderName, loaderVersion, progress)

            val unresolved = manifest.files.size
            val notes = if (unresolved > 0) {
                "Imported from CurseForge. $unresolved mod(s) reference CurseForge project/file ids " +
                    "and need a manual download (Nexira uses no CurseForge API key)."
            } else {
                "Imported from CurseForge."
            }
            val instance = PackInstance(
                id = instanceId,
                packRef = PackReference(
                    origin = PackOrigin.Local,
                    id = sanitize(displayName).lowercase(),
                    version = manifest.version.ifBlank { null },
                ),
                displayName = displayName,
                instanceDirName = instanceDirName,
                createdAtEpoch = Instant.now().epochSecond,
                pinnedPackVersion = manifest.version.ifBlank { null },
                runtime = InstanceRuntime(),
                notes = notes,
                cachedManifest = CachedManifestSnapshot(
                    minecraftVersion = mcVersion,
                    loaderName = loaderName ?: "vanilla",
                    loaderVersion = loaderVersion,
                    javaMajor = javaManager.detectJavaVersion(mcVersion),
                ),
            )
            repository.put(instance)
            log.info(
                "curseforge: registered instance {} ('{}'); overrides-only, {} mod(s) need manual download",
                instanceId, displayName, unresolved,
            )
            instance
        }
    }

    private fun extractOverrides(zip: ZipFile, prefix: String, clientDir: Path) {
        val budget = UnpackBudget(UnpackLimits.PACK_CONTENT, "CurseForge overrides")
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
    private fun safeResolve(base: Path, relative: String): Path {
        val root = base.normalize()
        val resolved = root.resolve(relative).normalize()
        if (!resolved.startsWith(root)) {
            throw SecurityException("CurseForge entry escapes the instance dir: $relative")
        }
        return resolved
    }

    private fun sanitize(raw: String): String = raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)

    private companion object {
        const val MANIFEST = "manifest.json"
    }
}

/**
 * Map the CurseForge primary mod-loader entry to a (LoaderRegistry id, version)
 * pair. CF ids are `name-version` (`forge-47.2.0`); returns (null, "") for a
 * vanilla pack or an unrecognized loader.
 */
internal fun resolveCfLoader(modLoaders: List<CfModLoader>): Pair<String?, String> {
    val primary = modLoaders.firstOrNull { it.primary } ?: modLoaders.firstOrNull() ?: return null to ""
    val dash = primary.id.indexOf('-')
    if (dash <= 0) return null to ""
    val name = primary.id.substring(0, dash)
    val version = primary.id.substring(dash + 1)
    val registryId = when (name) {
        "neoforge" -> "neoforge"
        "forge" -> "forge"
        "fabric" -> "fabric"
        "quilt" -> "quilt"
        else -> return null to ""
    }
    return registryId to version
}
