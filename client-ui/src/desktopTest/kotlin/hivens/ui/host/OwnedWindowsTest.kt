package hivens.ui.host

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.ui.ProvideComposition
import hivens.ui.layout.DocumentStore
import hivens.ui.navigation.CompositionHistory
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.onEventThread
import hivens.ui.settle
import hivens.ui.widgets.ChildLevel
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.widget.api.CompositionKeys
import hivens.widget.api.LocalScope
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.CompositionName
import hivens.widget.model.Document
import hivens.widget.model.ScopeLevel
import hivens.widget.model.StandardDocument
import kotlinx.coroutines.Dispatchers
import java.nio.file.Path
import java.util.UUID
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The windows a composition owns, as the widgets drawn in it see them: which are open, and the commands that open and
 * close them, each composition with its own, gone when it goes.
 */
@OptIn(ExperimentalComposeUiApi::class)
class OwnedWindowsTest {

    private val graph = koinApplication {
        modules(
            module {
                single<Document> { StandardDocument.load() }
                // Nobody here edits, so the person's own file is never written and never there.
                single { DocumentStore(Path.of(System.getProperty("java.io.tmpdir"), "owned-windows-${UUID.randomUUID()}", "interface.json"), { get<Document>() }) }
                single { WidgetScope.app(WidgetDataRegistry(), KoinLevelServicesHost.app(this), Dispatchers.Unconfined) }
            },
        )
    }

    @AfterTest
    fun tearDown() = graph.close()

    private val console = LauncherConfigs.ConsoleComposition

    private class Seen {
        var open: Set<CompositionName>? = null
        var show: ((CompositionName) -> Unit)? = null
        var close: ((CompositionName) -> Unit)? = null
    }

    @Test
    fun `a composition opens and closes its own windows, and two compositions keep theirs apart`() {
        val first = Seen()
        val second = Seen()
        var drawn by mutableStateOf(true)
        onEventThread {
            val scene = ImageComposeScene(100, 100, density = Density(1f)) {
                HostKoin(graph) {
                    CompositionLocalProvider(LocalScope provides graph.koin.get<WidgetScope>()) {
                        listOf(first, second).forEach { seen ->
                            if (seen === second || drawn) {
                                ChildLevel(ScopeLevel.Composition) {
                                    ProvideComposition(CompositionHistory(LauncherConfigs.Home), {}) {
                                        seen.open = rememberScopedSource(CompositionKeys.Windows).valueOrNull()
                                        seen.show = rememberScopedCommand(CompositionKeys.ShowWindow)
                                        seen.close = rememberScopedCommand(CompositionKeys.CloseWindow)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            fun next() {
                Snapshot.sendApplyNotifications()
                scene.settle(4)
            }
            try {
                scene.settle(4)
                assertEquals(emptySet(), first.open)
                assertNotNull(first.show).invoke(console)
                next()
                assertEquals(setOf(console), first.open)
                assertEquals(emptySet(), second.open, "the other composition's windows are its own")

                assertNotNull(first.show).invoke(LauncherConfigs.Composition)
                assertNotNull(first.show).invoke(CompositionName("nowhere"))
                next()
                assertEquals(setOf(console), first.open, "nor the start composition, nor one the document lacks")

                assertNotNull(first.close).invoke(console)
                next()
                assertEquals(emptySet(), first.open)

                assertNotNull(second.show).invoke(console)
                drawn = false
                next()
                assertEquals(setOf(console), second.open, "one composition going takes nothing of another's")
            } finally {
                scene.close()
            }
        }
    }
}
