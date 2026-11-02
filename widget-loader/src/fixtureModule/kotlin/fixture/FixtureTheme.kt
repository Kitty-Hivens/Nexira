package fixture

import androidx.compose.ui.graphics.Color
import hivens.widget.api.ThemeContribution
import hivens.widget.api.ThemeScheme

/** A theme carried by the fixture module, so the loader finds a second kind of contribution in a real jar. */
class FixtureTheme : ThemeContribution {
    override val id = "fixture.theme"
    override val label = "fixture.theme"
    override val dark = ThemeScheme(listOf(Color.Black, Color.DarkGray), listOf(Color.Red))
}
