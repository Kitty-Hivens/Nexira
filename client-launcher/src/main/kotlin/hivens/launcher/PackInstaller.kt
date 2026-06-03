package hivens.launcher

import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.api.dto.smrt.toDomain
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.InstanceRuntime
import hivens.core.data.OptionalContentRules
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.smrt.SmrtSyncService
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

/**
 * Composes a mirror-hosted pack install: download via
 * [SmrtSyncService], record the resulting [PackInstance] in the
 * launcher's [IPackRepository]. Each call creates a NEW instance
 * (own UUID, own instance directory), so installing the same pack
 * twice yields two independent installs the way Modrinth /
 * Prism do -- the user can fork-style experiment without touching
 * the original.
 *
 * The progress callback wraps [SmrtSyncService]'s progress hook so
 * the UI can render a single (current, total, filename) tuple
 * without knowing the internal sync stages.
 *
 * Exceptions: any failure inside [SmrtSyncService.sync] (SHA
 * mismatch, network, schema bump) propagates. We do NOT call
 * `repository.put(...)` on a failed sync -- a half-downloaded
 * pack should not appear in Library as if usable. The half-written
 * directory under `instances/` is left behind on disk for forensics;
 * a follow-up cleanup pass can remove orphan instance dirs that
 * have no matching registry entry.
 */
class PackInstaller(
    private val syncService: SmrtSyncService,
    private val runtimeProvisioner: RuntimeProvisioner,
    private val repository: IPackRepository,
    private val dataDir: Path,
) {
    private val log = LoggerFactory.getLogger(PackInstaller::class.java)

    suspend fun install(
        packId: String,
        summary: SmrtPackSummary,
        manifest: SmrtPackManifest,
        progress: (current: Int, total: Int, filename: String) -> Unit = { _, _, _ -> },
    ): PackInstance {
        val instanceId  = UUID.randomUUID().toString()
        val instanceDir = sanitizeInstanceDir("$packId-$instanceId")
        val clientDir   = dataDir.resolve("instances").resolve(instanceDir)

        log.info("install: pack={} version={} -> instance={} dir={}",
            packId, manifest.packVersion, instanceId, clientDir)

        // Optional-content defaults from the manifest: each optional mod
        // (required=false) seeds at its default_enabled. Sync places toggled-off
        // optionals as `.disabled` so a later flip is a rename, not a re-download.
        val optionalToggles = OptionalContentRules.defaultToggles(manifest.mods)
        syncService.sync(
            packId    = packId,
            clientDir = clientDir,
            progress  = progress,
            enabledState = OptionalContentRules.enabledState(manifest.mods, optionalToggles),
        )

        // Provision the canonical runtime (vanilla + loader libraries + client +
        // assets) into the shared roots -- once per MC version, shared across
        // instances. Heavy first-run download with progress; idempotent after.
        runtimeProvisioner.ensureRuntime(
            mcVersion = manifest.minecraft.version,
            loaderName = manifest.loader.name,
            loaderVersion = manifest.loader.version,
            progress = progress,
        )

        val instance = PackInstance(
            id                    = instanceId,
            packRef               = PackReference(
                origin  = PackOrigin.Mirror,
                id      = packId,
                version = manifest.packVersion,
            ),
            displayName           = summary.displayName,
            instanceDirName       = instanceDir,
            createdAtEpoch        = Instant.now().epochSecond,
            lastPlayedEpochOrZero = 0L,
            pinnedPackVersion     = manifest.packVersion,
            runtime               = InstanceRuntime(adaptiveMemory = true),  // new packs default to Auto heap
            optionalContent       = optionalToggles,
            forkedFrom            = null,
            notes                 = "",
            cachedManifest        = CachedManifestSnapshot(
                minecraftVersion = manifest.minecraft.version,
                loaderName       = manifest.loader.name,
                loaderVersion    = manifest.loader.version,
                javaMajor        = manifest.java.major,
                authRequirement  = manifest.auth?.toDomain(),
            ),
        )
        repository.put(instance)
        log.info("install: registered instance {} in repository", instanceId)
        return instance
    }

    /**
     * Filesystem-safe instance directory name. Strip characters
     * that would trip case-insensitive filesystems (Windows / HFS+)
     * or shell tooling, and bound the length so the absolute path
     * stays under typical PATH_MAX even for deep data-dir roots.
     */
    private fun sanitizeInstanceDir(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_").take(96)
}
