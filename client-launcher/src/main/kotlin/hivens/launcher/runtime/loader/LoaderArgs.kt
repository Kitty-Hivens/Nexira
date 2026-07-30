package hivens.launcher.runtime.loader

/**
 * Extracts the loader-added `--tweakClass <x>` pairs from a launchwrapper-era
 * `minecraftArguments` string. The vanilla game args (username, gameDir, ...)
 * are produced by the command builder, so only the tweakers a loader injects
 * (FML's tweaker, and any coremod tweakers listed beside it) are carried
 * through the profile. Returns an empty list when the string names no tweaker;
 * a caller with a canonical fallback applies it at the call site.
 *
 * Shared by every launchwrapper-family resolver whose profile is a flat
 * `minecraftArguments` overlay (Forge <=1.12.2, Cleanroom).
 */
internal fun extractTweakClassArgs(minecraftArguments: String?): List<String> =
    extractTweakClassArgs(minecraftArguments?.trim()?.split(Regex("\\s+")).orEmpty())

/**
 * Token-list overload for the modern `arguments.game` shape (already a flat list
 * after [hivens.launcher.runtime.flattenArguments]), used by loaders whose
 * profile carries `arguments` rather than a legacy `minecraftArguments` string
 * (lwjgl3ify). Keeps only the `--tweakClass <x>` pairs; the vanilla placeholder
 * game args are emitted by the command builder.
 */
internal fun extractTweakClassArgs(tokens: List<String>): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i < tokens.size) {
        if (tokens[i] == "--tweakClass" && i + 1 < tokens.size) {
            out += "--tweakClass"
            out += tokens[i + 1]
            i += 2
        } else {
            i++
        }
    }
    return out
}
