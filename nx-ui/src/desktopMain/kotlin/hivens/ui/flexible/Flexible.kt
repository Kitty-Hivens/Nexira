package hivens.ui.flexible

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What a [Flexible] wrapper is guarding -- a stable id plus a coarse kind so an
 * event can target only the widgets it knows how to dress (a button-fleeing event
 * ignores cards). The id is the same stable string the puppet layer uses, so one
 * name addresses a widget for both automation and events.
 */
@Immutable
data class FlexibleTarget(val id: String, val kind: FlexibleKind)

enum class FlexibleKind { Button, Card, Text, Generic }

/**
 * A behaviour layered on top of ordinary widgets while it is active -- the
 * superstructure over the base components. April Fools is one event (buttons flee,
 * text gibbers); a release celebration or a seasonal skin would be others. The
 * base widgets ([hivens.ui.nx] primitives) know nothing about events; an event only
 * ever sees widgets that opt in by wrapping in [Flexible].
 *
 * Inactive events are skipped, so an app with no active event pays nothing --
 * [decorate] is never called.
 */
interface FlexibleEvent {
    /** Stable identifier, e.g. "aprilfools". */
    val id: String

    /** Whether this event is currently augmenting the UI. */
    fun isActive(): Boolean

    /**
     * Wrap or replace [content] for [target]. The default renders [content]
     * unchanged, so an event overrides only the kinds it cares about and leaves
     * everything else alone.
     */
    @Composable
    fun decorate(target: FlexibleTarget, content: @Composable () -> Unit) {
        content()
    }
}

/**
 * The set of registered events, resolved once near the app root. Holds the events
 * statically; activeness is asked per-frame via [FlexibleEvent.isActive] so an
 * event can switch on (the calendar rolls to April 1) without re-providing the host.
 */
@Immutable
class FlexibleHost(private val events: List<FlexibleEvent>) {

    @Composable
    fun decorate(target: FlexibleTarget, content: @Composable () -> Unit) {
        decorateNested(events.filter { it.isActive() }, 0, target, content)
    }

    // Nest active events as composable layers: each wraps the rest, innermost is
    // the real content. Recursion (not a fold) keeps every layer a normal
    // composable call so Compose tracks it correctly.
    @Composable
    private fun decorateNested(
        active: List<FlexibleEvent>,
        index: Int,
        target: FlexibleTarget,
        content: @Composable () -> Unit,
    ) {
        if (index >= active.size) {
            content()
        } else {
            active[index].decorate(target) {
                decorateNested(active, index + 1, target, content)
            }
        }
    }

    companion object {
        /** No events -- [Flexible] is a pure pass-through. The production default. */
        val Empty = FlexibleHost(emptyList())
    }
}

val LocalFlexible = staticCompositionLocalOf { FlexibleHost.Empty }

/**
 * Opt a widget into the event layer. With no active event this renders [content]
 * directly (zero overhead); while an event is active it may wrap, animate or
 * replace the widget. [id] is the widget's stable name, [kind] lets events target
 * by shape.
 *
 *   Flexible("login.submit", FlexibleKind.Button) { NxButton(...) }
 */
@Composable
fun Flexible(
    id: String,
    kind: FlexibleKind = FlexibleKind.Generic,
    content: @Composable () -> Unit,
) {
    LocalFlexible.current.decorate(FlexibleTarget(id, kind), content)
}
