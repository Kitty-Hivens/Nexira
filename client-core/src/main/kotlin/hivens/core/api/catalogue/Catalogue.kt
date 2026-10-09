package hivens.core.api.catalogue

import hivens.core.data.PackAuthRequirement
import hivens.core.data.PackOrigin
import hivens.core.update.VersionChannel

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
    /**
     * Built by a member of the source's community rather than by the source itself,
     * listed beside its own packs. The mirror's community packs are the case.
     */
    val community: Boolean = false,
    /** Who built it, for a byline on the card. Null where the source itself did. */
    val author: String? = null,
)

/**
 * One screenshot of a pack.
 *
 * [thumb] and [full] are separate because they cost differently: opening a pack
 * pulls a grid of previews, and pulling a dozen multi-megabyte originals to show
 * them at a third of their size is the difference between a page that appears
 * and one that arrives. A source with only one size puts it in both.
 *
 * [title] and [description] are the author's caption. Without them a gallery is a
 * wall of screenshots with nothing saying what any of them is of, which is what
 * the source went to the trouble of writing.
 */
data class CatalogueGalleryItem(
    val full: String,
    val thumb: String,
    val title: String? = null,
    val description: String? = null,
)

/** One of the author's own addresses, named by what it is rather than by its host. */
enum class CatalogueLinkKind { Issues, Source, Wiki, Discord, Donate }

/** [label] is the destination's own name where it has one: Patreon and Ko-fi rather than "donate" twice. */
data class CatalogueLink(val kind: CatalogueLinkKind, val url: String, val label: String? = null)

/** One person credited on a pack, and what the source calls their part in it. */
data class CatalogueCreator(
    val name: String,
    val role: String,
    val avatarUrl: String? = null,
    val owner: Boolean = false,
)

/**
 * Full pack page: who it is, what it says about itself, what it runs on, and every
 * build that can be installed.
 *
 * Past the first few fields everything is optional, and optional on purpose. The
 * two sources answer different questions: the catalogue counts downloads and
 * credits a team, the mirror names the runtime and the sign-in a pack needs. A
 * field a source leaves empty is one the page leaves out, rather than one it fills
 * with a placeholder that reads as an answer.
 */
data class CataloguePackDetails(
    val origin: PackOrigin,
    val id: String,
    val title: String,
    val tagline: String,
    val iconUrl: String? = null,
    val bannerUrl: String? = null,
    /** Screenshots, in the order the source lists them. */
    val gallery: List<CatalogueGalleryItem> = emptyList(),
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
    /** The source's own short name for the pack, for its address. */
    val slug: String? = null,
    /** The pack's page at the source, for "open in browser" and "copy link". Null where there is none to give. */
    val pageUrl: String? = null,
    /**
     * The catalogue project type the pack is filed under where tags can be searched
     * by it (`modpack`). Null for a source whose tags are its own vocabulary.
     */
    val projectType: String? = null,
    val downloads: Long? = null,
    val followers: Long? = null,
    /** Every game version a build of the pack targets. */
    val gameVersions: List<String> = emptyList(),
    /** Every loader a build of the pack runs on. */
    val loaders: List<String> = emptyList(),
    /** `required` / `optional` / `unsupported` / `unknown`, read as a pair; null where the source does not say. */
    val clientSide: String? = null,
    val serverSide: String? = null,
    val licenseId: String? = null,
    val licenseName: String? = null,
    /** RFC 3339 instants. */
    val publishedAt: String? = null,
    val updatedAt: String? = null,
    val links: List<CatalogueLink> = emptyList(),
    val creators: List<CatalogueCreator> = emptyList(),
    /**
     * The build an install with no choice made reaches for. The mirror names it,
     * and it may well be a beta: a mirror build is a beta unless its curator said
     * release. Null leaves it to the reader of [versions].
     */
    val latestVersionId: String? = null,
    /** The sign-in the pack asks for before the game starts; null when it asks for none. */
    val auth: PackAuthRequirement? = null,
)

/**
 * One installable version of a pack.
 *
 * Carries what a person needs to CHOOSE between versions, not just to fetch one:
 * both sources date and grade their builds, and dropping that on the floor left
 * the install picker unable to say which build is current, which is a beta, or
 * what changed.
 */
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
    /**
     * The sha1 the source publishes for [downloadUrl], and its size. The archive
     * carries the index that pins every other file, so it is the one download
     * nothing else can vouch for.
     */
    val downloadSha1: String? = null,
    val downloadSize: Long = -1L,
    /** Release channel; both sources spell it `version_type` on the wire. */
    val channel: VersionChannel = VersionChannel.Release,
    /** ISO-8601 publish instant, or null when the source does not date a version. */
    val publishedAt: String? = null,
    /** The curator's notes for this build; null when the source carries none. */
    val changelog: String? = null,
    /** How many times this build was taken, where the source counts. */
    val downloads: Long? = null,
    /** How many mods the build lists, and what it costs to fetch, where the source says. */
    val modsCount: Int? = null,
    val sizeBytes: Long? = null,
)
