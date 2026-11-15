package fixture

import androidx.compose.ui.graphics.Color
import hivens.themes.api.ThemeContribution
import hivens.themes.api.ThemeScheme

/**
 * A theme carried by the fixture module, so the loader finds a kind of contribution a module
 * compiled into the launcher declares, the themes module's, in a real jar.
 */
class FixtureTheme : ThemeContribution {
    override val id = "fixture.theme"
    override val label = "fixture.theme"
    override val dark = ThemeScheme(listOf(Color.Black, Color.DarkGray), listOf(Color.Red))
}
