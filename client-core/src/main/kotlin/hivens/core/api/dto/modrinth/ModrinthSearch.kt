package hivens.core.api.dto.modrinth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of Modrinth `/v2/search`. The decoder is tolerant
 * (`ignoreUnknownKeys`), so only the fields the catalogue card needs are
 * modelled; the rest of the rich hit payload is discarded.
 */
@Serializable
data class ModrinthSearchResponse(
    val hits: List<ModrinthSearchHit> = emptyList(),
    @SerialName("total_hits") val totalHits: Int = 0,
)

@Serializable
data class ModrinthSearchHit(
    @SerialName("project_id") val projectId: String,
    val slug: String = "",
    val title: String = "",
    /** Modrinth's short one-line summary -- maps to the card tagline. */
    val description: String = "",
    @SerialName("icon_url") val iconUrl: String? = null,
    val categories: List<String> = emptyList(),
)
