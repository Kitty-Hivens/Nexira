package hivens.ui.editor.palette

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import hivens.widget.api.LocalWidgetServiceRegistry
import hivens.widget.api.LocalWidgetStateHost
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetServiceRegistry
import hivens.widget.api.WidgetStateHost
import hivens.widget.api.provideService
import hivens.widget.model.WidgetService
import kotlinx.serialization.json.JsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a widget drawn for the gallery is not allowed to touch.
 *
 * A preview composes the real widget down the real render path, which is the
 * whole point of it and also the hazard: the widget does not know it is a
 * picture. It registers, it persists, it reports, exactly as it would on a
 * surface, and the only thing standing between that and the running launcher is
 * the set of locals [PreviewEnvironment] substitutes.
 *
 * Each rule here is paired with the same composition run WITHOUT the
 * environment. Without that pair a test like this passes just as happily when
 * the widget never registered anything in the first place, and goes on passing
 * after somebody deletes the line it exists to protect.
 */
@OptIn(ExperimentalComposeUiApi::class)
class PreviewIsolationTest {

    private val pool = ScenePool(Density(1f))

    @AfterTest
    fun release() {
        pool.close()
    }

    private class Speaker : WidgetService

    /** Records every write instead of persisting one, so a leak is visible rather than silent. */
    private class RecordingHost : WidgetStateHost {
        val written = mutableListOf<String>()
        override fun load(instanceId: String): JsonObject? = null
        override fun store(instanceId: String, value: JsonObject) {
            written += instanceId
        }
    }

    /**
     * Composes [content] and reads [probe] while it is still mounted.
     *
     * The read has to happen inside the pool's block. Registration is undone on
     * unmount and the pool empties the scene on the way out, so a registry read
     * after the fact is empty whatever happened, and the control case would pass
     * for the wrong reason.
     */
    private fun <T> whileMounted(content: @Composable () -> Unit, probe: () -> T): T =
        pool.draw(IntSize(64, 64)) { scene ->
            scene.setContent(content)
            scene.render().close()
            probe()
        }

    @Test
    fun `a preview does not register itself in the live service registry`() {
        val live = WidgetServiceRegistry()
        val seen = whileMounted(
            content = {
                CompositionLocalProvider(LocalWidgetServiceRegistry provides live) {
                    PreviewEnvironment(WidgetDataRegistry()) {
                        provideService(Speaker::class, "preview:player", Speaker())
                    }
                }
            },
            probe = { live.first(Speaker::class) },
        )
        assertNull(
            seen,
            "a preview reached the live registry: useService picks the lowest instance id, so a " +
                "preview player can win over the real one and the surface binds to an off-screen widget",
        )
    }

    @Test
    fun `the same widget without the environment does reach it`() {
        // The control. Without this the rule above passes just as well when
        // nothing registered anything.
        val live = WidgetServiceRegistry()
        val seen = whileMounted(
            content = {
                CompositionLocalProvider(LocalWidgetServiceRegistry provides live) {
                    provideService(Speaker::class, "preview:player", Speaker())
                }
            },
            probe = { live.first(Speaker::class) },
        )
        assertNotNull(seen, "provideService no longer registers on composition, so the rule above proves nothing")
    }

    @Test
    fun `a preview does not persist state under its fictional instance id`() {
        val live = RecordingHost()
        val host = whileMounted(
            content = {
                CompositionLocalProvider(LocalWidgetStateHost provides live) {
                    PreviewEnvironment(WidgetDataRegistry()) {
                        Recorded(LocalWidgetStateHost.current)
                    }
                }
            },
            probe = { captured },
        )
        assertNotNull(host)
        assertTrue(host !== live, "the preview was handed the live store, so anything it settles at is persisted")
        assertEquals(emptyList(), live.written)
    }

    @Test
    fun `emptying the scene unmounts what the preview composed`() {
        // A kept scene keeps its composition, and a composition keeps its
        // effects: animations, flow collections, a service registration renewed
        // on every pass. Before the pool a scene was closed after every render
        // and this came for free.
        var disposed = false
        pool.draw(IntSize(64, 64)) { scene ->
            scene.setContent {
                DisposableEffect(Unit) { onDispose { disposed = true } }
            }
            scene.render().close()
        }
        assertTrue(disposed, "the pooled scene still holds the widget, so its effects go on running unseen")
    }

    @Test
    fun `a scene handed over is empty whatever the last widget left in it`() {
        var mounts = 0
        repeat(3) {
            pool.draw(IntSize(64, 64)) { scene ->
                scene.setContent { DisposableEffect(Unit) { mounts++; onDispose { } } }
                scene.render().close()
            }
        }
        assertEquals(3, mounts, "a reused scene has to mount each widget fresh rather than keep the last one")
    }

    private companion object {
        var captured: WidgetStateHost? = null
    }

    @Composable
    private fun Recorded(host: WidgetStateHost) {
        captured = host
    }
}
