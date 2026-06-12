package hivens.core.api.interfaces

import hivens.core.api.dto.smrt.SmrtPackManifest

/**
 * Mirror pack-manifest reads the launch flow needs. The launcher's mirror
 * client implements the full surface (downloads, listings, Modrinth lookups);
 * the controller only resolves a manifest, so it injects this slice.
 */
interface IMirrorPackClient {
    suspend fun fetchManifest(packId: String): SmrtPackManifest
    suspend fun fetchManifestVersion(packId: String, version: String): SmrtPackManifest
}
