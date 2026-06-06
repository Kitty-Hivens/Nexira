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
     * Applies a single user toggle to [current], dependency-aware:
     *
     * - ENABLING pulls the mod on PLUS the transitive closure of its non-optional
     *   `display.requires` -- so a library (e.g. Mixinbooter) can ship optional +
     *   `default_enabled=false` and follow its consumers on, instead of being
     *   flat-`required`. For each newly-on mod, mutual exclusions are enforced:
     *   same-`role` members (one active per interchangeable group) and declared
     *   `incompatibleWith` are turned off.
     * - DISABLING never cascades: a library that was auto-enabled for another mod
     *   stays put (harmlessly loaded-but-unused) rather than risking the surprise
     *   of pulling content the user never touched.
     *
     * Returns the new state; only optional + present entries change.
     */
    fun applyToggle(
        mods: List<SmrtModEntry>,
        current: Map<String, Boolean>,
        filename: String,
        enable: Boolean,
    ): Map<String, Boolean> {
        val next = current.toMutableMap()
        if (!enable) {
            next[filename] = false
            return next
        }
        val byName = mods.associateBy { it.filename }
        val toEnable = requiredClosure(byName, filename)
        for (f in toEnable) next[f] = true
        for (f in toEnable) {
            val role = byName[f]?.display?.role
            for (other in mods) {
                if (other.filename == f) continue
                val sameRole = role != null && other.display?.role == role
                if (sameRole || conflicts(mods, f, other.filename)) {
                    next[other.filename] = false
                }
            }
        }
        return next
    }

    /**
     * [filename] plus the transitive closure of its NON-optional `requires`
     * (optional/soft deps do not follow). Cycle-safe -- a `requires` cycle in a
     * bad manifest terminates instead of looping. References to filenames absent
     * from [byName] are skipped (the resolver surfaces those as warnings).
     */
    private fun requiredClosure(byName: Map<String, SmrtModEntry>, filename: String): Set<String> {
        val out = LinkedHashSet<String>()
        val stack = ArrayDeque<String>()
        stack.addLast(filename)
        while (stack.isNotEmpty()) {
            val f = stack.removeLast()
            if (!out.add(f)) continue
            for (req in byName[f]?.display?.requires.orEmpty()) {
                if (!req.optional && req.filename in byName) stack.addLast(req.filename)
            }
        }
        return out
    }
}
