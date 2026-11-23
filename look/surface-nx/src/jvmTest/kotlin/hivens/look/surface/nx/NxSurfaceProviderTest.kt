package hivens.look.surface.nx

import hivens.look.manager.api.LookContext
import hivens.look.surface.api.Surface
import hivens.look.surface.api.SurfaceApi
import hivens.ui.theme.nxSurfaceAnswers
import hivens.widget.api.BootContext
import hivens.widget.api.LevelServices
import hivens.widget.api.SettingsChange
import hivens.widget.api.SettingsDomain
import hivens.widget.api.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The module's surface provider answers the surface axis with nx's planes, drawing no blur where the
 * person switched it off: the same before the boot as once the host is up, following a change of that
 * choice while it stays open, from the module's own settings alone, and carrying the choice the
 * launcher kept in customization.json over once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NxSurfaceProviderTest {

    private val dir: Path = Files.createTempDirectory("nx-surface")
    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `it answers the surface axis as the nx family, named in every language the module ships`() {
        assertEquals(Surface.id, NxSurfaceProvider.axis)
        assertEquals("nx", NxSurfaceProvider.family)
        assertEquals(SurfaceApi.VERSION, NxSurfaceProvider.contract)
        assertTrue(NxSurfaceProvider in SurfaceNxModule.contributions)
        assertTrue(SurfaceNxModule.strings.tables.values.all { NxSurfaceProvider.label in it && SurfaceNxModule.name in it })
        assertNull(SettingsDomain.faultIn(NxSurfaceSettingsDomain.id))
        assertEquals(NxSurfaceSettings(), NxSurfaceSettingsDomain.defaults())
    }

    @Test
    fun `before the boot it answers what it answers once the host is up, with blur on and off`() {
        listOf(false, true).forEach { blur ->
            val settings = NxSurfaceSettings(blur = blur)
            val atBoot = NxSurfaceProvider.open(context(boot = Boot(settings))).answers.value
            val live = NxSurfaceProvider.open(context(store = Store(settings))).answers.value
            assertSame(nxSurfaceAnswers(blur), atBoot, "blur $blur before the boot")
            assertSame(nxSurfaceAnswers(blur), live, "blur $blur once up")
        }
    }

    @Test
    fun `a change of the choice moves the answers while the provider stays open`() {
        val store = Store(NxSurfaceSettings(blur = true))
        val services = Services(store)
        val open = NxSurfaceProvider.open(Context(services, null))
        val answers = open.answers
        assertSame(nxSurfaceAnswers(true), answers.value)
        val choice = services.get(NxSurfaceChoice::class)
        choice.set(false)
        assertSame(answers, open.answers)
        assertSame(nxSurfaceAnswers(false), answers.value, "blur switched off")
        assertEquals(false, store.value.value.blur)
        choice.set(true)
        assertSame(nxSurfaceAnswers(true), answers.value, "and on again")
    }

    @Test
    fun `where its own setting says nothing, the choice kept in customization dot json holds, and is written into its own`() {
        dir.resolve("customization.json").writeText("""{"surfaceBlur":false,"reduceMotion":true,"navHoverHighlight":true}""")
        assertFalse(bootBlur(Boot(NxSurfaceSettings())), "before the boot")
        val store = Store(NxSurfaceSettings())
        val open = NxSurfaceProvider.open(context(store = store))
        assertSame(nxSurfaceAnswers(false), open.answers.value, "once up")
        assertEquals(NxSurfaceSettings(blur = false), store.value.value, "carried over into its own setting")

        dir.resolve("customization.json").writeText("""{"surfaceBlur":true}""")
        assertFalse(bootBlur(Boot(NxSurfaceSettings(blur = false))), "its own setting, whatever the old file says now")
    }

    @Test
    fun `its own setting wins over the old file, and an old file that says nothing or cannot be read is blur on`() {
        dir.resolve("customization.json").writeText("""{"surfaceBlur":false}""")
        assertTrue(bootBlur(Boot(NxSurfaceSettings(blur = true))))
        val store = Store(NxSurfaceSettings(blur = true))
        NxSurfaceProvider.open(context(store = store))
        assertEquals(NxSurfaceSettings(blur = true), store.value.value, "nothing written over a choice made here")

        Files.delete(dir.resolve("customization.json"))
        assertTrue(bootBlur(Boot(NxSurfaceSettings())), "no old file")
        dir.resolve("customization.json").writeText("not json")
        assertTrue(bootBlur(Boot(NxSurfaceSettings())), "an old file that cannot be read")
        dir.resolve("customization.json").writeText("""{"surfaceBlur":"no"}""")
        assertTrue(carriedOver(dir), "a value that is not a switch")
    }

    @Test
    fun `it reads its own module's settings and nothing of another module's`() {
        val boot = Boot(NxSurfaceSettings(blur = true))
        NxSurfaceProvider.open(context(boot = boot))
        assertEquals(listOf(NxSurfaceSettingsDomain.id), boot.asked, "before the boot, its own domain alone")

        val services = Services(Store(NxSurfaceSettings(blur = true)))
        NxSurfaceProvider.open(Context(services, null))
        assertEquals(listOf<KClass<*>>(NxSurfaceChoice::class), services.asked, "once up, the module's own choice alone")
    }

    private fun context(store: Store? = null, boot: BootContext? = null): LookContext = Context(Services(store), boot)

    private inner class Context(override val services: LevelServices, override val boot: BootContext?) : LookContext {
        override val coroutineScope: CoroutineScope = scope
    }

    /** The settings store the host keeps for the module's domain, in memory. */
    private class Store(initial: NxSurfaceSettings) : SettingsStore<NxSurfaceSettings> {
        private val flow = MutableStateFlow(initial)
        override val value: StateFlow<NxSurfaceSettings> = flow
        override val readOnly: Boolean = false
        override fun update(change: SettingsChange<NxSurfaceSettings>) {
            flow.value = change.apply(flow.value)
        }
    }

    /** The services a provider of this module is handed once the host is up, which note what was asked of them. */
    private inner class Services(store: Store?) : LevelServices {
        val asked = ArrayList<KClass<*>>()
        private val choice = store?.let { NxSurfaceChoice(it, { carriedOver(dir) }, scope) }

        @Suppress("UNCHECKED_CAST")
        // The choice is handed out for its own type only.
        override fun <T : Any> find(type: KClass<T>): T? {
            asked += type
            return if (type == NxSurfaceChoice::class) choice as T? else null
        }

        override fun <T : Any> get(type: KClass<T>): T = find(type) ?: throw NoSuchElementException(type.qualifiedName)
    }

    /** This module's boot context over [settings] and the test's data directory, which notes which domains were read. */
    private inner class Boot(private val settings: NxSurfaceSettings) : BootContext {
        val asked = ArrayList<String>()
        override val dataDir: Path = dir

        @Suppress("UNCHECKED_CAST")
        // The module's domain holds its settings, and no other domain is answered.
        override fun <T : Any> settings(domain: SettingsDomain<T>): T {
            asked += domain.id
            check(domain.id == NxSurfaceSettingsDomain.id) { "asked for another module's domain ${domain.id}" }
            return settings as T
        }

        override val services: LevelServices = Services(null)
    }
}
