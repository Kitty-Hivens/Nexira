package hivens.core.api.dto.modrinth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One entry of `GET /v2/tag/game_version`, the catalogue's own list of Minecraft
 * versions in release order, newest first.
 *
 * The page needs it to fold a project's fifty-eight supported versions into four
 * ranges, which cannot be done from the project alone: only this list knows that
 * 1.21.2 follows 1.21.1, and that a snapshot sits between releases rather than
 * after them. [versionType] is `release`, `snapshot`, `alpha` or `beta`, and only
 * the first of those takes part in a range.
 */
@Serializable
data class ModrinthGameVersion(
    val version: String,
    @SerialName("version_type") val versionType: String = "",
    val major: Boolean = false,
    /**
     * When it shipped. Needed to answer whether a snapshot is NEWER than the
     * newest release a project supports, which is the only snapshot worth a chip:
     * one that predates the releases is already covered by them.
     */
    val date: String? = null,
) {
    companion object {
        const val RELEASE = "release"
        const val SNAPSHOT = "snapshot"
    }
}
