package hivens.core.api.dto.modrinth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One of the catalogue's categories, `GET /v2/tag/category`.
 *
 * [header] is the group the catalogue files it under for its [projectType]:
 * `categories`, `features`, `resolutions`, `performance impact`. The same name can
 * sit under two project types, so a category is only identified by the pair.
 * [icon] is an SVG drawn in `currentColor`, blank for a few.
 */
@Serializable
data class ModrinthCategoryTag(
    val name: String,
    @SerialName("project_type") val projectType: String = "",
    val header: String = "",
    val icon: String = "",
)

/** One of the catalogue's loaders, `GET /v2/tag/loader`, with the project types it publishes. */
@Serializable
data class ModrinthLoaderTag(
    val name: String,
    @SerialName("supported_project_types") val supportedProjectTypes: List<String> = emptyList(),
    val icon: String = "",
)
