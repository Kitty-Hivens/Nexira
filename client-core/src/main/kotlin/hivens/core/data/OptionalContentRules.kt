package hivens.core.data

import hivens.core.api.dto.smrt.SmrtModEntry

/**
 * Pure rules for a pack instance's optional mods, shared by the install/sync
 * path and the toggle UI. A mod is OPTIONAL when `required = false`; required
 * mods are always installed and never appear in the toggle list.
 *
 * State is a `filename -> enabled` view derived from [PackInstance.optionalContent]
 * (a list of [ContentToggle]); the manifest's `default_enabled` is the fallback
 * for an optional the user has not touched. Incompatibility is the mutual closure
 * of each mod's `display.incompatibleWith`.
 */
object OptionalContentRules {

    /** The optional entries of [mods], in manifest order. */
    fun optionalMods(mods: List<SmrtModEntry>): List<SmrtModEntry> = mods.filter { !it.required }

    /**
     * Seed toggles for a fresh install: one [ContentToggle] per optional mod at
     * its manifest `default_enabled`. Required mods are omitted (always on).
     */
    fun defaultToggles(mods: List<SmrtModEntry>): List<ContentToggle> =
        optionalMods(mods).map { ContentToggle(it.filename, it.defaultEnabled) }

    /**
     * Effective `filename -> enabled` for the sync path. Required mods are always
     * enabled; an optional uses the user's [toggles] entry, else its manifest
     * `default_enabled`.
     */
    fun enabledState(mods: List<SmrtModEntry>, toggles: List<ContentToggle>): Map<String, Boolean> {
        val userState = toggles.associate { it.entryId to it.enabled }
        return mods.associate { mod ->
            mod.filename to if (mod.required) true else (userState[mod.filename] ?: mod.defaultEnabled)
        }
    }

    /**
     * True when [a] and [b] declare each other (in either direction) under
     * `display.incompatibleWith`. Mutual so the curator only has to mark one side.
     */
    fun conflicts(mods: List<SmrtModEntry>, a: String, b: String): Boolean {
        if (a == b) return false
        val byName = mods.associateBy { it.filename }
        val aIncompat = byName[a]?.display?.incompatibleWith.orEmpty()
        val bIncompat = byName[b]?.display?.incompatibleWith.orEmpty()
        return b in aIncompat || a in bIncompat
    }

    /**
     * Applies a single user toggle to [current], enforcing incompatibilities:
     * enabling a mod disables every mod that conflicts with it. Disabling never
     * cascades. Returns the new state (only optional + present entries change).
     */
    fun applyToggle(
        mods: List<SmrtModEntry>,
        current: Map<String, Boolean>,
        filename: String,
        enable: Boolean,
    ): Map<String, Boolean> {
        val next = current.toMutableMap()
        next[filename] = enable
        if (enable) {
            for (other in mods) {
                if (other.filename != filename && conflicts(mods, filename, other.filename)) {
                    next[other.filename] = false
                }
            }
        }
        return next
    }
}
