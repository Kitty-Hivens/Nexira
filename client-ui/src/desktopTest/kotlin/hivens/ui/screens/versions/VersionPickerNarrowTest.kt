package hivens.ui.screens.versions

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import hivens.core.update.VersionChannel
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.layers.LayerHost
import hivens.ui.layers.WindowLayers
import hivens.ui.theme.NxTheme
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The version picker on a phone held upright, 1080 by 2400 at density 2.5. The list
 * kept its fixed measure and left the notes beside it a column so narrow that the
 * build's date stood one character per line. Where there is no room beside the list
 * the notes go under it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class VersionPickerNarrowTest {

    private val versions = listOf(
        PickerVersion(
            id = "8",
            label = "0.1.8",
            channel = VersionChannel.Beta,
            publishedAt = "2026-06-27T19:11:00Z",
            runtimeLine = "Minecraft 1.20.1  Forge",
            installed = true,
        ),
        PickerVersion(id = "7", label = "0.1.7", channel = VersionChannel.Release, publishedAt = "2026-06-01T10:00:00Z"),
    )

    @Test
    fun `the notes keep a readable width on a phone`() {
        val scene = ImageComposeScene(1080, 2400, density = Density(2.5f)) {
            LocaleProvider(AppLocale.RUSSIAN) {
                NxTheme(dark = true) {
                    LayerHost(WindowLayers(), Modifier.fillMaxSize()) {
                        VersionPickerWindow(
                            title = "Версии",
                            packName = "Sodium",
                            packIcon = null,
                            versions = versions,
                            intentFor = { PickerIntent.Switch },
                            onConfirm = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }
        try {
            var time = 0L
            repeat(6) {
                scene.render(time)
                time += 16_000_000L
            }
            // The detail pane's title names the build the picker opened on.
            val title = assertNotNull(scene.texts().lastOrNull { it.text() == "0.1.8" && it.size.width > 0 }, "no detail title")
            val date = assertNotNull(scene.texts().firstOrNull { it.text().startsWith("2026") }, "no date in the notes")
            assertTrue(
                date.size.width > date.size.height * 3,
                "the date is ${date.size.width} wide and ${date.size.height} high, wrapped down a narrow column",
            )
            assertTrue(title.boundsInRoot.width > 300f, "the notes are ${title.boundsInRoot.width} px wide")
        } finally {
            scene.close()
        }
    }

    private fun SemanticsNode.text(): String =
        config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

    private fun ImageComposeScene.texts(): List<SemanticsNode> =
        semanticsOwners.asSequence()
            .flatMap { it.unmergedRootSemanticsNode.withDescendants() }
            .filter { it.config.getOrNull(SemanticsProperties.Text) != null }
            .toList()

    private fun SemanticsNode.withDescendants(): Sequence<SemanticsNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.withDescendants() }
}
