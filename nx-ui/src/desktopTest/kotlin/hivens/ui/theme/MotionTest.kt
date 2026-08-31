package hivens.ui.theme

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The promise a motion style makes is that it reaches everything. Brut sets
 * `animationMultiplier = 0.0f` and means it: under that style no role may resolve
 * to a duration a viewer could perceive as movement.
 *
 * Reads the roles through a real composition, because that is the only place
 * [LocalStyle] exists -- a role asked for outside one is not the thing call sites
 * use.
 */
class MotionTest {

    @OptIn(ExperimentalComposeUiApi::class)
    private fun rolesUnder(style: StyleSpec): Map<String, Int> {
        val seen = LinkedHashMap<String, Int>()
        val scene = ImageComposeScene(width = 8, height = 8, density = Density(1f)) {
            CompositionLocalProvider(LocalStyle provides style) {
                seen["tap"] = Motion.tap.durationMs
                seen["fade"] = Motion.fade.durationMs
                seen["colorShift"] = Motion.colorShift.durationMs
                seen["panelSlide"] = Motion.panelSlide.durationMs
                seen["reveal"] = Motion.reveal.durationMs
                seen["emphasis"] = Motion.emphasis.durationMs
                seen["drift"] = Motion.drift.durationMs
                seen["sweep"] = Motion.sweep.durationMs
            }
        }
        try {
            scene.render(0L)
        } finally {
            scene.close()
        }
        return seen
    }

    @Test
    fun `a still style leaves no role moving`() {
        val roles = rolesUnder(CelestiaStyle.copy(animationMultiplier = 0f))

        assertTrue(roles.isNotEmpty(), "no role was read -- the composition never ran")
        roles.forEach { (name, ms) ->
            // 1ms rather than 0: Compose rejects a zero-duration spec, and a frame
            // is 16ms, so this never renders as motion.
            assertEquals(1, ms, "$name keeps animating under a style that asks for stillness")
        }
    }

    @Test
    fun `a moving style gives every role a perceptible duration`() {
        val roles = rolesUnder(CelestiaStyle)

        roles.forEach { (name, ms) ->
            assertTrue(ms > 16, "$name resolves to ${ms}ms -- under one frame, so the role is not motion at all")
        }
    }

    /**
     * The scale is only worth having if the rungs are told apart on sight. Pins the
     * ordering rather than the numbers, so the values stay tunable.
     */
    @Test
    fun `the scale runs from a press to ambient drift`() {
        val roles = rolesUnder(CelestiaStyle)
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
