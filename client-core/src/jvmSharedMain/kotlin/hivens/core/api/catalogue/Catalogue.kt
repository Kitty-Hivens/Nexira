package hivens.core.api.catalogue

import hivens.core.data.PackOrigin
import hivens.core.update.VersionChannel

/**
 * A catalogue pack and one of its builds as the engine's installer takes them. What a
 * source lists and shows is in the catalogue API's own values, which the packs module
 * turns into these to install from.
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
