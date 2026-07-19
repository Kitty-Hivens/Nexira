package hivens.core.api.dto.smrt

import hivens.core.update.VersionChannel
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shape of `GET /v1/packs/{id}/manifest/versions`: the per-build listing
 * the mirror retains for a pack, newest first by publish date. The server order
 * is canonical -- release and SNAPSHOT builds interleave, and version-tuple
 * sorting misorders them (only the timestamps rank across channels). [latest]
 * names the build the bare manifest endpoint serves.
 */
@Serializable
data class SmrtManifestVersions(
    val latest: String? = null,
    val builds: List<SmrtManifestBuild> = emptyList(),
)

/**
 * One retained build in the listing (Modrinth version-object naming).
 * [fingerprint] hashes the shipped content set: equal NON-NULL fingerprints
 * mean two builds carry identical files (a label-only rebuild). The field is
 * absent on builds that predate it, so equality is meaningful only when both
 * sides are non-null.
 */
@Serializable
data class SmrtManifestBuild(
    @SerialName("version_number") val versionNumber: String,
    @SerialName("version_type") val versionType: String? = null,
    @SerialName("date_published") val datePublished: String? = null,
    val fingerprint: String? = null,
    /** Curator-authored release notes for this build (CommonMark); absent when none were given. */
    val changelog: String? = null,
    @SerialName("mods_count") val modsCount: Int = 0,
    @SerialName("assets_count") val assetsCount: Int = 0,
) {
    /** Channel of this build, derived from the version string when the field is absent or unknown. */
    val channel: VersionChannel get() = VersionChannel.of(versionType, versionNumber)
}
