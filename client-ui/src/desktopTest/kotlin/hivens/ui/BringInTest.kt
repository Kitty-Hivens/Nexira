package hivens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.config.Storage
import hivens.test.stopThenDelete
import hivens.ui.host.LauncherHostAccess
import hivens.widget.api.CompiledModule
import hivens.widget.api.KeyContribution
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.SettingsChange
import hivens.widget.api.SettingsDomain
import hivens.widget.api.SettingsStores
import hivens.widget.api.TakenContributions
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.api.missingNeeds
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A module the host trusts brings in a compiled module the document does not name: it goes in through
 * the checks the document's modules go through, its keys, settings and contributions with it, and the
 * readers that found its keys missing read them again.
 */
@OptIn(ExperimentalComposeUiApi::class)
class BringInTest {

    private val dir: Path = Files.createTempDirectory("bring-in")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = stopThenDelete(scope, dir)

    /** A kind of contribution the taker module takes, the way the catalogue takes sources. */
    interface Book : ModuleContribution

    private class Novel(override val id: String) : Book {
        override val label: String = id
    }

    private object Taker : CompiledModule {
        override val id = "test.taker"
        override val name = id
        override val contributions: List<ModuleContribution> = emptyList()
        override val contributionKinds: List<Class<out ModuleContribution>> = listOf(Book::class.java)
    }

    @Serializable
    data class ShelfPrefs(val level: Int = 1)

    object ShelfDomain : SettingsDomain<ShelfPrefs>("shelf", ShelfPrefs.serializer()) {
        override fun defaults() = ShelfPrefs()
    }

    private val level = SourceKey<Int>("test.shelf.level", ScopeLevel.App)
    private val window = SourceKey<String>("test.shelf.window", ScopeLevel.Window)
    private val update = CommandKey<SettingsChange<ShelfPrefs>>("test.shelf.settings.update", ScopeLevel.App)
    private val novel = Novel("test.novel")

    private val shelfKeys = object : KeyContribution {
        override val id = "test.shelf.keys"
        override val label = "Shelf"
        override fun declare(keys: ModuleKeys) {
            keys.settings(ShelfDomain, SourceKey("test.shelf.settings", ScopeLevel.App), update)
            keys.source(level) { app ->
                val store = app.services.get<SettingsStores>().of(ShelfDomain)
                flowSource(store.value.map { it.level }.stateIn(app.coroutineScope, SharingStarted.Eagerly, store.value.value.level))
            }
            keys.source(window) { flowSource(MutableStateFlow("shelf")) }
        }
    }

    /** A compiled module with keys, settings and a book the taker takes. */
    private inner class Shelf(override val id: String = "test.shelf") : CompiledModule {
        override val name = id
        override val contributions: List<ModuleContribution> = listOf(shelfKeys, novel)
    }

    /** A module with a key of its own and a key contribution that declares one the shelf holds. */
    private inner class Squatter : CompiledModule {
        override val id = "test.squatter"
        override val name = id
        override val contributions: List<ModuleContribution> = listOf(
            object : KeyContribution {
                override val id = "test.squatter.own"
                override val label = "Own"
                override fun declare(keys: ModuleKeys) = keys.source(SourceKey<Int>("test.squatter.own", ScopeLevel.App)) { flowSource(MutableStateFlow(1)) }
            },
            object : KeyContribution {
                override val id = "test.squatter.taken"
                override val label = "Taken"
                override fun declare(keys: ModuleKeys) = keys.source(level) { flowSource(MutableStateFlow(-1)) }
            },
        )
    }

    private val own = SourceKey<Int>("test.squatter.own", ScopeLevel.App)

    private fun writeShelf(value: Int) {
        dir.resolve(Storage.SETTINGS_DIR).createDirectories().resolve("shelf.json").writeText("""{"schema":1,"value":{"level":$value}}""")
    }

    private fun taken(shell: ModuleAppLevel): TakenContributions = assertNotNull(LauncherHostAccess.servicesOf(shell.app)).get<TakenContributions>()

    private fun eventually(what: String, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) {
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue(condition(), what)
    }

