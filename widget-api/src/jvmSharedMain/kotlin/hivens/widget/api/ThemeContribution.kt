package hivens.widget.api

import androidx.compose.ui.graphics.Color

/**
 * The colours of one theme in one mode: the ladder of surfaces from the ground up, the inks drawn
 * on them, and the accent colours in the order the interface reaches for them.
 *
 * Only [steps] and [colors] have to name something. A theme that gives no [inks] reads in the
 * launcher's own, as the themes that ship without ones do.
 */
data class ThemeScheme(
    val steps: List<Color>,
    val colors: List<Color>,
    val inks: List<Color> = emptyList(),
)

/**
 * A theme a module brings. It joins the launcher's own in the theme picker, under the module's
 * name, and is chosen like any of them. A choice that names a theme whose module is off or gone
 * lands on the default theme and comes back with the module.
 *
 * Without a [light] scheme the theme stays dark in light mode, as the launcher's own authored
 * themes do.
 */
interface ThemeContribution : ModuleContribution {
    val dark: ThemeScheme
    val light: ThemeScheme? get() = null
}
