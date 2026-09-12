package hivens.launcher.util

import java.nio.file.Path
import java.util.jar.JarFile

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

    /**
     * The jar names [jar] declares it carries inside itself, from the
     * `ContainedDeps` manifest attribute. Empty for the overwhelming majority of
     * mods, which declare nothing.
     *
     * FML unpacks these into `mods/<mcversion>/` the first time it loads the jar,
     * so they appear on disk after the game starts and are nobody's doing but the
     * loader's. Scalar is the case that matters here: its whole purpose is to hand
     * a Scala runtime to a loader that ships none, and it does that by carrying
     * twelve jars and having FML lay them out.
     *
     * Read through [JarFile] rather than by hand so the manifest's continuation
     * lines are folded back together first. The attribute is one long
     * space-separated list and wraps at 72 bytes, which splits names across lines
     * in the middle of a word.
     *
     * A name with a path separator in it is dropped. The attribute names files at
     * the jar's root, and anything else is either malformed or an attempt to
     * describe somewhere the extraction does not go.
     */
    fun containedDeps(jar: Path): Set<String> =
        runCatching {
            JarFile(jar.toFile()).use { archive ->
                archive.manifest
                    ?.mainAttributes
                    ?.getValue(CONTAINED_DEPS)
                    ?.split(' ')
                    ?.map(String::trim)
                    ?.filter { it.isNotEmpty() && '/' !in it && '\\' !in it }
                    ?.toSet()
                    .orEmpty()
            }
        }.getOrDefault(emptySet())

    private const val CONTAINED_DEPS = "ContainedDeps"
}