    @Test
    fun `a compiled module the document does not name is brought in with its keys, its settings and its contributions`() {
        writeShelf(5)
        val shelf = Shelf()
        ModuleAppLevel(dir, listOf(Taker, shelf), scope, named = setOf(Taker.id)).use { shell ->
            assertNull(shell.app.find(level), "not in yet")
            assertEquals(emptyList(), taken(shell).all.value.map { it.item })

            assertTrue(shell.bringIn.bringIn(shelf.id))
            assertEquals(5, shell.value(level), "its key answers from its own settings")
            eventually("its book reaches the module that takes books") { taken(shell).all.value.map { it.item } == listOf(novel) }
            assertEquals(setOf(Taker.id, shelf.id), shell.modulesIn.ids.value)

            assertTrue(shell.bringIn.bringIn(shelf.id), "a module in is answered at once, and nothing is registered twice")
            assertTrue(shell.bringIn.bringIn(Taker.id), "a module the document names is in")
        }
    }

    @Test
    fun `a module brought in gets in what it would by being named, through the same checks`() {
        val named = ModuleAppLevel(dir, listOf(Shelf(), Squatter()), scope)
        val namedOwn = named.app.find(own) != null
        val namedLevel = named.value(level)
        named.close()

        ModuleAppLevel(dir, listOf(Shelf(), Squatter()), scope, named = setOf("test.shelf")).use { shell ->
            assertFalse(shell.bringIn.bringIn("test.squatter"), "a key another module holds refuses that contribution, so the module is not wholly in")
            assertEquals(namedOwn, shell.app.find(own) != null, "its other contribution went in, as it does for a module the document names")
            assertTrue(namedOwn)
            assertEquals(namedLevel, shell.value(level), "the shelf keeps its key")
            assertFalse(shell.bringIn.bringIn("test.squatter"), "and it is not tried again")
        }
    }

    @Test
    fun `a module the build does not carry is not brought in, a jar's among them`() {
        ModuleAppLevel(dir, listOf(Shelf()), scope, named = emptySet()).use { shell ->
            assertFalse(shell.bringIn.bringIn("someone.jar"))
            assertFalse(shell.bringIn.bringIn("someone.jar"))
            assertEquals(emptySet(), shell.modulesIn.ids.value)
        }
    }

    // Each read below is composed in a scope of its own, so it is read again only where its own read
    // asked to be told, and not because a read beside it was.

    @Composable
    private fun <T> Reads(key: SourceKey<T>, seen: (T?) -> Unit) = seen(rememberScopedSource(key).valueOrNull())

    @Composable
    private fun ReadsCommand(key: CommandKey<*>, seen: (Boolean) -> Unit) = seen(rememberScopedCommand(key) != null)

    @Composable
    private fun ReadsMissing(ids: List<String>, seen: (List<String>) -> Unit) = seen(missingNeeds(ids))

    @Test
    fun `a widget that found a key missing reads it once the module is brought in from another thread`() {
        writeShelf(3)
        val shelf = Shelf()
        ModuleAppLevel(dir, listOf(shelf), scope, named = emptySet()).use { shell ->
            val windowLevel = shell.app.child(ScopeLevel.Window)
            val read = HashMap<String, Any?>()
            var before: Map<String, Any?> = emptyMap()
            onEventThread {
                val scene = ImageComposeScene(8, 8, Density(1f)) {
                    CompositionLocalProvider(
                        LocalScope provides windowLevel,
                        LocalWidgetDataRegistry provides shell.registry,
                        LocalWidgetCommandRegistry provides shell.commands,
                    ) {
                        Reads(level) { read["app source"] = it }
                        Reads(window) { read["window source"] = it }
                        ReadsCommand(update) { read["app command"] = it }
                        ReadsMissing(listOf(level.id, window.id)) { read["missing"] = it }
                    }
                }
                try {
                    scene.settle(1)
                    before = HashMap(read)
                    thread { shell.bringIn.bringIn(shelf.id) }.join()
                    scene.settle(2)
                } finally {
                    scene.close()
                }
            }
            assertEquals<Map<String, Any?>>(
                mapOf("app source" to null, "window source" to null, "app command" to false, "missing" to listOf(level.id, window.id)),
                before,
            )
            assertEquals<Map<String, Any?>>(
                mapOf("app source" to 3, "window source" to "shelf", "app command" to true, "missing" to emptyList<String>()),
                read,
                "every read that found the shelf's keys missing reads them again",
            )
        }
    }
}
