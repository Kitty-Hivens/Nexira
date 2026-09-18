package hivens.ui.layout

import hivens.widget.model.LAYOUT_SCHEMA
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
    const val CURRENT_SCHEMA: Int = LAYOUT_SCHEMA

    /**
     * The first schema whose widget surfaces are describable.
     *
     * A layout below this is discarded rather than migrated. Every earlier file
     * describes a widget's plane as a glass percentage, a single corner and a tier
     * name, and none of those has a faithful reading in seven values: the tier stood
     * for numbers that were discarded before they drew, the corner had one value where
     * a shape has four, and the glass percentage was overridden by a constant, so what
     * a person saw was never what their file said. Inventing a translation would be
     * inventing intent.
     *
     * This is the one reset. The replacement format is built so a second is never
     * needed: every field defaulted, unknown keys ignored, nothing on the wire an enum.
     */
    const val SURFACE_SCHEMA: Int = 8

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
        val merged = mergeMissingFamilies(mergeMissingSurfaces(migrated, default), default)
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

    // Adds families, and slots within a family, that the bundled default declares
    // on a surface the user already has. [mergeMissingSurfaces] only adds whole
    // NEW surfaces; a family or a slot ADDED to an existing surface in a later
    // release would otherwise stay invisible (SlotRenderer finds nothing at the
    // new id -- a blank pane with no in-product way back). Both are structural
    // (the editor has no create/delete op for either), so one present in the
    // default and absent from the user graph is always an upstream addition and
    // never a user deletion, which makes this purely additive and safe.
    // REMOVALS are left in place; a stale family or slot is inert and a true
    // reclaim needs an explicit migration step.
    private fun mergeMissingFamilies(user: LayoutGraph, def: LayoutGraph): LayoutGraph {
        var changed = false
        val merged = user.surfaces.mapValues { (surfaceId, layout) ->
            val defLayout = def.surfaces[surfaceId] ?: return@mapValues layout

            val newFamilies = defLayout.families.filterKeys { it !in layout.families }
            if (newFamilies.isNotEmpty()) {
                changed = true
                log.info(
                    "Layout graph: seeding {} new family/families into surface '{}' from bundled default: {}",
                    newFamilies.size, surfaceId.value, newFamilies.keys.map { it.value },
                )
            }

            // No early return here, deliberately: the label would be the inner
            // mapValues and read as if it left the surface loop.
            val grown = layout.families.mapValues { (familyId, family) ->
                val missing = defLayout.family(familyId)?.slots
                    ?.filterKeys { it !in family.slots }
                    .orEmpty()
                if (missing.isEmpty()) {
                    family
                } else {
                    changed = true
                    log.info(
                        "Layout graph: seeding {} new slot(s) into '{}' family '{}' from bundled default: {}",
                        missing.size, surfaceId.value, familyId.value, missing.keys.map { it.value },
                    )
                    family.copy(slots = family.slots + missing)
                }
            }

            layout.copy(families = grown + newFamilies)
        }
        return if (changed) user.copy(surfaces = merged) else user
    }
}
