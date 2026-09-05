package hivens.ui.theme

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The scale is only worth having if its rungs can be told apart on sight, and if
 * every one of them is long enough to read as movement at all.
 *
 * Read through a real composition rather than from the object: the roles are
 * `@Composable` accessors, and one asked for outside a composition is not the
 * thing call sites use.
 *
 * There used to be a third case here, checking that a style asking for stillness
 * left no role moving. Nothing can ask any more -- the style axis carried one
 * motion token and went with it -- so the case had no subject. If a reduce-motion
 * preference arrives it will come from the customization layer, and this is where
 * its test belongs.
 */
class MotionTest {

    @OptIn(ExperimentalComposeUiApi::class)
    private fun roles(): Map<String, Int> {
        val seen = LinkedHashMap<String, Int>()
        val scene = ImageComposeScene(width = 8, height = 8, density = Density(1f)) {
            seen["tap"] = Motion.tap.durationMs
            seen["fade"] = Motion.fade.durationMs
            seen["colorShift"] = Motion.colorShift.durationMs
            seen["panelSlide"] = Motion.panelSlide.durationMs
            seen["reveal"] = Motion.reveal.durationMs
            seen["emphasis"] = Motion.emphasis.durationMs
            seen["drift"] = Motion.drift.durationMs
            seen["sweep"] = Motion.sweep.durationMs
        }
        try {
            scene.render(0L)
        } finally {
            scene.close()
        }
        return seen
    }

    @Test
    fun `every role has a perceptible duration`() {
        val roles = roles()
        assertTrue(roles.isNotEmpty(), "no role was read -- the composition never ran")
        roles.forEach { (name, ms) ->
            assertTrue(ms > 16, "$name resolves to ${ms}ms -- under one frame, so the role is not motion at all")
        }
    }

    /**
     * Pins the ordering rather than the numbers, so the values stay tunable.
     */
    @Test
    fun `the scale runs from a press to ambient drift`() {
        val roles = roles()
        val tap = roles.getValue("tap")

        roles.filterKeys { it != "tap" }.forEach { (name, ms) ->
            assertTrue(tap < ms, "tap (${tap}ms) must be the shortest, but $name is ${ms}ms")
        }
        assertTrue(
            roles.getValue("drift") > roles.getValue("panelSlide"),
            "ambient drift must outlast a panel the user is waiting on",
        )
    }
}
