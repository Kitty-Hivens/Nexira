package hivens.ui.widgets.modules

import hivens.ui.theme.Scheme
import hivens.ui.theme.Theme
import hivens.ui.theme.Themes
import hivens.ui.widgets.modules.WidgetModules.Contributed
import hivens.widget.api.ThemeContribution
import hivens.widget.api.ThemeScheme
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("Widgets")

/**
 * The themes modules brought, as themes the picker can list.
 *
 * A theme's id is its module's, a slash, then its own, so a module cannot take the id of a theme
 * that ships or of another module's. It is named in the person's language by the module's own
 * words. A theme that cannot make a scheme, or whose module throws while being read, is left out
 * with a line in the log, so it costs only itself: this runs in the shell's own composition, where a
 * module's failure would otherwise take the interface down.
 */
fun List<Contributed<ThemeContribution>>.toThemes(languageTag: String): List<Theme> = mapNotNull { entry ->
    runCatching {
        Theme(
            id = entry.scope,
            name = entry.strings.get(entry.item.label, languageTag),
            dark = entry.item.dark.toScheme(Themes.Celestia.dark),
            light = entry.item.light?.toScheme(Themes.Celestia.light!!),
        )
    }.onFailure { log.warn("Theme '{}' of module '{}' could not be used: {}", entry.item.id, entry.moduleId, it.message) }.getOrNull()
}

/** A module's colours as a scheme, reading in the launcher's own inks when it gave none. */
private fun ThemeScheme.toScheme(fallback: Scheme): Scheme = Scheme(
    steps = steps,
    inks = inks.ifEmpty { fallback.inks },
    colors = colors,
)
