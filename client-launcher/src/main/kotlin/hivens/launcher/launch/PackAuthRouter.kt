package hivens.launcher.launch

import hivens.core.data.PackAuthRequirement

/**
 * Resolves the auth requirement for a pack launch. An explicit manifest
 * requirement always wins, and nothing else is treated as a server binding.
 *
 * Without one the answer is Microsoft for every origin, which records the intent
 * (licensed play) and nothing more. A pack's origin never implies a server: the
 * binding is the manifest's `auth` block, and a launch that the manifest did not
 * bind gets no session token unless a licensed provider is registered and signed
 * in. See `LauncherController.preparePackLaunch` for where that is decided.
 *
 * [PackOrigin.Smartycraft][hivens.core.data.PackOrigin.Smartycraft] used to derive
 * an SC binding from its pack id. Nothing creates that origin any more, and the
 * derived binding handed a live session to a launch the manifest had never bound,
 * so none of the guards a bound launch runs under were armed for it.
 */
object PackAuthRouter {
    fun requirementFor(explicit: PackAuthRequirement?): PackAuthRequirement =
        explicit ?: PackAuthRequirement.Microsoft
}
