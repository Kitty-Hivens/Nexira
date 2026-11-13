package hivens.ui.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.ui.onEventThread
import hivens.ui.settle
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.ProvideScoped
import hivens.widget.api.ScopedSource
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.flowSource
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.rememberSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The levels as the shell's owners make them: in the composition, around what they
 * draw, and gone when the owner leaves it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class LevelsTest {

    private val appKey = SourceKey<String>("test.app")
    private val pageKey = SourceKey<String>("test.page", ScopeLevel.Page)
    private val brokenKey = SourceKey<String>("test.broken", ScopeLevel.Page)

    private val registry = WidgetDataRegistry().apply {
        register(appKey, flowSource(MutableStateFlow("app")))
        registerFactory(brokenKey) { error("this factory always fails") }
    }

    private val app: WidgetScope = koinApplication {
        modules(module { single { WidgetScope.app(registry, KoinLevelServicesHost.app(this), Dispatchers.Unconfined) } })
    }.koin.get()

    /** Composes [content] under the app level and draws a few frames, all on the event thread. */
    private fun draw(content: @Composable () -> Unit, between: (ImageComposeScene) -> Unit = {}) = onEventThread {
        val scene = ImageComposeScene(200, 200, density = Density(1f)) {
            CompositionLocalProvider(LocalWidgetDataRegistry provides registry, LocalScope provides app) { content() }
        }
        try {
            scene.settle(4)
            between(scene)
        } finally {
            scene.close()
        }
    }

    private fun ImageComposeScene.next() {
        Snapshot.sendApplyNotifications()
        settle(4)
    }

    @Test
    fun `a page key read where no page is above is missing, and nothing throws`() {
        var read: ScopedSource<String>? = null
        draw({ ChildLevel(ScopeLevel.Window) { read = rememberScopedSource(pageKey) } })
        assertEquals(ScopedSource.Missing(ScopeLevel.Page, levelFound = false), read)
    }

    @Test
    fun `a value put over a page reaches the widgets below it and nobody else`() {
        var below: ScopedSource<String>? = null
        var beside: ScopedSource<String>? = null
        var sibling: ScopedSource<String>? = null
        draw({
            ChildLevel(ScopeLevel.Window) {
                ChildLevel(ScopeLevel.Composition) {
                    ChildLevel(ScopeLevel.Page) {
                        ProvideScoped(pageKey, "mine") { below = rememberScopedSource(pageKey) }
                        beside = rememberScopedSource(pageKey)
                    }
                    ChildLevel(ScopeLevel.Page) { sibling = rememberScopedSource(pageKey) }
                }
            }
        })
        assertEquals(ScopedSource.Present("mine"), below)
        assertEquals(ScopedSource.Missing(ScopeLevel.Page, levelFound = true), beside, "nothing was written to the level")
        assertEquals(ScopedSource.Missing(ScopeLevel.Page, levelFound = true), sibling)
    }

    @Test
    fun `an app key reads the same with either call, from any depth`() {
        var scoped: ScopedSource<String>? = null
        var plain: String? = null
        draw({
            ChildLevel(ScopeLevel.Window) {
                ChildLevel(ScopeLevel.Page) {
                    scoped = rememberScopedSource(appKey)
                    plain = rememberSource(appKey).value
                }
            }
        })
        assertEquals("app", scoped?.valueOrNull())
        assertEquals("app", plain)
    }

    @Test
    fun `a factory that fails leaves its key missing instead of taking the window down`() {
        var read: ScopedSource<String>? = null
        draw({ ChildLevel(ScopeLevel.Page) { read = rememberScopedSource(brokenKey) } })
        assertEquals(ScopedSource.Missing(ScopeLevel.Page, levelFound = true), read)
    }

    @Test
    fun `a reader follows what an owner outside the composition provides and withdraws`() {
        var page: WidgetScope? = null
        var read: ScopedSource<String>? = null
        draw(
            {
                ChildLevel(ScopeLevel.Page) {
                    page = LocalScope.current
                    read = rememberScopedSource(pageKey)
                }
            },
            between = { scene ->
                assertIs<ScopedSource.Missing>(read)
                val handle = assertNotNull(page).provide(pageKey, "handed over")
                scene.next()
                assertEquals("handed over", read?.valueOrNull())
                handle.close()
                scene.next()
                assertIs<ScopedSource.Missing>(read)
            },
        )
    }

    @Test
    fun `leaving the composition disposes the level made there`() {
        val shown = mutableStateOf(true)
        var page: WidgetScope? = null
        draw(
            {
                if (shown.value) ChildLevel(ScopeLevel.Page) { page = LocalScope.current }
            },
            between = { scene ->
                assertFalse(assertNotNull(page).closed)
                shown.value = false
                scene.next()
                assertTrue(assertNotNull(page).closed, "the owner left, and took its level along")
            },
        )
        assertFalse(app.closed)
    }
}
