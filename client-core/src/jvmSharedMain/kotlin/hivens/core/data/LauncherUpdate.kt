package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Available launcher update. [highlights] is the release's player-facing note,
 * written for the person using the launcher rather than cut from the
 * engineering log: `CHANGELOG_EN.md` and its translations. Null for a release
 * that has none (a nightly, or anything predating the file), and the UI then
 * falls back to [changelog] -- the aggregated `## What's Changed` body across
 * the versions between the installed one and the latest.
 */
@Serializable
data class LauncherUpdate(
    val version: String,
    val downloadUrl: String,
    val checksum: String,
    val changelog: String,
    val highlights: String? = null,
    val releasePageUrl: String,
    val isCritical: Boolean,
    /**
     * True when installed version is below `mandatory_min_version` in
     * `meta/update-channel.json` AND the user has not opted out via
     * experimental settings. UI must show a non-dismissable dialog
     * whose only options are "Install" or "Exit".
     */
    val isMandatory: Boolean = false,
    /** Optional human-readable reason from `update-channel.json`. */
    val mandatoryReason: String? = null,
)
