package hivens.launcher.launch

import hivens.core.data.PackAuthRequirement
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin

/**
 * Resolves the auth requirement for a pack launch. An explicit manifest
 * requirement always wins; otherwise it is derived from the pack origin:
 *
 * - Smartycraft: SC, via the [scFallback] name match (until the mirror manifest
 *   carries an explicit auth block). No serverId can be invented, so an unknown
 *   SC pack resolves to null (no precondition) -- same as before.
 * - Modrinth / Local (incl. CurseForge imports) / Unknown: Microsoft (licensed
 *   play). Advisory until the Microsoft provider is registered.
 * - Mirror: ambiguous (it hosts both SC-port packs and license-friendly packs),
 *   so the SC name fallback decides first, else Microsoft.
 *
 * Microsoft requirements are non-blocking in this phase (the launch gate only
 * enforces a satisfiable provider), so deriving Microsoft instead of null does
 * not change current behavior -- it records intent for when the provider lands.
 */
object PackAuthRouter {

    fun requirementFor(instance: PackInstance, explicit: PackAuthRequirement?): PackAuthRequirement? {
        if (explicit != null) return explicit
        return when (instance.packRef.origin) {
            PackOrigin.Smartycraft -> scFallback(instance)
            PackOrigin.Mirror -> scFallback(instance) ?: PackAuthRequirement.Microsoft
            PackOrigin.Modrinth,
            PackOrigin.Local,
            PackOrigin.Unknown -> PackAuthRequirement.Microsoft
        }
    }

    /**
     * SC requirement for an SC-bound pack whose mirror manifest carries no
     * explicit `auth` block yet. Recognises the shipping SC pack identities by
     * name; drains as the mirror authors fill in
     * `auth: { kind: smartycraft, server_id: ... }`. A pack missing from this
     * table launches with a Microsoft/vanilla session and the SC server kicks
     * it with an invalid-session error, so a newly mirrored SC pack must land
     * here (or, properly, in the mirror's auth block) before it is playable.
     */
    private fun scFallback(instance: PackInstance): PackAuthRequirement? {
        val serverId = SC_BOUND_PACKS.entries.firstOrNull { (packName, _) ->
            instance.displayName.equals(packName, ignoreCase = true) ||
                instance.packRef.id.equals(packName, ignoreCase = true)
        }?.value
        return serverId?.let { PackAuthRequirement.SmartyCraft(it) }
    }

    // Pack identity (display name or pack id) -> SC server id the join binds to.
    private val SC_BOUND_PACKS = mapOf(
        "Industrial" to "Industrial",
        "Create" to "Create",
    )
}
