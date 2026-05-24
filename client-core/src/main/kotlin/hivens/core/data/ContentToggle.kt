package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * One optional-content entry's on/off state for a given
 * [PackInstance]. Covers mods, shader packs, resource packs, configs
 * -- whatever the pack manifest exposes as togglable.
 *
 * Stored as a list (not a map keyed by [entryId]) so the wire format
 * preserves order. The UI groups by `display.category` from the
 * manifest's [DisplayBlock], but within a category the user-set
 * priority should match what they chose; map-based JSON doesn't
 * guarantee that. The list is also smaller diffs in version control
 * if the file ever lands in a profile-sharing flow.
 */
@Serializable
data class ContentToggle(
    /**
     * Origin-defined identifier for the entry: the mod's `filename`,
     * the asset's `dest`, or whatever the manifest uses as its
     * primary key. Stable across pack versions so toggle state
     * survives a pack-version bump.
     */
    val entryId: String,
    val enabled: Boolean,
)
