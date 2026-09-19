package hivens.ui.screens.mod

import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthVersion

/**
 * What the versions table is narrowed by.
 *
 * Three axes, each a SET rather than one value: a reader asking "does this run on
 * Fabric or Quilt" is asking one question, and making them pick one loader at a
 * time answers a different one. Empty means the axis is not narrowing.
 *
 * Game versions are held as raw version strings and not as the chip labels the
 * table shows, because a chip is a handle for a set: `1.21.x` stands for every
 * minor of that major the project ships for, and a filter recorded as the label
 * would have to re-derive that set every time it was asked.
 */
data class VersionFilters(
    val channels: Set<String> = emptySet(),
    val gameVersions: Set<String> = emptySet(),
    val loaders: Set<String> = emptySet(),
) {
    val activeCount: Int =
        (if (channels.isEmpty()) 0 else 1) +
            (if (gameVersions.isEmpty()) 0 else 1) +
            (if (loaders.isEmpty()) 0 else 1)

    val isEmpty: Boolean get() = activeCount == 0

    /** Within an axis the members are alternatives; across axes they all have to hold. */
    fun matches(v: ModrinthVersion): Boolean =
        (channels.isEmpty() || v.versionType in channels) &&
            (gameVersions.isEmpty() || v.gameVersions.any { it in gameVersions }) &&
            (loaders.isEmpty() || v.loaders.any { it in loaders })

    fun toggleChannel(channel: String): VersionFilters =
        copy(channels = channels.toggle(channel))

    fun toggleLoader(loader: String): VersionFilters =
        copy(loaders = loaders.toggle(loader))

    /**
     * Turns a whole group on or off at once.
     *
     * A chip reading `1.21.x` is one thing to a reader, so clicking it takes every
     * version behind it rather than leaving them half-selected. Already fully on
     * means off, which is what makes the same click undo itself.
     */
    fun toggleGameVersions(versions: List<String>): VersionFilters =
        if (versions.isNotEmpty() && gameVersions.containsAll(versions)) {
            copy(gameVersions = gameVersions - versions.toSet())
        } else {
            copy(gameVersions = gameVersions + versions)
        }

    fun clearChannels(): VersionFilters = copy(channels = emptySet())
    fun clearGameVersions(): VersionFilters = copy(gameVersions = emptySet())
    fun clearLoaders(): VersionFilters = copy(loaders = emptySet())
}

private fun Set<String>.toggle(value: String): Set<String> =
    if (value in this) this - value else this + value

/**
 * The axes a particular project actually offers.
 *
 * Derived from its own builds rather than from the catalogue's whole vocabulary: a
 * mod published only for Fabric has nothing to say about NeoForge, and a filter
 * listing every loader there is would be a list of things that all return nothing.
 */
data class VersionFacets(
    val channels: List<String>,
    val gameVersionGroups: List<GameVersionGroup>,
    val loaders: List<String>,
)

fun facetsOf(versions: List<ModrinthVersion>, tags: List<ModrinthGameVersion>): VersionFacets {
    val channels = versions.map { it.versionType }.distinct()
    val loaders = versions.flatMap { it.loaders }.distinct().sorted()
    val supported = versions.flatMap { it.gameVersions }.distinct()
    return VersionFacets(
        channels = channels,
        gameVersionGroups = groupGameVersions(supported, tags),
        loaders = loaders,
    )
}

/**
 * Whether a build can run on the pack the page was opened from.
 *
 * Both axes have to be KNOWN to say no. A page opened without a pack behind it,
 * or one whose manifest has not been read, is not evidence of anything, and a
 * table that marked every row orange because it did not know the game version
 * would be worse than one that marked none.
 */
internal fun runsOn(v: ModrinthVersion, mcVersion: String, loaders: List<String>): Boolean =
    (mcVersion.isBlank() || v.gameVersions.contains(mcVersion)) &&
        (loaders.isEmpty() || v.loaders.any { it in loaders })
