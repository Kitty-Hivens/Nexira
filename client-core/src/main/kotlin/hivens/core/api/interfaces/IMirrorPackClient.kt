package hivens.core.api.interfaces

import hivens.core.api.dto.smrt.SmrtBuildDiff
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary

/**
 * Mirror pack read slice the UI and launch flow consume: manifests (latest and
 * pinned-version) plus the pack summary. The launcher's mirror client
 * implements the full surface (downloads, listings, Modrinth lookups); callers
 * that only read inject this slice so tests can fake it.
 */
interface IMirrorPackClient {
    suspend fun fetchManifest(packId: String): SmrtPackManifest
    suspend fun fetchManifestVersion(packId: String, version: String): SmrtPackManifest
    suspend fun fetchSummary(packId: String): SmrtPackSummary

    /** The mirror's structured change summary between two builds (registry-enriched version labels). */
    suspend fun fetchDiff(packId: String, from: String, to: String): SmrtBuildDiff
}
