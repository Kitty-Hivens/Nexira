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
    /** Resized ~350px preview (`..._350.webp`) -- fine for the strip thumbnail. */
    val url: String,
    /** Original full-resolution upload. Use this for the hero + lightbox; [url] is
     *  a thumbnail and upscales to mush at full-window size. Older entries may omit
     *  it, so callers fall back to [url]. */
    @SerialName("raw_url") val rawUrl: String? = null,
    val featured: Boolean = false,
    val title: String? = null,
)
