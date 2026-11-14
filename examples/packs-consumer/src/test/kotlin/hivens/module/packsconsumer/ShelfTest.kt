package hivens.module.packsconsumer

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.packs.api.PackKeys
import hivens.packs.api.PackLaunch
import hivens.packs.api.PackSource
import hivens.packs.api.PackView
import hivens.widget.api.ContributedServices
import hivens.widget.api.HostApi
import hivens.widget.api.LevelServices
import hivens.widget.api.LevelServicesHost
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.ModuleKeysResult
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.command
import hivens.widget.api.current
import hivens.widget.api.flowSource
import hivens.widget.api.registerModuleKeys
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The module reads and fires the pack keys typed, through nothing but the kernel and
 * the packs API. The test stands in for the launcher: it answers the pack keys the
 * way the packs module would, then brings this module's keys in the way the host does.
 */
@OptIn(HostApi::class, ExperimentalComposeUiApi::class)
class ShelfTest {

    private object NoServices : LevelServicesHost {
        override val services = object : LevelServices {
            override fun <T : Any> find(type: KClass<T>): T? = null
            override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException(type.qualifiedName)
        }
        override fun child(level: ScopeLevel): LevelServicesHost = this
        override fun close() = Unit
    }

    private fun view(id: String, name: String, played: Long) =
        PackView(id = id, name = name, source = PackSource("mirror", id), folder = id, createdAtEpochSecond = 0, lastPlayedEpochSecond = played)

    private val library = MutableStateFlow(listOf(view("a", "Alpha", played = 10), view("b", "Beta", played = 50)))
    private val launches = MutableStateFlow(mapOf<String, PackLaunch>("b" to PackLaunch.Running))
    private val launched = CopyOnWriteArrayList<String>()

    /** The pack keys as the packs module answers them, or none of them when [withPacks] is false. */
    private fun tree(withPacks: Boolean): Triple<WidgetScope, WidgetDataRegistry, WidgetCommandRegistry> {
        val sources = WidgetDataRegistry()
        val commands = WidgetCommandRegistry()
        if (withPacks) {
            sources.registerFactory(PackKeys.Library) { flowSource(library) }
            sources.registerFactory(PackKeys.Launches) { flowSource(launches) }
            commands.registerFactory(PackKeys.LaunchPack) { command { launched += it } }
        }
        val services = ContributedServices()
        assertTrue(registerModuleKeys("packs-consumer", ShelfKeys(), sources, commands, services) is ModuleKeysResult.Registered)
        val app = WidgetScope.app(sources, services.host(NoServices, Dispatchers.Unconfined), Dispatchers.Unconfined, commands)
        return Triple(app, sources, commands)
    }

    @Test
    fun `its own key is made from the pack keys, typed, and follows them`() {
        val (app) = tree(withPacks = true)
        assertEquals(listOf(ShelfLine("b", "Beta", running = true), ShelfLine("a", "Alpha", running = false)), app.current(ShelfKeys.Shelf))
        launches.value = emptyMap()
        assertEquals(false, app.current(ShelfKeys.Shelf)?.first()?.running)
    }

    @Test
    fun `without the packs module its own key is missing too, and nothing throws`() {
        val (app) = tree(withPacks = false)
        assertNull(app.current(ShelfKeys.Shelf))
    }

    @Test
    fun `a widget reads the library and fires Play typed`() {
        val (app, sources, commands) = tree(withPacks = true)
        var names: List<String>? = null
        var play: (() -> Unit)? = null
        val scene = ImageComposeScene(50, 50, density = Density(1f)) {
            CompositionLocalProvider(
                LocalWidgetDataRegistry provides sources,
                LocalWidgetCommandRegistry provides commands,
                LocalScope provides app,
            ) {
                PackShelf { n, p ->
                    names = n
                    play = p
                }
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        assertEquals(listOf("Alpha", "Beta"), names)
        assertNotNull(play).invoke()
        assertEquals(listOf("a"), launched.toList())
    }

    @Test
    fun `a widget where the packs module is not brought in reads nothing and has no Play`() {
        val (app, sources, commands) = tree(withPacks = false)
        var read = true
        val scene = ImageComposeScene(50, 50, density = Density(1f)) {
            CompositionLocalProvider(
                LocalWidgetDataRegistry provides sources,
                LocalWidgetCommandRegistry provides commands,
                LocalScope provides app,
            ) {
                PackShelf { n, p -> read = n != null || p != null }
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
        assertTrue(!read, "it found pack keys nobody brought in")
    }
}
