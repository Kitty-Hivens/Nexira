package hivens.ui.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.ui.navigation.MainWindowRequests
import hivens.ui.navigation.NavRequests
import hivens.ui.widgets.Commands
import hivens.ui.widgets.LevelHost
import hivens.ui.widgets.Sources
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalSurfaceFamilies
import hivens.widget.api.SurfaceFamilies
import hivens.widget.api.WidgetScope
import hivens.widget.api.command
import hivens.widget.api.flowSource
import hivens.widget.api.rememberChildScope
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.CoroutineScope
import org.koin.compose.koinInject

/**
 * One window's handle on the host: which window it is, who owns it, the
 * composition it shows, and the registry it is in. Exists from the window's first
 * composition, before the window is registered once that composition applies.
 */
class HostWindow internal constructor(
    val id: WindowId,
    val owner: WindowOwner,
    val composition: CompositionId,
    val registry: WindowRegistry,
) {
    /**
     * Holds a system dialog this window's composition opened. When the composition
     * goes first, [revoke] runs, which is the host taking the dialog back.
     */
    fun holdDialog(revoke: () -> Unit): AutoCloseable = registry.holdDialog(composition, revoke)
}

/** The window a composable is drawn in, or null outside any window the host knows. */
val LocalHostWindow: ProvidableCompositionLocal<HostWindow?> = staticCompositionLocalOf { null }

/**
 * Draws [content] as the window [id]: registers it with the host while this call
 * stays composed, makes the window's level of the scope tree under the app level,
 * and puts there what lives per window. That is [Sources.Window] read from
 * [backend], the window commands [backend] takes, and the window's own services,
 * the surface families among them.
 *
 * Called inside the OS window, by whoever opened it. A window the registry refuses
 * is still drawn, since it is already on screen, and the refusal is logged. It then
 * has no [LocalHostWindow], so nothing it opens is held against a registration it
 * never had.
 */
@Composable
fun HostedWindow(
    id: WindowId,
    owner: WindowOwner,
    backend: WindowBackend,
    registry: WindowRegistry = koinInject(),
    content: @Composable () -> Unit,
) {
    val composition = remember(id) { CompositionId.of(id) }
    val host = remember(id, owner, composition, registry) { HostWindow(id, owner, composition, registry) }
    val scope = rememberCoroutineScope()
    val registered = remember(host, backend) { Registered(host, backend, scope) }
    val app = LocalScope.current
    val level = app?.let { rememberChildScope(it, ScopeLevel.Window) }
    if (level != null) remember(level, backend) { level.provideWindow(backend) }
    val families = remember(level) { level?.services?.getOrNull<SurfaceFamilies>() ?: SurfaceFamilies() }
    CompositionLocalProvider(
        // A refused window is in no registry, so nothing may be held against it.
        LocalHostWindow provides host.takeUnless { registered.refused },
        LocalSurfaceFamilies provides families,
    ) {
        LevelHost(level, content)
    }
}

/** Puts the window's state and its commands on its level, where they live until the level is closed. */
private fun WidgetScope.provideWindow(backend: WindowBackend) {
    val _ = provideSource(Sources.Window, flowSource(backend.state))
    backend.commands.forEach { which ->
        val _ = provideCommand(which.key, command { backend.run(which) })
    }
}

private val WindowCommand.key: CommandKey<Unit>
    get() = when (this) {
        WindowCommand.Maximize -> Commands.MaximizeWindow
        WindowCommand.Minimize -> Commands.MinimizeWindow
        WindowCommand.Restore -> Commands.RestoreWindow
        WindowCommand.Close -> Commands.CloseWindow
    }

/**
 * The window's place in the registry, for as long as the composition holds it.
 * Registered once the composition applies and taken out when it lets go, abandoned
 * included, so a window whose first frame never landed leaves nothing behind. The
 * leave is keyed on the composition, so a window whose id a newer composition took
 * over leaves the newer one in place.
 */
private class Registered(
    private val host: HostWindow,
    private val backend: WindowBackend,
    private val scope: CoroutineScope,
) : RememberObserver {
    /** True once the registry turned the window down, which takes it off [LocalHostWindow]. */
    var refused by mutableStateOf(false)
        private set

    override fun onRemembered() {
        val outcome = host.registry.register(host.id, host.owner, host.composition, backend, scope)
        refused = outcome is Registration.Refused
    }

    override fun onForgotten() = leave()

    override fun onAbandoned() = leave()

    private fun leave() {
        host.registry.unregister(host.id, host.composition)
    }
}

/**
 * Takes what is asked of the main window from outside every window, a
 * notification action say, into this window's own history, and raises the window
 * once it was handed over, unless it holds the focus already. For the main window's
 * shell, inside its [HostedWindow].
 */
@Composable
fun MainWindowNavigation(raise: () -> Unit) {
    val requests: MainWindowRequests = koinInject()
    val window: NavRequests = koinInject()
    val host = LocalHostWindow.current
    val currentRaise by rememberUpdatedState(raise)
    LaunchedEffect(requests, window, host) {
        requests.deliverTo(window) {
            if (host == null || host.registry.focused.value != host.id) currentRaise()
        }
    }
}
