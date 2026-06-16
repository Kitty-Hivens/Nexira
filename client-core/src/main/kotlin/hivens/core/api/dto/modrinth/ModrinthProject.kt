package hivens.core.api.dto.modrinth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Minimal Modrinth `/v2/project/{id}` response subset. We currently
 * read only the project icon URL (for the Library PackDetail Content
 * tab when a mod's `display.iconUrl` is absent), but the surrounding
 * fields are likely candidates for future use -- description,
 * title, body -- so the tolerant decoder will pick them up without a
 * schema bump.
 */
@Serializable
data class ModrinthProject(
    val id: String,
    val slug: String,
    val title: String,
    @SerialName("icon_url") val iconUrl: String? = null,
)
