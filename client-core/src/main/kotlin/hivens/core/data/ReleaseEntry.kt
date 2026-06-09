package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * One release row in the update manager's version list. Lightweight: it
 * describes a release for display + selection without fetching its manifest
 * (the manifest, with the SHA-256 the installer verifies, is fetched only when
 * the user actually picks a version -- see `UpdateService.prepareUpdate`).
 *
 * [installable] means an asset exists for the current OS; the install can still
 * be refused later if that release ships no verifiable manifest entry.
 */
@Serializable
data class ReleaseEntry(
    val version: String,
    val channel: ReleaseChannel,
    val isCurrent: Boolean,
    val installable: Boolean,
    val releasePageUrl: String,
)
