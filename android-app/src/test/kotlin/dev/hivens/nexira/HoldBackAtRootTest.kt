package dev.hivens.nexira

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Back at the root never takes the person out of the launcher. The dispatcher's
 * fallback is what the activity runs when no handler takes a back, the system's own
 * answer that leaves the launcher.
 */
class HoldBackAtRootTest {

    @Test
    fun `without the hold a back nothing takes falls through to the system`() {
        var leftLauncher = 0
        val dispatcher = OnBackPressedDispatcher { leftLauncher++ }
        dispatcher.onBackPressed()
        assertEquals(1, leftLauncher, "the premise: an unhandled back is what leaves the launcher")
    }

    @Test
    fun `a back nothing else takes is held and the launcher stays`() {
        var leftLauncher = 0
        val dispatcher = OnBackPressedDispatcher { leftLauncher++ }
        holdBackAtRoot(dispatcher)
        dispatcher.onBackPressed()
        dispatcher.onBackPressed()
        assertEquals(0, leftLauncher)
    }

    @Test
    fun `a handler added after the hold is asked first`() {
        var leftLauncher = 0
        var walked = 0
        val dispatcher = OnBackPressedDispatcher { leftLauncher++ }
        holdBackAtRoot(dispatcher)
        val history = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                walked++
            }
        }
        dispatcher.addCallback(history)
        dispatcher.onBackPressed()
        assertEquals(1, walked, "the composition's history hears back before the hold")

        history.isEnabled = false
        dispatcher.onBackPressed()
        assertEquals(1, walked)
        assertEquals(0, leftLauncher, "and with nothing else listening the launcher still stays")
    }
}
