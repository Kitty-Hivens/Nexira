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
     * its manifest `default_enabled`. Keyed by [SmrtModEntry.stableKey] so the
     * choice survives a pack-version bump. Required mods are omitted (always on).
     */
    fun defaultToggles(mods: List<SmrtModEntry>): List<ContentToggle> =
        optionalMods(mods).map { ContentToggle(it.stableKey, it.defaultEnabled) }

    /**
     * Persistable [ContentToggle] list for an effective `filename -> enabled`
     * view (e.g. the result of [applyToggle]). One entry per OPTIONAL mod, keyed
     * by [SmrtModEntry.stableKey]; required mods are omitted (always on). The
     * inverse of [enabledState] over the optional subset -- what the toggle UI
     * hands to the persistence path after a flip.
     */
    fun togglesFrom(mods: List<SmrtModEntry>, enabled: Map<String, Boolean>): List<ContentToggle> =
        optionalMods(mods).map { ContentToggle(it.stableKey, enabled[it.filename] ?: it.defaultEnabled) }

    /**
     * Effective `filename -> enabled` for the sync path, settled into a state the
     * rules can keep (see [settle]). An optional uses the user's [toggles] entry,
     * else its manifest `default_enabled`.
     *
     * A toggle is matched by [SmrtModEntry.stableKey] first, then by [filename]
     * as a fallback so state persisted before stable keys existed still
     * applies until it is rewritten on the next toggle.
     *
     * Settled here rather than trusted: a selection is saved against one build and
     * applied against the next, and a curator who adds a conflict or a requirement
     * in between would otherwise get both halves of the conflict installed, or a
     * mod without the library it needs, at every launch after.
     */
    fun enabledState(mods: List<SmrtModEntry>, toggles: List<ContentToggle>): Map<String, Boolean> {
        val userState = toggles.associate { it.entryId to it.enabled }
        val raw = mods.associate { mod ->
            mod.filename to if (mod.required) {
                true
            } else {
                userState[mod.stableKey] ?: userState[mod.filename] ?: mod.defaultEnabled
            }
        }
        return settle(mods, raw)
    }

    /**
     * True when [a] and [b] declare each other (in either direction) under
     * `display.incompatibleWith`. Mutual so the curator only has to mark one side.
     */
    fun conflicts(mods: List<SmrtModEntry>, a: String, b: String): Boolean = Index(mods).conflicts(a, b)

    /** Why an optional mod's switch cannot move, or null when it can. */
    sealed interface Lock {
        /** The mod the lock comes from, which the pack requires. */
        val by: SmrtModEntry

        /** It cannot run beside [by], so it stays off. */
        data class ConflictsWithRequired(override val by: SmrtModEntry) : Lock

        /** [by] cannot run without it, so it stays on. */
        data class NeededByRequired(override val by: SmrtModEntry) : Lock
    }

    /**
     * The required mod that decides [filename]'s state, if one does.
     *
     * A required mod is never switched off, so an optional that conflicts with it
     * can never be on and one it needs can never be off. Asked before a switch is
     * offered, so the row can say why it does not move rather than accept a flip
     * the rules would put back.
     */
    fun lockOf(mods: List<SmrtModEntry>, filename: String): Lock? = Index(mods).lockOf(filename)

    /** [lockOf] for every optional at once, keyed by filename, for a list that asks per row. */
    fun locks(mods: List<SmrtModEntry>): Map<String, Lock> {
        val index = Index(mods)
        return mods.mapNotNull { m -> index.lockOf(m.filename)?.let { m.filename to it } }.toMap()
    }

    /**
     * Applies a single user toggle to [current]:
     *
     * - ENABLING pulls the mod on PLUS the transitive closure of its non-optional
     *   `display.requires` -- so a library (e.g. Mixinbooter) can ship optional +
     *   `default_enabled=false` and follow its consumers on, instead of being
     *   flat-`required`. For each newly-on mod, mutual exclusions are enforced:
     *   same-`role` members (one active per interchangeable group) and declared
     *   `incompatibleWith` are turned off.
     * - DISABLING takes down what cannot run without the mod, and nothing it
     *   needed: a library that was auto-enabled for another mod stays put
     *   (harmlessly loaded-but-unused) rather than risking the surprise of
     *   pulling content the user never touched.
     *
     * Either way the result is [settle]d, so a mod switched off by an exclusion
     * takes down whatever needed it as well. A mod under a [lockOf] is refused,
     * and [current] comes back unchanged.
     */
    fun applyToggle(
        mods: List<SmrtModEntry>,
        current: Map<String, Boolean>,
        filename: String,
        enable: Boolean,
    ): Map<String, Boolean> {
        val index = Index(mods)
        if (index.lockOf(filename) != null) return current
        val next = current.toMutableMap()
        if (!enable) {
            index.switchOff(next, filename)
            return index.settle(next)
        }
        val toEnable = index.closure(filename)
        for (f in toEnable) next[f] = true
        for (f in toEnable) {
            val mod = index.byName[f] ?: continue
            for (other in mods) {
                if (other.filename != f && !other.required && index.excludes(mod, other)) index.switchOff(next, other.filename)
            }
        }
        return index.settle(next)
    }

    /**
     * [state] made into one the rules can keep, changing as little as it must.
     *
     * Required mods are on. What an enabled mod hard-requires is on. Of two mods
     * that exclude each other (a shared `role`, or `incompatibleWith`), a required
     * one wins, and between optionals the one earlier in the manifest does. Last,
     * an optional whose hard requirement ended up off goes off with it, until
     * nothing changes. A mod missing from [state] reads as its manifest default.
     *
     * A state [applyToggle] produced is already settled, so for a selection made
     * in this launcher this changes nothing. It is the guard for one that was
     * saved against a build whose rules differed.
     */
    fun settle(mods: List<SmrtModEntry>, state: Map<String, Boolean>): Map<String, Boolean> = Index(mods).settle(state)

    /**
     * One manifest's references resolved once, so the rules above ask each
     * question in a lookup rather than a scan of the whole list.
     *
     * A reference in `requires` or `incompatibleWith` names a mod either by its
     * filename, which carries the mod's version and moves with every build, or by
     * the [SmrtModEntry.stableKey] the toggles are already keyed on, which does
     * not. A filename wins where the two would name different entries. A
     * reference to a mod this manifest does not carry is dropped (the resolver
     * surfaces those as warnings).
     */
    private class Index(val mods: List<SmrtModEntry>) {
        val byName: Map<String, SmrtModEntry> = mods.associateBy { it.filename }
        private val byRef: Map<String, SmrtModEntry> = buildMap {
            mods.forEach { put(it.stableKey, it) }
            mods.forEach { put(it.filename, it) }
        }
        private val hardRequires: Map<String, List<String>> = mods.associate { m ->
            m.filename to m.display?.requires.orEmpty().filter { !it.optional }.mapNotNull { byRef[it.filename]?.filename }
        }
        private val incompatible: Map<String, Set<String>> = mods.associate { m ->
            m.filename to m.display?.incompatibleWith.orEmpty().mapNotNullTo(HashSet()) { byRef[it]?.filename }
        }
        private val closures = HashMap<String, Set<String>>()
        private val required = mods.filter { it.required }

        fun conflicts(a: String, b: String): Boolean =
            a != b && (b in incompatible[a].orEmpty() || a in incompatible[b].orEmpty())

        /** Whether [a] and [b] cannot both be on: one per `role`, or declared incompatible. */
        fun excludes(a: SmrtModEntry, b: SmrtModEntry): Boolean {
            if (a.filename == b.filename) return false
            val role = a.display?.role
            return (role != null && b.display?.role == role) || conflicts(a.filename, b.filename)
        }

        /**
         * [filename] plus the transitive closure of its NON-optional `requires`
         * (optional/soft deps do not follow). Cycle-safe -- a `requires` cycle in
         * a bad manifest terminates instead of looping.
         */
        fun closure(filename: String): Set<String> = closures.getOrPut(filename) {
            val out = LinkedHashSet<String>()
            val stack = ArrayDeque<String>()
            stack.addLast(filename)
            while (stack.isNotEmpty()) {
                val f = stack.removeLast()
                if (out.add(f)) hardRequires[f].orEmpty().forEach { stack.addLast(it) }
            }
            out
        }

        fun lockOf(filename: String): Lock? {
            val mod = byName[filename]?.takeIf { !it.required } ?: return null
            required.firstOrNull { excludes(it, mod) }?.let { return Lock.ConflictsWithRequired(it) }
            required.firstOrNull { filename in closure(it.filename) }?.let { return Lock.NeededByRequired(it) }
            return null
        }

        /**
         * Turns [filename] off together with every optional that needs it, directly
         * or through another. Before [settle] rather than left to it: settling pulls
         * an enabled mod's requirements on, so a library switched off under a
         * consumer that is still on would come straight back.
         */
        fun switchOff(state: MutableMap<String, Boolean>, filename: String) {
            state[filename] = false
            for (mod in mods) {
                if (!mod.required && mod.filename != filename && filename in closure(mod.filename)) state[mod.filename] = false
            }
        }

        fun settle(state: Map<String, Boolean>): Map<String, Boolean> {
            val on = state.toMutableMap()
            for (mod in mods) on[mod.filename] = mod.required || (on[mod.filename] ?: mod.defaultEnabled)

            for (mod in mods) {
                if (on[mod.filename] == true) closure(mod.filename).forEach { on[it] = true }
            }

            val kept = required.toMutableList()
            for (mod in mods) {
                if (mod.required || on[mod.filename] != true) continue
                if (kept.any { excludes(it, mod) }) on[mod.filename] = false else kept += mod
            }

            do {
                var changed = false
                for (mod in mods) {
                    if (mod.required || on[mod.filename] != true) continue
                    if (hardRequires[mod.filename].orEmpty().any { on[it] != true }) {
                        on[mod.filename] = false
                        changed = true
                    }
                }
            } while (changed)
            return on
        }
    }
}
