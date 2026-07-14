package hivens.core.api.catalogue

import hivens.core.data.PackOrigin

/**
 * Source-neutral pack-catalogue model. Every browsable source (the Hivens
 * mirror, Modrinth, later CurseForge) maps its own wire shape onto these so the
 * Browse UI stays source-agnostic; see [hivens.core.api.interfaces.IPackCatalogueService].
 */

/** A pack as it appears in a Browse grid. */
data class CataloguePack(
    val origin: PackOrigin,
    /** Source-local id: mirror `pack_id`, Modrinth project id. */
    val id: String,
    val title: String,
    val tagline: String,
    val iconUrl: String? = null,
    /** Wide screenshot for the card background; null falls back to procedural pixel art. */
    val bannerUrl: String? = null,
    val tags: List<String> = emptyList(),
    /** Target MC version when the source exposes one on a card (mirror); null otherwise. */
    val mcVersion: String? = null,
)

/** Full pack page: hero + long body + gallery + installable versions. */
data class CataloguePackDetails(
    val origin: PackOrigin,
    val id: String,
    val title: String,
    val tagline: String,
    val iconUrl: String? = null,
    val bannerUrl: String? = null,
    /** Full-resolution gallery image URLs (for the lightbox). */
    val galleryUrls: List<String> = emptyList(),
    /** Light preview URLs for the strip, index-aligned with [galleryUrls]; empty
     *  means the source has no separate thumbnails (the strip falls back to full). */
    val galleryThumbUrls: List<String> = emptyList(),
    /** Long-form CommonMark description; null when the source has none. */
    val bodyMarkdown: String? = null,
    val versions: List<CataloguePackVersion> = emptyList(),
    /** Source-authored tags/categories for the metadata block; empty when none. */
    val tags: List<String> = emptyList(),
    /**
     * Required runtime label (e.g. "Java 21") when the source declares one -- the
     * mirror reads it from the manifest. Null when the source has no runtime info
     * (Modrinth does not expose a per-pack Java requirement). MC + loader come off
     * the selected [CataloguePackVersion].
     */
    val runtimeLabel: String? = null,
)

/** One installable version of a pack. */
data class CataloguePackVersion(
    /** Source-local version id: Modrinth `version_id`, mirror `pack_version`. */
    val id: String,
    val name: String,
    val versionNumber: String,
    val mcVersions: List<String> = emptyList(),
    val loaders: List<String> = emptyList(),
    /**
     * Direct artifact URL when the source serves one (Modrinth `.mrpack`). Null
     * for sources installed by sync (the mirror), where the installer resolves
     * files from the manifest by (packId, version).
     */
    val downloadUrl: String? = null,
)
