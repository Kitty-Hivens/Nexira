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
    val isCritical: Boolean
)
