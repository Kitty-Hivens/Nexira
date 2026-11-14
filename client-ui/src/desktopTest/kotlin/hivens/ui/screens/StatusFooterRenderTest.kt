package hivens.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.LocaleProvider
import hivens.ui.i18n.stringsFor
import hivens.ui.theme.NxTheme
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The console's status row on a phone held upright, 1080 by 2400 at density 2.5.
 * Its follow chip was a Material text button held to the row's height, which kept
 * its own padding inside and left the label ten pixels of height, out of sight.
 */
@OptIn(ExperimentalComposeUiApi::class)
class StatusFooterRenderTest {

    private val strings = stringsFor(AppLocale.RUSSIAN)

    @Test
    fun `the follow chip shows its whole label on a phone`() = assertChipWhole(historyOffset = 0, searchActive = false)

    @Test
    fun `the follow chip stays whole when the counts are long and a search is on`() =
        assertChipWhole(historyOffset = 120_000, searchActive = true)

    private fun assertChipWhole(historyOffset: Int, searchActive: Boolean) {
        val scene = ImageComposeScene(PHONE_WIDTH, 2400, density = Density(2.5f)) {
            LocaleProvider(AppLocale.RUSSIAN) {
                NxTheme(dark = true) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                        StatusFooter(
                            strings        = strings,
                            filtered       = 48_211,
                            total          = 50_000,
                            historyOffset  = historyOffset,
                            warnCount      = 1_234,
                            errorCount     = 567,
                            following      = true,
                            searchActive   = searchActive,
                            matchCurrent   = 12,
                            matchTotal     = 5_000,
                            searchCapped   = true,
                            onResumeFollow = {},
                        )
                    }
                }
            }
        }
        try {
            repeat(3) { scene.render() }
            val label = assertNotNull(scene.text(strings.consoleStatusFollow), "no follow chip")
            // A ten sp label at density 2.5 is twenty-five pixels of type before any
            // line spacing. The phone showed it ten pixels high.
            assertTrue(label.size.height >= LABEL_MIN_PX, "the chip's label is laid out ${label.size.height} px high")
            val drawn = label.boundsInRoot
            assertTrue(
                drawn.height >= label.size.height - 1f,
                "the chip's label is drawn ${drawn.height} of its ${label.size.height} px high",
            )
            assertTrue(drawn.width >= label.size.width - 1f, "the chip's label is drawn ${drawn.width} of its ${label.size.width} px wide")
            assertTrue(drawn.right <= PHONE_WIDTH, "the chip runs off the screen at ${drawn.right}")
        } finally {
            scene.close()
        }
    }

    /** The text node itself, not the control it is merged into, so its own bounds are what is measured. */
    private fun ImageComposeScene.text(label: String): SemanticsNode? =
        semanticsOwners.asSequence()
            .flatMap { it.unmergedRootSemanticsNode.withDescendants() }
            .firstOrNull { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == label } }

    private fun SemanticsNode.withDescendants(): Sequence<SemanticsNode> =
        sequenceOf(this) + children.asSequence().flatMap { it.withDescendants() }

    private companion object {
        const val PHONE_WIDTH = 1080

        /** Ten sp at density 2.5, the label's size before any line spacing. */
        const val LABEL_MIN_PX = 25f
    }
}
