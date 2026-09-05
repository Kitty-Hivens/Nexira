package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Machine-readable description of a GitHub release. Published by CI as
 * `release-manifest.json` alongside the binaries; `UpdateService`
 * REQUIRES it -- refuses to auto-install when the manifest is missing
 * or does not list the selected asset, and treats an empty checksum as
 * a hard error rather than a silent skip.
 *
 * Releases without a manifest cannot be auto-updated to and require
 * manual reinstallation.
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
     * The release's player-facing note, as CI found it in `CHANGELOG_EN.md`.
     *
     * A frozen copy, and the fallback rather than the source: the launcher
     * prefers the reader's own language read live off `stable`, so a note
     * corrected or translated after the release still reaches them. This is
     * what a launcher with no route to raw.githubusercontent falls back on.
     * Null when the release has no player notes, and clients then fall back to
     * the full changelog.
     */
    @SerialName("highlights") val highlights: String? = null,

    @SerialName("assets") val assets: List<ReleaseAsset>,
)

@Serializable
data class ReleaseAsset(
    @SerialName("name") val name: String,
    /** Coarse platform tag: "windows" | "macos" | "linux". */
    @SerialName("platform") val platform: String,
    /** Distribution kind: "installer" | "portable" | "appimage" | "dmg". */
    @SerialName("kind") val kind: String,
    @SerialName("sha256") val sha256: String,
    @SerialName("size") val size: Long,
)
