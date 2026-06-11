package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * One pack, identified within its origin. Pack identity is the pair
 * `(origin, id)`; the same pack can exist twice in a user's library
 * with different origins (e.g. an SC-mirrored copy and a user-forked
 * Local copy share the same display name but are different packs).
 *
 * This is a data class. Repositories that find / list / persist packs
 * live in separate files; this file deliberately stays free of any
 * platform / IO concerns so it can be referenced from `:client-core`
 * tests without dragging the world.
 */
@Serializable
data class Pack(
    val id: String,
    @Serializable(with = PackOriginSerializer::class)
    val origin: PackOrigin,
    val displayName: String,
    val mcVersion: String,
    @Serializable(with = PackLoaderSerializer::class)
    val loader: PackLoader,
    val loaderVersion: String? = null,
    /**
     * Java major version the pack needs (8 / 17 / 21). Derived from
     * [mcVersion] by `JavaManagerService.detectJavaVersion` when not
     * pinned; pack manifests MAY override this if their mod set has
     * a known incompatibility with the default mapping.
     */
    val requiredJava: Int = 8,
    val tagline: String? = null,
    val description: String? = null,
    val bannerUrl: String? = null,
    val tags: List<String> = emptyList(),
    /**
     * Where to fetch the v2 pack manifest from. Set for [PackOrigin.Mirror]
     * and [PackOrigin.Modrinth]; null for [PackOrigin.Smartycraft] (the
     * SC API has its own manifest endpoint that the SC adapter knows
     * about) and [PackOrigin.Local] (no remote manifest).
     */
    val manifestUrl: String? = null,
    /**
     * Last-known available pack version, in the source's own format
     * (mirror's `pack_version`, Modrinth's version slug, etc).
     * Used by the UI to badge "update available" vs the instance's
     * `pinnedPackVersion`. Null when the source doesn't version packs
     * (Local) or when the value hasn't been fetched yet.
     */
    val latestVersion: String? = null,
)
