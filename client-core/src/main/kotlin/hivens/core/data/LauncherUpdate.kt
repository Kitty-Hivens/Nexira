package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Available launcher update. [highlights] is the user-facing TL;DR pulled
 * from the release's `### Highlights` block; null for legacy releases (UI
 * falls back to [changelog], the aggregated `## What's Changed` body
 * across versions between the installed and the latest).
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
