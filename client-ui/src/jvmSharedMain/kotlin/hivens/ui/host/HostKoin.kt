package hivens.ui.host

import androidx.compose.runtime.Composable
import org.koin.compose.KoinIsolatedContext
import org.koin.core.KoinApplication

/**
 * Draws [content] with the launcher's own Koin, [application], as what everything
 * injected below resolves from.
 *
 * The launcher never registers its graph in Koin's global context, so this is the
 * one way the composition reaches it: the host provides it around the windows it
 * draws, and the built-in widgets inject from it as before. A widget module loaded
 * from a jar is given no view of Koin at all (see the widget module loader), so it
 * cannot name these locals, and a copy of Koin it bundles reads its own, empty ones.
 */
@Composable
fun HostKoin(application: KoinApplication, content: @Composable () -> Unit) {
    KoinIsolatedContext(application, content)
}
