package hivens.launcher.launch

import hivens.core.data.PackAuthRequirement
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin

/**
 * Resolves the auth requirement for a pack launch. An explicit manifest
 * requirement always wins; otherwise it derives from the pack origin:
 *
 * - Smartycraft: the packRef id IS the SC server id by construction, so the
 *   binding derives directly from it.
 * - Mirror: Microsoft. An SC-bound mirror pack declares itself through the
 *   manifest's `auth` block (`kind: smartycraft`); the name table that used to
 *   bridge the gap is gone now that the mirror authors the block, so a mirror
 *   manifest WITHOUT one launches as a plain licensed pack and an SC server
 *   will kick it -- the block is the single source of the binding.
 * - Modrinth / Local (incl. CurseForge imports) / Unknown: Microsoft (licensed
 *   play). Advisory until the Microsoft provider is registered.
 *
 * Microsoft requirements are non-blocking in this phase (the launch gate only
 * enforces a satisfiable provider), so deriving Microsoft instead of null does
 * not change current behavior -- it records intent for when the provider lands.
 */
object PackAuthRouter {
    fun requirementFor(instance: PackInstance, explicit: PackAuthRequirement?): PackAuthRequirement? {
        if (explicit != null) return explicit
        return when (instance.packRef.origin) {
            PackOrigin.Smartycraft -> PackAuthRequirement.SmartyCraft(instance.packRef.id)
            PackOrigin.Mirror,
            PackOrigin.Modrinth,
            PackOrigin.Local,
            PackOrigin.Unknown -> PackAuthRequirement.Microsoft
        }
    }
}
