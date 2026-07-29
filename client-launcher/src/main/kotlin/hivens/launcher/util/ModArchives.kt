package hivens.launcher.util

/**
 * File names a Minecraft mod loader will pick up out of `mods/`.
 *
 * `.zip` is here because FML used to accept it: Forge 1.7.10
 * (`cpw.mods.fml.common.discovery.ModDiscoverer`) matches candidates against
 * `(.+).(zip|jar)$` and logs "Found a candidate zip or jar file". By Forge
 * 1.12.2 (14.23.5) that pattern is gone and only `.jar` is discovered.
 *
 * Both are listed rather than the newer rule alone, because the launcher does
 * not get to assume the newer loader: SmartyCraft servers default to 1.7.10
 * when the list omits a version, and the legacy Forge resolver accepts any
 * Minecraft version a pack asks for. A prune that only knows about `.jar`
 * leaves a loadable file behind on exactly those.
 *
 * Removing a `.zip` on a version that would have ignored it costs nothing:
 * the file was not going to run, and the pruning it feeds is the "only what
 * the pack asks for is present" guarantee, not a guess about the loader.
 */
object ModArchives {

    private val LOADABLE_SUFFIXES = listOf(".jar", ".zip")

    /** True when [fileName] is a name a loader would try to load from `mods/`. */
    fun isLoadable(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return LOADABLE_SUFFIXES.any { lower.endsWith(it) }
    }
}
