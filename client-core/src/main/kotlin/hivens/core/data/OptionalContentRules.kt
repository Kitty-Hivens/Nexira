package hivens.core.data

import hivens.core.api.dto.smrt.SmrtAssetEntry
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pure rules for a pack instance's optional mods, shared by the install/sync
 * path and the toggle UI. A mod is OPTIONAL when `required = false`; required
 * mods are always installed and never appear in the toggle list.
 *
 * State is a `filename -> enabled` view derived from [PackInstance.optionalContent]
 * (a list of [ContentToggle]); the manifest's `default_enabled` is the fallback
 * for an optional the user has not touched. Incompatibility is the mutual closure
 * of each mod's `display.incompatibleWith`.
 *
 * A resource or shader pack the pack ships can be optional too, see
 * [SmrtAssetEntry.toggleable]. Its state is a `dest -> enabled` view kept in the
 * same toggle list under [SmrtAssetEntry.stableKey], and the manifest names no
 * default for one, so an asset nobody switched off is on. The two halves are kept
 * apart because a mod's rules (requires, conflicts) mean nothing for an asset.
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
     * Effective `filename -> enabled` for the sync path. Required mods are always
     * enabled; an optional uses the user's [toggles] entry, else its manifest
     * `default_enabled`.
     *
     * A toggle is matched by [SmrtModEntry.stableKey] first, then by [filename]
     * as a fallback so state persisted before stable keys existed still
     * applies until it is rewritten on the next toggle.
     *
     * Applied as the player left it, conflicts included: the pack is theirs to
     * break, and [problems] is how a screen says what is wrong with it.
     */
    fun enabledState(mods: List<SmrtModEntry>, toggles: List<ContentToggle>): Map<String, Boolean> {
        val userState = toggles.associate { it.entryId to it.enabled }
        return mods.associate { mod ->
            mod.filename to if (mod.required) {
                true
            } else {
                userState[mod.stableKey] ?: userState[mod.filename] ?: mod.defaultEnabled
            }
        }
    }

    /** The assets of [assets] a player may switch off, in manifest order. */
    fun optionalAssets(assets: List<SmrtAssetEntry>): List<SmrtAssetEntry> = assets.filter { it.toggleable }

    /**
     * Effective `dest -> enabled` for every asset of [assets]: the player's [toggles]
     * entry for one they may switch off, and on for the rest.
     *
     * An optional asset with no entry is read off the disk through [placed] before it
     * falls back to on. A record can predate the asset being optional at all: a
     * resource pack the player switched off by hand while the pack still shipped it
     * as plain content sits under its `.disabled` name with no choice written down,
     * and reading that silence as on put the file back in the game at the next relabel.
     */
    fun assetState(
        assets: List<SmrtAssetEntry>,
        toggles: List<ContentToggle>,
        placed: (SmrtAssetEntry) -> Boolean? = { null },
    ): Map<String, Boolean> {
        val userState = toggles.associate { it.entryId to it.enabled }
        return assets.associate { a -> a.dest to (!a.toggleable || (userState[a.stableKey] ?: placed(a) ?: true)) }
    }

    /**
     * How each asset sits in [clientDir] right now: on when its own name is there,
     * off when only its `.disabled` name is, null when neither is. The disk half of
     * [assetState], for a caller that holds the instance's folder.
     */
    fun placedIn(clientDir: Path): (SmrtAssetEntry) -> Boolean? = { a ->
        when {
            Files.exists(clientDir.resolve(a.dest)) -> true
            Files.exists(clientDir.resolve(a.dest + DISABLED_SUFFIX)) -> false
            else -> null
        }
    }

    private const val DISABLED_SUFFIX = ".disabled"

    /**
     * Seed toggles for a fresh install of [manifest]: every optional mod at its
     * `default_enabled` and every optional asset on.
     */
    fun defaultToggles(manifest: SmrtPackManifest): List<ContentToggle> =
        defaultToggles(manifest.mods) + optionalAssets(manifest.assets).map { ContentToggle(it.stableKey, true) }

    /**
     * The whole persistable choice for [manifest]: [mods] by filename and [assets]
     * by dest, one keyed entry per optional mod and per optional asset.
     *
     * What every writer of the choice goes through. A writer that rebuilt the list
     * from the mods alone dropped what the player had chosen about the assets.
     */
    fun togglesFrom(
        manifest: SmrtPackManifest,
        mods: Map<String, Boolean>,
        assets: Map<String, Boolean>,
    ): List<ContentToggle> =
        togglesFrom(manifest.mods, mods) +
            optionalAssets(manifest.assets).map { ContentToggle(it.stableKey, assets[it.dest] ?: true) }

    /**
     * [manifest]'s choice as [toggles] hold it, carried onto the same manifest's
     * keys: what a rewrite of the record keeps when it changes nothing about it.
     * [placed] answers for an asset the record says nothing about, see [assetState].
     */
    fun carried(
        manifest: SmrtPackManifest,
        toggles: List<ContentToggle>,
        placed: (SmrtAssetEntry) -> Boolean? = { null },
    ): List<ContentToggle> =
        togglesFrom(manifest, enabledState(manifest.mods, toggles), assetState(manifest.assets, toggles, placed))

    /**
     * True when [a] and [b] declare each other (in either direction) under
     * `display.incompatibleWith`. Mutual so the curator only has to mark one side.
     */
    fun conflicts(mods: List<SmrtModEntry>, a: String, b: String): Boolean = Index(mods).conflicts(a, b)

    /** Something wrong with an enabled mod in a given selection. */
    sealed interface Problem {
        /** The other mod the problem is about. */
        val other: SmrtModEntry

        /** Both are on and cannot run together: one per `role`, or declared incompatible. */
        data class ConflictsWith(override val other: SmrtModEntry) : Problem

        /** A mod it hard-requires is off. */
        data class NeedsDisabled(override val other: SmrtModEntry) : Problem

        /**
         * It is off and [other], which is on, hard-requires it. The other side of
         * [NeedsDisabled], so a screen that lists only optionals still shows a
         * required mod's missing library on the library's own row.
         */
        data class NeededBy(override val other: SmrtModEntry) : Problem
    }

    /**
     * What is wrong with each enabled mod under [state], by filename. A mod with
     * nothing wrong is absent.
     *
     * Reported, never acted on. A player may enable a mod beside one it conflicts
     * with, or turn off a library something still needs: the launcher does what
     * they asked and the row says what will go wrong, which is the difference
     * between a pack they broke on purpose and one they cannot tell is broken.
     * Both sides of a conflict carry it, since either may be the one to turn off,
     * and a requirement that is off is reported on both the mod that needs it and
     * the mod that is off.
     */
    fun problems(mods: List<SmrtModEntry>, state: Map<String, Boolean>): Map<String, List<Problem>> {
        val index = Index(mods)
        fun on(mod: SmrtModEntry) = mod.required || (state[mod.filename] ?: mod.defaultEnabled)
        val out = LinkedHashMap<String, MutableList<Problem>>()
        for (mod in mods) {
            if (!on(mod)) continue
            for (other in mods) {
                if (other.filename != mod.filename && on(other) && index.excludes(mod, other)) {
                    out.getOrPut(mod.filename) { mutableListOf() } += Problem.ConflictsWith(other)
                }
            }
            for (req in index.hardRequires(mod.filename)) {
                val needed = index.byName[req] ?: continue
                if (!on(needed)) {
                    out.getOrPut(mod.filename) { mutableListOf() } += Problem.NeedsDisabled(needed)
                    out.getOrPut(needed.filename) { mutableListOf() } += Problem.NeededBy(mod)
                }
            }
        }
        return out
    }

    /**
     * Applies a single user toggle to [current], dependency-aware:
     *
     * - ENABLING pulls the mod on PLUS the transitive closure of its non-optional
     *   `display.requires` -- so a library (e.g. Mixinbooter) can ship optional +
     *   `default_enabled=false` and follow its consumers on, instead of being
     *   flat-`required`. For each newly-on mod, mutual exclusions are enforced
     *   among the optionals: same-`role` members (one active per interchangeable
     *   group) and declared `incompatibleWith` are turned off. A required mod is
     *   never turned off, and a conflict with one is left for [problems] to show.
     * - DISABLING never cascades: a library that was auto-enabled for another mod
     *   stays put (harmlessly loaded-but-unused) rather than risking the surprise
     *   of pulling content the user never touched, and a mod still needing the one
     *   turned off is shown by [problems] rather than turned off with it.
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
        val index = Index(mods)
        val toEnable = index.closure(filename)
        for (f in toEnable) next[f] = true
        for (f in toEnable) {
            val mod = index.byName[f] ?: continue
            for (other in mods) {
                // Never one of the mods being turned on: a manifest whose requires and
                // exclusions contradict each other would otherwise switch off the very
                // mod the player just enabled, and [problems] reports the contradiction.
                if (other.filename in toEnable || other.required) continue
                if (index.excludes(mod, other)) next[other.filename] = false
            }
        }
        return next
    }

    /**
     * One manifest's references resolved once, so the rules above ask each
     * question in a lookup rather than a scan of the whole list.
     *
     * A reference in `requires` or `incompatibleWith` names a mod either by its
     * filename, which carries the mod's version and moves with every build, or by
     * the [SmrtModEntry.stableKey] the toggles are already keyed on, which does
     * not. A filename names one entry and wins over a key. A key can name several:
     * two assets of one GitHub repository share it unless the curator gave them a
     * slug, and a reference by that key is about each of them. A reference to a
     * mod this manifest does not carry is dropped.
     */
    private class Index(mods: List<SmrtModEntry>) {
        val byName: Map<String, SmrtModEntry> = mods.associateBy { it.filename }
        private val byKey: Map<String, List<String>> = mods.groupBy({ it.stableKey }, { it.filename })

        /** The filenames [ref] names: the one file it is, or every entry carrying it as a key. */
        private fun resolve(ref: String): List<String> =
            if (ref in byName) listOf(ref) else byKey[ref].orEmpty()

        private val requires: Map<String, List<String>> = mods.associate { m ->
            m.filename to m.display?.requires.orEmpty().filter { !it.optional }.flatMap { resolve(it.filename) }.distinct()
        }
        private val incompatible: Map<String, Set<String>> = mods.associate { m ->
            m.filename to m.display?.incompatibleWith.orEmpty().flatMapTo(HashSet()) { resolve(it) }
        }

        fun hardRequires(filename: String): List<String> = requires[filename].orEmpty()

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
        fun closure(filename: String): Set<String> {
            val out = LinkedHashSet<String>()
            val stack = ArrayDeque<String>()
            stack.addLast(filename)
            while (stack.isNotEmpty()) {
                val f = stack.removeLast()
                if (out.add(f)) hardRequires(f).forEach { stack.addLast(it) }
            }
            return out
        }
    }
}
