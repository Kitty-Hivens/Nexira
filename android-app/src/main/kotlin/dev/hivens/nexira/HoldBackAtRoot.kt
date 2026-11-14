package dev.hivens.nexira

import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher

/**
 * The host's one rule for back: it never takes the person out of the launcher.
 *
 * Without a handler that takes it, the system answers back itself and takes the
 * person out of the launcher: from Android 12 the task of a root launcher activity
 * goes to the back, its history kept only until the system reclaims the process.
 * The composition walks its own history and decides what a back at its root means.
 * This catches a back nothing else wanted, as before the composition draws, and does
 * nothing with it.
 *
 * Added to [dispatcher] before anything else, so every handler added later is asked
 * first. Added for good rather than for a lifecycle, which would put it back on top
 * of the others each time the activity starts.
 */
internal fun holdBackAtRoot(dispatcher: OnBackPressedDispatcher) {
    dispatcher.addCallback(HoldBackAtRoot())
}

private class HoldBackAtRoot : OnBackPressedCallback(true) {
    override fun handleOnBackPressed() = Unit
}
