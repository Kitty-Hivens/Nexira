package hivens.core.api.dto.modrinth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Subset of Modrinth `/v2/project/{id}`. The Content-tab icon resolver reads
 * only [iconUrl]; the catalogue detail render adds [description] (tagline),
 * [body] (long markdown), [gallery] and [categories]; the project page reads
 * the rest. Tolerant decoder (`ignoreUnknownKeys`) ignores what is left.
 *
 * [clientSide] and [serverSide] are `required` / `optional` / `unsupported` /
 * `unknown`, and they are read as a PAIR: neither half means anything alone,
 * since "client: required" is a client-only mod and a client-and-server mod
 * alike depending on what the other half says.
 */
@Serializable
data class ModrinthProject(
    val id: String,
    val slug: String,
    val title: String,
    /** `mod` / `resourcepack` / `shader` / ... -- also the URL path segment (`modrinth.com/<type>/<slug>`). */
    @SerialName("project_type") val projectType: String = "mod",
    /** Short one-line summary -- the page tagline. */
    val description: String = "",
    /** Long-form CommonMark project body. */
    val body: String = "",
    val categories: List<String> = emptyList(),
    /**
     * Tags beyond the primary ones. The catalogue's own page shows both together
     * in one block, so a reader never has to know which list a tag came from.
     */
    @SerialName("additional_categories") val additionalCategories: List<String> = emptyList(),
    val license: ModrinthLicense? = null,
    @SerialName("icon_url") val iconUrl: String? = null,
    val gallery: List<ModrinthGalleryImage> = emptyList(),
    val downloads: Long = 0,
    val followers: Long = 0,
    /** Loader ids the project publishes for, or `minecraft` / `iris` for the other kinds. */
    val loaders: List<String> = emptyList(),
    @SerialName("game_versions") val gameVersions: List<String> = emptyList(),
    @SerialName("client_side") val clientSide: String = "unknown",
    @SerialName("server_side") val serverSide: String = "unknown",
    @SerialName("issues_url") val issuesUrl: String? = null,
    @SerialName("source_url") val sourceUrl: String? = null,
    @SerialName("wiki_url") val wikiUrl: String? = null,
    @SerialName("discord_url") val discordUrl: String? = null,
    @SerialName("donation_urls") val donationUrls: List<ModrinthDonation> = emptyList(),
    /** When the project first appeared, which is not when its newest build did. */
    val published: String? = null,
    val updated: String? = null,
)

/** One "support the author" link. [id] names the platform (`patreon`, `ko-fi`, `github`). */
@Serializable
data class ModrinthDonation(
    val id: String = "",
    val platform: String = "",
    val url: String = "",
)

@Serializable
data class ModrinthLicense(
    val id: String? = null,
    val name: String? = null,
    val url: String? = null,
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
    /** Author's caption for the shot. Often the only thing saying what is being looked at. */
    val description: String? = null,
)
