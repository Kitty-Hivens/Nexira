package hivens.core.update

import hivens.core.data.CachedManifestSnapshot

/**
 * How a candidate pack version differs from what is installed, in compatibility terms.
 * Drives the version-window's coloured dot (green = safe re-sync, amber = needs care +
 * an auto-snapshot before applying). Pure classification; no IO.
 */
enum class CompatChange {
    /** MC + loader name + loader version all identical -- a no-op re-sync. */
    Same,
    /** MC + loader name same, loader VERSION bumped -- still a safe re-sync. */
    LoaderBump,
    /** MC version changed (same loader family) -- unsafe; snapshot first. */
    McBump,
    /** Loader family swapped (e.g. Fabric -> Forge) -- unsafe; snapshot first. */
    LoaderSwap,
    /** No installed baseline to compare against -- treat as needing care. */
    Unknown;

    /** Green dot: the change re-syncs without a structural break. */
    val isSafe: Boolean get() = this == Same || this == LoaderBump
}

/**
 * Classify a candidate version (its MC + loader) against the installed baseline.
 * Most-severe wins: loader swap > MC bump > loader bump > same.
 */
fun classifyCompat(
    installed: CachedManifestSnapshot?,
    targetMinecraft: String,
    targetLoaderName: String,
    targetLoaderVersion: String,
): CompatChange {
    if (installed == null) return CompatChange.Unknown
    return when {
        !installed.loaderName.equals(targetLoaderName, ignoreCase = true) -> CompatChange.LoaderSwap
        installed.minecraftVersion != targetMinecraft                     -> CompatChange.McBump
        installed.loaderVersion != targetLoaderVersion                    -> CompatChange.LoaderBump
        else                                                             -> CompatChange.Same
    }
}
