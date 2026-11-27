package hivens.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.widgets.ChildLevel
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetRegistry
import hivens.widget.api.CompositionKeys
import hivens.widget.api.ProvideScoped
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.SlotRenderer
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.model.Composition
import hivens.widget.model.CompositionName
import hivens.widget.model.Config
import hivens.widget.model.ConfigId
import hivens.widget.model.ConfigLayout
import hivens.widget.model.Document
import hivens.history.api.History
import hivens.widget.model.LayoutId
import hivens.widget.model.ScopeLevel
import hivens.pages.api.Show
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.Dispatchers
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The config view draws a config at one place, so what the composition has once is the same node in every config: a
 * block a slot of the frame stands in two configs is not made again between them, while a page's content is made per
 * page, and a config that holds nothing there draws nothing there.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ConfigViewTest {

    /** An app level the composition's level stands under, which is where the config drawn is put. */
    private val app: WidgetScope = koinApplication {
        val data = WidgetDataRegistry().apply { declare(CompositionKeys.Config) }
        modules(module { single { WidgetScope.app(data, KoinLevelServicesHost.app(this), Dispatchers.Unconfined) } })
    }.koin.get()

    /** How many times each widget was made, by name. */
    private val made = HashMap<String, Int>()

    private val frame = WidgetKind("test.frame")
    private val block = WidgetKind("test.block")
    private val leaf = WidgetKind("test.leaf")
    private val body = SlotId("body")
    private val side = SlotId("side")

    private val a = ConfigId("a")
    private val b = ConfigId("b")
    private val c = ConfigId("c")
    private val composition = CompositionName("test")

    private val document: Document = run {
        fun config(page: String, blockInSide: Boolean) = Config(
            layouts = listOf(
                ConfigLayout(
                    LayoutId("main"),
                    SlotContent(
                        listOf(
                            WidgetInstance(
                                frame,
                                "frame",
                                children = mapOf(
                                    body to SlotContent(listOf(WidgetInstance(leaf, page))),
                                    side to SlotContent(if (blockInSide) listOf(WidgetInstance(block, "block")) else emptyList()),
                                    BOTTOM to SlotContent(listOf(WidgetInstance(leaf, "only-on-b", shownOn = listOf(b)))),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        Document(
            start = composition,
            compositions = mapOf(
                composition to Composition(
                    start = a,
                    singles = mapOf(
                        frame to WidgetInstance(frame, "frame", children = mapOf(body to SlotContent(), side to SlotContent(), BOTTOM to SlotContent())),
                        block to WidgetInstance(block, "block"),
                    ),
                    configs = mapOf(a to config("on-a", true), b to config("on-b", true), c to config("on-c", false)),
                ),
            ),
        )
    }

    private inner class Counted(override val kind: WidgetKind) : WidgetDescriptor {
        override val displayName = kind.value
        override val removable = true

        @Composable
        override fun Render(instance: WidgetInstance) {
            remember(instance.instanceId) { made.merge(instance.instanceId, 1, Int::plus) }
            Box(Modifier.size(4.dp))
        }
    }

    private object Frame : WidgetDescriptor {
        override val kind = WidgetKind("test.frame")
        override val displayName = "frame"
        override val removable = false
        override val slots = listOf(SlotId("body"), SlotId("side"), BOTTOM)
        override val pages = listOf(SlotId("body"))

        @Composable
        override fun Render(instance: WidgetInstance) {
            Box(Modifier.fillMaxSize()) {
                SlotRenderer(instance, SlotId("body"))
                SlotRenderer(instance, SlotId("side"))
                SlotRenderer(instance, BOTTOM)
            }
        }
    }

    private val registry = object : WidgetRegistry {
        private val kinds = listOf(Frame, Counted(block), Counted(leaf)).associateBy { it.kind }

        override fun all(): Map<WidgetKind, WidgetDescriptor> = kinds

        override fun get(kind: WidgetKind): WidgetDescriptor? = kinds[kind]
    }

    /** Draws the history from [a] through each of [moves], letting each swap finish. */
    private fun visit(vararg moves: History.() -> History) {
        var history by mutableStateOf(History(a))
        onEventThread {
            val scene = ImageComposeScene(200, 200, Density(1f)) {
                CompositionLocalProvider(LocalWidgetRegistry provides registry, LocalScope provides app) {
                    ChildLevel(ScopeLevel.Composition) {
                        ProvideScoped(CompositionKeys.Config, history.current.config) {
                            ConfigView(composition, document, history, Modifier.fillMaxSize())
                        }
                    }
                }
            }
            try {
                var t = 0L
                fun frames() = repeat(40) {
                    t += FRAME_NANOS
                    scene.render(t).close()
                }
                frames()
                moves.forEach { move ->
                    history = history.move()
                    Snapshot.sendApplyNotifications()
                    frames()
                }
            } finally {
                scene.close()
            }
        }
    }

    @Test
    fun `a block two configs stand in a slot that holds no page is one node across them`() {
        visit({ show(Show(b)) }, { back() })
        assertEquals(1, made["block"], "made once: $made")
    }

    @Test
    fun `a page's content is made for its page`() {
        visit({ show(Show(b)) })
        assertEquals(1, made["on-a"], "$made")
        assertEquals(1, made["on-b"], "$made")
    }

    @Test
    fun `a config that does not stand the block draws none, and one that does makes it again`() {
        visit({ show(Show(c)) }, { show(Show(a)) })
        assertEquals(2, made["block"], "$made")
    }

    @Test
    fun `a widget shown on another config is not drawn`() {
        visit()
        assertEquals(null, made["only-on-b"], "$made")
    }

    @Test
    fun `a widget shown on one config is drawn there only`() {
        visit({ show(Show(b)) }, { back() })
        assertEquals(1, made["only-on-b"], "made on b and nowhere else: $made")
    }
}

private val BOTTOM = SlotId("bottom")
