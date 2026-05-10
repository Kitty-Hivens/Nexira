package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Information about available launcher updates.
 *
 * [highlights] holds the user-facing TL;DR pulled from `### Highlights` in
 * CHANGELOG.md (via the release manifest). It's null for older releases
 * that pre-date the convention; the dialog falls back to [changelog]
 * (which is the aggregated `## What's Changed` body of every release between
 * the user's current version and the latest one).
 */
@Serializable
data class LauncherUpdate(
    val version: String,
    val downloadUrl: String,
    val checksum: String,
    val changelog: String,
    val highlights: String? = null,
    /** Public GitHub release page; opened by the "View on GitHub" button in the update dialog. */
    val releasePageUrl: String,
    val isCritical: Boolean,
    /**
     * True when the installed launcher version is below `mandatory_min_version`
     * published in `meta/update-channel.json` AND the user has not opted out
     * via the experimental settings. The UI must show a non-dismissable
     * dialog whose only options are "Install" or "Exit".
     */
    val isMandatory: Boolean = false,
    /** Optional human-readable reason from `update-channel.json` (shown in the blocking banner). */
    val mandatoryReason: String? = null
)
