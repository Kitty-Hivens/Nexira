package hivens.ui.theme

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.look.preview.NeutralTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** [familyForText] moves a string to another face only where nx's interface face cannot draw it. */
class FamilyForTextTest {

    /**
     * The selector call sites depend on, evaluated in a composition because it is
     * one. A render cannot answer this on a machine that has Noto CJK installed:
     * the bundled face and the host's are the same face, so the picture is
     * identical either way and only the choice is observable.
     */
    @Test
    fun `the selector sends only what the UI face cannot draw to the CJK family`() {
        val answers = mutableMapOf<String, Boolean>()
        @OptIn(ExperimentalComposeUiApi::class)
        val scene = ImageComposeScene(width = 8, height = 8, density = Density(1f)) {
            // Inside nx's look, where the type axis answers which face a script needs.
            NxThemed(NeutralTheme) {
                listOf(
                    "Sacrifice", "Привет", "Grüße", "Xin chào", "€1",
                    "追憶のサクラメント", "Taka feat. めらみぽっぷ", "한국어", "Sacrifice ☆",
                ).forEach { answers[it] = familyForText(it) != null }
            }
        }
        scene.render()
        scene.close()

        val routed = answers.filterValues { it }.keys
        val kept = answers.filterValues { !it }.keys
        assertEquals(
            setOf("追憶のサクラメント", "Taka feat. めらみぽっぷ", "한국어", "Sacrifice ☆"),
            routed.toSet(),
            "the wrong strings were sent to the CJK family",
        )
        assertEquals(
            setOf("Sacrifice", "Привет", "Grüße", "Xin chào", "€1"),
            kept.toSet(),
            "a string the UI face covers must keep whatever the style already said",
        )
    }
}
