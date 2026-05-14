package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Machine-readable description of a GitHub release. Published by CI as
 * `release-manifest.json` alongside the binaries; the launcher REQUIRES it
 * — `UpdateService` refuses to auto-install when the manifest is missing or
 * doesn't list the selected asset (#186 hardening: empty checksum was
 * previously a silent skip).
 *
 * Older releases (pre-2.2.7-rc3) that ship without a manifest can no longer
 * be auto-updated to and require manual reinstall.
 */
@Serializable
data class ReleaseManifest(
    /** Format version of this manifest contract. Bump on incompatible field changes. */
    @SerialName("schemaVersion") val schemaVersion: Int = 1,

    /** Semver of the release (no leading "v"). */
    @SerialName("version") val version: String,

    /** ISO-8601 publish timestamp written by CI. */
    @SerialName("publishedAt") val publishedAt: String? = null,

    /**
     * User-facing one-paragraph summary extracted from the `### Highlights`
     * subsection of the version's CHANGELOG entry. Null when the entry has no
     * Highlights block — clients should fall back to the full changelog.
     */
    @SerialName("highlights") val highlights: String? = null,

    @SerialName("assets") val assets: List<ReleaseAsset>
)

@Serializable
data class ReleaseAsset(
    @SerialName("name") val name: String,
    /** "windows" | "macos" | "linux" — coarse platform tag. */
    @SerialName("platform") val platform: String,
    /** "installer" | "portable" | "appimage" | "dmg" — distribution kind. */
    @SerialName("kind") val kind: String,
    @SerialName("sha256") val sha256: String,
    @SerialName("size") val size: Long
)
