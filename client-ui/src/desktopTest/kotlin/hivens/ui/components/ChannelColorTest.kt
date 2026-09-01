package hivens.ui.components

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import hivens.core.data.ReleaseChannel
import hivens.core.update.VersionChannel
import hivens.ui.theme.NxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * The launcher's own channels and a pack build's channels are separate
 * vocabularies -- one has six values, the other three, because Dev, Git and
 * Nightly have no counterpart on the pack side. The user reads the same three
 * words either way, and read them in two different colours until these were tied
 * together: beta blue in About and yellow in the version picker, alpha yellow in
 * one and red in the other.
 *
 * Pins the tie rather than the colours, so the palette stays free to move.
 */
class ChannelColorTest {

    private val shared = listOf(
        VersionChannel.Release to ReleaseChannel.Release,
        VersionChannel.Beta to ReleaseChannel.Beta,
        VersionChannel.Alpha to ReleaseChannel.Alpha,
    )

    @OptIn(ExperimentalComposeUiApi::class)
    private fun colours(): Pair<Map<VersionChannel, Color>, Map<ReleaseChannel, Color>> {
        val pack = LinkedHashMap<VersionChannel, Color>()
        val app = LinkedHashMap<ReleaseChannel, Color>()
        val scene = ImageComposeScene(width = 8, height = 8, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                VersionChannel.entries.forEach { pack[it] = channelColor(it) }
                ReleaseChannel.entries.forEach { app[it] = channelColor(it) }
            }
        }
        try {
            scene.render(0L)
        } finally {
            scene.close()
        }
        return pack to app
    }

    @Test
    fun `the same word is the same colour on both scales`() {
        val (pack, app) = colours()

        shared.forEach { (build, launcher) ->
            assertEquals(
                app.getValue(launcher),
                pack.getValue(build),
                "${build.name} reads one colour as a pack build and another as a launcher channel",
            )
        }
    }

    @Test
    fun `the tiers stay told apart`() {
        val (_, app) = colours()
        val distinct = app.values.toSet()

        // Dev is deliberately plain text rather than an accent, so it is the one
        // tier allowed to share nothing but still count.
        assertEquals(
            app.size,
            distinct.size,
            "two channels resolve to the same colour, so the scale no longer ranks them",
        )
        assertNotEquals(
            app.getValue(ReleaseChannel.Release),
            app.getValue(ReleaseChannel.Nightly),
            "the most stable and the rawest tier must not look alike",
        )
    }
}
