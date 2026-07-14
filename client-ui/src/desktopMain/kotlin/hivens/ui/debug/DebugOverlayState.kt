package hivens.ui.debug

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hivens.config.Branding
import hivens.core.data.ReleaseChannel
import hivens.widget.api.SlotChromeModifier
import hivens.widget.api.WidgetDecorator

/**
 * Switchboard for the dev UI-debug overlay. A process-lifetime Koin singleton so
 * the toggle survives shell recomposition and the crash-restart loop.
 *
 * [available] gates the whole feature on build identity: a clean release tag
 * classifies as [ReleaseChannel.Release] and the overlay is unreachable there, so
 * no debug chrome and no per-widget instrumentation ships to release users. A
 * dev/source build (commits-ahead / dirty / -dev) is [ReleaseChannel.Dev]; a
 * prerelease (-preview / -beta) is [ReleaseChannel.Beta] -- both keep the tool, so
 * testers on a preview or nightly get it while release users never do. It stays
 * inert until [enabled] regardless.
 *
 * When the update tier model lands a distinct Nightly channel, this stays correct:
 * anything not [ReleaseChannel.Release] is a non-release build that may debug.
 */
class DebugOverlayState {
    /** True on any non-release build. See the class doc for the release-proofing. */
    val available: Boolean =
        ReleaseChannel.classify(Branding.VERSION.removePrefix("v")) != ReleaseChannel.Release

    /** Window-space node bounds reported by the debug decorators; drawn by DebugOverlay. */
    val bounds = DebugBoundsRegistry()

    /** Master switch. Never true on a release build (see [toggle]). */
    var enabled by mutableStateOf(false)
        private set

    // Per-facet flags. The master switch is the coarse on/off; these prune what the
    // enabled overlay actually draws. Bounds + HUD default on (the common case);
    // rulers and recomposition are opt-in from the panel (noisier / costlier).
    var slotBounds by mutableStateOf(true)
    var widgetBounds by mutableStateOf(true)
    var spacingRulers by mutableStateOf(false)
    var recomposition by mutableStateOf(false)
    var perfHud by mutableStateOf(true)

    /** Standalone FX sandbox: a centered demo card wired to the disintegrate effect. */
    var fxDemo by mutableStateOf(false)

    /** Any facet that needs per-node bounds instrumentation (the perf HUD does not). */
    val needsDecorators: Boolean get() = slotBounds || widgetBounds || spacingRulers || recomposition

    // Stable decorator instances held on the singleton so the root provider swaps
    // identity, not the lambda -- and so the instrumentation mounts only while a facet
    // needs it, keeping a non-release build identical to release when the overlay is off.
    val widgetDecorator: WidgetDecorator by lazy { debugWidgetDecorator(this) }
    val slotChrome: SlotChromeModifier by lazy { debugSlotChromeModifier(this) }

    /** Master on/off. A no-op on a release build so debug chrome never lights up. */
    fun toggle() {
        if (!available) return
        enabled = !enabled
        if (!enabled) bounds.clear() // drop stale rects so a re-enable starts clean
    }
}
