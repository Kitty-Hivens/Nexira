package hivens.core.api.dto.modrinth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of Modrinth `/v2/project/{id}`. The Content-tab icon resolver reads
 * only [iconUrl]; the catalogue detail render adds [description] (tagline),
 * [body] (long markdown), [gallery] and [categories]. Tolerant decoder
 * (`ignoreUnknownKeys`) ignores the rest of the rich payload.
 */
@Serializable
data class ModrinthProject(
    val id: String,
    val slug: String,
    val title: String,
    /** Short one-line summary -- the page tagline. */
    val description: String = "",
    /** Long-form CommonMark project body. */
    val body: String = "",
    val categories: List<String> = emptyList(),
    @SerialName("icon_url") val iconUrl: String? = null,
    val gallery: List<ModrinthGalleryImage> = emptyList(),
)

@Serializable
data class ModrinthGalleryImage(
    val url: String,
    val featured: Boolean = false,
    val title: String? = null,
)
