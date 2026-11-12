package hivens.ui.feature.catalogue.browse

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.ui.levelServicesModule
import hivens.ui.onEventThread
import hivens.ui.settle
import hivens.ui.widgets.ChildLevel
import hivens.widget.api.LocalScope
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.Dispatchers
import org.koin.compose.koinInject
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Browse's controller as the shell wires it: one per window, from the module the app loads. */
@OptIn(ExperimentalComposeUiApi::class)
class BrowseControllerLevelTest {

    private fun app(): WidgetScope = koinApplication {
        modules(levelServicesModule, module { single { WidgetScope.app(WidgetDataRegistry(), this, Dispatchers.Unconfined) } })
    }.koin.get()

    @Test
    fun `two windows get two controllers, and everything in a window gets its own`() {
        val app = app()
        val first = app.child(ScopeLevel.Window)
        val second = app.child(ScopeLevel.Window)
        val controller = first.services.get<BrowseController>()
        assertSame(controller, first.services.get<BrowseController>())
        assertNotSame(controller, second.services.get<BrowseController>())
        val page = first.child(ScopeLevel.Composition).child(ScopeLevel.Page)
        assertSame(controller, page.services.get<BrowseController>())
        assertNull(app.services.getOrNull<BrowseController>(), "no window, no controller")
        first.close()
        assertTrue(first.services.closed)
    }

    /** The injection sites ask Koin through the composition, and each one is answered by the window it sits in. */
    @Test
    fun `an injection resolves from the nearest window`() {
        val app = app()
        var inFirst: BrowseController? = null
        var inFirstPage: BrowseController? = null
        var firstOwn: BrowseController? = null
        var inSecond: BrowseController? = null
        onEventThread {
            val scene = ImageComposeScene(200, 200, density = Density(1f)) {
                CompositionLocalProvider(LocalScope provides app) {
                    ChildLevel(ScopeLevel.Window) {
                        inFirst = koinInject<BrowseController>()
                        firstOwn = LocalScope.current?.services?.get<BrowseController>()
                        ChildLevel(ScopeLevel.Composition) {
                            ChildLevel(ScopeLevel.Page) { inFirstPage = koinInject<BrowseController>() }
                        }
                    }
                    ChildLevel(ScopeLevel.Window) { inSecond = koinInject<BrowseController>() }
                }
            }
            try {
                scene.settle(2)
            } finally {
                scene.close()
            }
        }
        assertSame(assertNotNull(firstOwn), inFirst)
        assertSame(inFirst, inFirstPage)
        assertNotSame(inFirst, assertNotNull(inSecond))
    }
}
