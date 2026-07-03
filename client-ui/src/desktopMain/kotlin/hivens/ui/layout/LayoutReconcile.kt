package hivens.ui.layout

import hivens.widget.model.LayoutGraph
import hivens.widget.model.walkInstances
import org.slf4j.LoggerFactory

/**
 * Brings a persisted or preset [LayoutGraph] up to the current schema and
 * the bundled-default structure, then verifies tree-wide instanceId
 * uniqueness. Shared by [LayoutGraphRepository.load] and the editor's
 * preset load so both paths migrate + merge identically -- a preset saved
 * on an older schema (carrying retired kinds) or an older app version
 * (missing surfaces / slots) reconciles the same way an on-disk graph does,
 * instead of being written straight into live state.
 */
object LayoutReconcile {
    private val log = LoggerFactory.getLogger(LayoutReconcile::class.java)

    /** Schema version this build writes and migrates up to. Single source of truth. */
    const val CURRENT_SCHEMA: Int = 7

    sealed interface Result {
        data class Ok(val graph: LayoutGraph) : Result
        /** A migration or merge produced a duplicate instanceId; the caller falls back. */
        data class DuplicateId(val id: String, val stage: String) : Result
    }

    /**
     * Migrates [graph] from [schemaVersion] to [CURRENT_SCHEMA], seeds
     * bundled-default surfaces and slots the graph is missing, and sweeps
     * instanceId uniqueness. Returns [Result.DuplicateId] when a migration
     * or merge mints a collision (a tree the caller must reject), otherwise
     * [Result.Ok]. Throws when [schemaVersion] is structurally invalid (< 1)
     * -- callers wrap.
     */
    fun reconcile(schemaVersion: Int, graph: LayoutGraph, default: LayoutGraph): Result {
        val migrated = Migrations.apply(schemaVersion, graph)
        firstDuplicateInstanceId(migrated)?.let { return Result.DuplicateId(it, "migration") }
        val merged = mergeMissingSlots(mergeMissingSurfaces(migrated, default), default)
        firstDuplicateInstanceId(merged)?.let { return Result.DuplicateId(it, "merge") }
        return Result.Ok(merged)
    }

    /**
     * First instanceId that occurs more than once across the whole graph
     * (nested children included), or null when every id is unique. Backs the
     * live-update guard, the post-load sweep, and the preset-load sweep.
     */
    fun firstDuplicateInstanceId(graph: LayoutGraph): String? {
        val seen = HashSet<String>()
        for (widget in graph.walkInstances()) {
            if (!seen.add(widget.instanceId)) return widget.instanceId
        }
        return null
    }

    // Adds whole surfaces from the bundled default that the user file
    // pre-dates. Only ADDS: a surface the user has edited keeps its
    // persisted form; a surface dropped from the default in a later release
    // stays in the user file (no automatic deletion -- it may carry data we
    // cannot recreate).
    private fun mergeMissingSurfaces(user: LayoutGraph, def: LayoutGraph): LayoutGraph {
        val missing = def.surfaces.filterKeys { it !in user.surfaces }
        if (missing.isEmpty()) return user
        log.info("Layout graph: seeding {} new surface(s) from bundled default: {}", missing.size, missing.keys.map { it.value })
        return user.copy(surfaces = user.surfaces + missing)
    }

    // Adds slots the bundled default declares on a surface the user already
    // has. [mergeMissingSurfaces] only adds whole NEW surfaces; a slot ADDED
    // to an existing surface in a later release would otherwise stay
    // invisible (SlotRenderer finds nothing at the new id -- a blank pane
    // with no in-product way back). Slots are structural (the editor has no
    // create/delete-slot op), so a slot present in the default but absent
    // from the user graph is always an upstream addition, never a user
    // deletion -- which makes this purely additive and safe. Slot REMOVALS
    // are left in place; a stale slot is inert and a true reclaim needs an
    // explicit migration step.
    private fun mergeMissingSlots(user: LayoutGraph, def: LayoutGraph): LayoutGraph {
        var changed = false
        val merged = user.surfaces.mapValues { (surfaceId, layout) ->
            val defLayout = def.surfaces[surfaceId] ?: return@mapValues layout
            val missing   = defLayout.slots.filterKeys { it !in layout.slots }
            if (missing.isEmpty()) return@mapValues layout
            changed = true
            log.info(
                "Layout graph: seeding {} new slot(s) into surface '{}' from bundled default: {}",
                missing.size, surfaceId.value, missing.keys.map { it.value },
            )
            layout.copy(slots = layout.slots + missing)
        }
        return if (changed) user.copy(surfaces = merged) else user
    }
}
