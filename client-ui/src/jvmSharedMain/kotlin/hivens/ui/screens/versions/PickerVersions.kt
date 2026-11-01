package hivens.ui.screens.versions

import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.update.VersionChannel
import hivens.ui.i18n.AppStrings
import hivens.ui.utils.humanSize

/**
 * The catalogue's builds as the picker reads them.
 *
 * One mapping, because there are now two places that show a mod's versions -- the
 * modal an installed row opens and the project page's own tab -- and they have to
 * agree about what "latest" means, which builds are marked incompatible, and how a
 * runtime line is written. Two copies of this drifted the moment one of them
 * gained a rule.
 *
 * [mcVersion] and [loaders] describe where the builds would run. Blank and empty
 * mean "nowhere in particular", which is the catalogue browsing case: nothing is
 * incompatible because nothing has been asked of it yet.
 */
fun pickerVersionsOf(
    versions: List<ModrinthVersion>,
    mcVersion: String,
    loaders: List<String>,
    installedId: String?,
    s: AppStrings,
): List<PickerVersion> {
    // Everything that has a file, newest first. A version with no file is a record
    // of a release nobody can install.
    val shown = versions.filter { it.files.isNotEmpty() }.sortedByDescending { it.datePublished }

    fun fits(v: ModrinthVersion): Boolean =
        (mcVersion.isBlank() || v.gameVersions.contains(mcVersion)) &&
            (loaders.isEmpty() || loaders.any { it in v.loaders })

    // The newest build that RUNS here, not the newest overall: the newest may be
    // for another loader entirely, and badging that one "latest" points a reader
    // at a dead end.
    val newestId = shown.firstOrNull { fits(it) }?.id

    return shown.map { v ->
        PickerVersion(
            id = v.id,
            label = v.versionNumber,
            channel = VersionChannel.of(v.versionType, v.versionNumber),
            publishedAt = v.datePublished,
            changelog = v.changelog,
            runtimeLine = listOf(v.gameVersions.joinToString(", "), v.loaders.joinToString(", "))
                .filter { it.isNotBlank() }
                .joinToString("  |  ")
                .takeIf { it.isNotBlank() },
            sizeLabel = v.files.firstOrNull { f -> f.primary }?.size?.let { humanSize(it, s) }
                ?: v.files.firstOrNull()?.size?.let { humanSize(it, s) },
            installed = v.id == installedId,
            latest = v.id == newestId,
            compatible = fits(v),
        )
    }
}

/**
 * Which way picking [target] moves an instance already on [installedAt].
 *
 * Dates rather than version strings, because a version string is the author's and
 * orders however they felt: 1.10 sorts before 1.9 as text and after it as a
 * release. Unknown on either side is [PickerIntent.Switch], which is the honest
 * answer when there is nothing to compare.
 */
fun pickerIntentFor(
    target: PickerVersion,
    versions: List<ModrinthVersion>,
    installedAt: String?,
): PickerIntent {
    if (target.installed) return PickerIntent.Switch
    val at = versions.firstOrNull { it.id == target.id }?.datePublished
    if (installedAt == null || at == null) return PickerIntent.Switch
    return when {
        at > installedAt -> PickerIntent.Upgrade
        at < installedAt -> PickerIntent.Rollback
        else -> PickerIntent.Switch
    }
}
