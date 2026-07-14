package hivens.ui.flexible

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Something that happened in the app that a [FlexibleEvent] -- or, later, a rule or
 * an achievement -- can react to. Kept open through [Named] so the app side can
 * emit its own keys (a pack launched, navigation, an absurd config combo) without
 * the leaf module knowing about them; typed platform signals can be added as the
 * reactive surface grows.
 */
sealed interface FlexibleSignal {
    /** An app-defined signal: a stable [key] and an optional [payload]. */
    data class Named(val key: String, val payload: Any? = null) : FlexibleSignal
}

/**
 * Emits signals and lets listeners subscribe. The interface lives in the leaf
 * module (which is DI-free); client-ui provides the implementation and wires the
 * real emitters (launch, navigation, the date rolling to April 1, config changes).
 * This is the single substrate a rule engine, achievements and idle cinematics
 * would all subscribe to -- one bus, not three parallel ones.
 */
interface FlexibleSignalBus {
    fun emit(signal: FlexibleSignal)

    /** Register [listener]; close the returned handle to stop receiving. */
    fun subscribe(listener: (FlexibleSignal) -> Unit): AutoCloseable

    /** No bus -- emits nowhere, subscribes to nothing. The production default
     *  until client-ui provides a real one. */
    object Noop : FlexibleSignalBus {
        override fun emit(signal: FlexibleSignal) {}
        override fun subscribe(listener: (FlexibleSignal) -> Unit) = AutoCloseable {}
    }
}

val LocalFlexibleSignals = staticCompositionLocalOf<FlexibleSignalBus> { FlexibleSignalBus.Noop }

/**
 * Provides the [FlexibleHost] and the [FlexibleSignalBus], and subscribes every
 * event to the bus for the lifetime of this composition so events react to
 * triggers instead of polling. Place once near the app root.
 */
@Composable
fun FlexibleHostProvider(
    events: List<FlexibleEvent>,
    bus: FlexibleSignalBus,
    content: @Composable () -> Unit,
) {
    val host = remember(events) { FlexibleHost(events) }
    DisposableEffect(events, bus) {
        val subscriptions = events.map { event -> bus.subscribe(event::onSignal) }
        onDispose { subscriptions.forEach(AutoCloseable::close) }
    }
    CompositionLocalProvider(
        LocalFlexible provides host,
        LocalFlexibleSignals provides bus,
    ) {
        content()
    }
}
