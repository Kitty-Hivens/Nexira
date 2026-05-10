package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Out-of-band update-channel metadata, fetched separately from the GitHub
 * Releases API so we can mark older releases as mandatory after the fact —
 * without re-publishing them. Lives at
 * `https://raw.githubusercontent.com/Kitty-Hivens/Aura-Launcher/stable/meta/update-channel.json`.
 *
 * Both fields are optional; an absent file or absent fields mean "no floor",
 * which is the safe default for `UpdateService` to treat as a normal
 * (non-mandatory) update path.
 */
@Serializable
data class UpdateChannelMeta(
    /**
     * Lowest version a user is allowed to keep running. Anything strictly
     * below this is force-upgraded to the latest available release for the
     * user's selected channel (stable or prerelease).
     */
    @SerialName("mandatory_min_version") val mandatoryMinVersion: String? = null,
    /** Human-readable reason shown in the blocking dialog banner. */
    @SerialName("reason")                val reason: String? = null
)
