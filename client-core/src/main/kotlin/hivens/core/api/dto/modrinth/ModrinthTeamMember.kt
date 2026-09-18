package hivens.core.api.dto.modrinth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One person or organisation credited on a project, from
 * `GET /v2/project/{id}/members`.
 *
 * Its own call because the project record carries no authors at all: a page that
 * only reads the project can name what a mod runs on and not who wrote it, which
 * is the question a reader asks first about an unfamiliar one.
 */
@Serializable
data class ModrinthTeamMember(
    @SerialName("team_id") val teamId: String = "",
    val user: ModrinthUser = ModrinthUser(),
    /** The project's own word for what this person does, "Owner" included. */
    val role: String = "",
    /**
     * False for an invitation nobody answered. Such a member is not a credit yet
     * and the catalogue's own page leaves them out, so this one does too.
     */
    val accepted: Boolean = true,
)

@Serializable
data class ModrinthUser(
    val id: String = "",
    val username: String = "",
    val name: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)
