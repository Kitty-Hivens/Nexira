package hivens.ui.screens.detail.versions

import hivens.core.data.PackInstance
import hivens.core.update.PackBuild

/**
 * Which of [builds] the instance is actually on.
 *
 * A version label is not an identity everywhere. Modrinth publishes one version
 * per loader and Minecraft version and gives them all the same number, so asking
 * the list for "the build labelled 1.15.56" gets three answers and marking all
 * three as the installed one is the least wrong thing a label can do.
 *
 * The recorded build key answers it outright. An instance installed before that
 * was recorded falls back to the label, and where the label is ambiguous the
 * runtime it was installed with tells the candidates apart -- that is exactly
 * what they differ by. Only when even that does not separate them is the newest
 * of them assumed, which is the same guess as before and now the last resort
 * rather than the only one.
 */
internal fun installedBuildOf(builds: List<PackBuild>, pack: PackInstance): PackBuild? {
    pack.installedBuildKey?.let { key ->
        builds.firstOrNull { it.key == key }?.let { return it }
    }
    val label = pack.pinnedPackVersion ?: pack.packRef.version ?: return null
    val sharing = builds.filter { it.versionNumber == label }
    if (sharing.size <= 1) return sharing.firstOrNull()
    val manifest = pack.cachedManifest ?: return sharing.first()
    return sharing.firstOrNull { build ->
        build.minecraftVersion == manifest.minecraftVersion &&
            (build.loaderName == null || build.loaderName.equals(manifest.loaderName, ignoreCase = true))
    } ?: sharing.first()
}
