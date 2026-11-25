package hivens.look.motion.nx

import hivens.look.manager.api.LookContext
import hivens.look.motion.api.Motion
import hivens.look.motion.api.MotionApi
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
 * The module's motion provider answers the motion axis with nx's answers, holding still what only
 * decorates where the person asked for less movement: the same before the boot as once the host is up,
 * following a change of that choice while it stays open, from the module's own settings alone, and
 * carrying the choice the launcher kept in customization.json over once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NxMotionProviderTest {

    private val dir: Path = Files.createTempDirectory("nx-motion")
    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `it answers the motion axis as the nx family, named in every language the module ships`() {
        assertEquals(Motion.id, NxMotionProvider.axis)
        assertEquals("nx", NxMotionProvider.family)
        assertEquals(MotionApi.VERSION, NxMotionProvider.contract)
        assertTrue(NxMotionProvider in MotionNxModule.contributions)
        assertTrue(MotionNxModule.strings.tables.values.all { NxMotionProvider.label in it && MotionNxModule.name in it })
        assertNull(SettingsDomain.faultIn(NxMotionSettingsDomain.id))
        assertEquals(NxMotionSettings(), NxMotionSettingsDomain.defaults())
    }

    @Test
    fun `before the boot it answers what it answers once the host is up, with reduce motion off and on`() {
        listOf(false, true).forEach { reduce ->
            val settings = NxMotionSettings(reduceMotion = reduce)
            val atBoot = NxMotionProvider.open(context(boot = Boot(settings))).answers.value
            val live = NxMotionProvider.open(context(store = Store(settings))).answers.value
            assertSame(nxMotionAnswers(reduce), atBoot, "reduce motion $reduce before the boot")
            assertSame(nxMotionAnswers(reduce), live, "reduce motion $reduce once up")
        }
    }

    @Test
    fun `a change of the choice moves the answers while the provider stays open`() {
        val store = Store(NxMotionSettings(reduceMotion = false))
        val services = Services(store)
        val open = NxMotionProvider.open(Context(services, null))
        val answers = open.answers
        assertSame(nxMotionAnswers(false), answers.value)
        val choice = services.get(NxMotionChoice::class)
        choice.set(true)
        assertSame(answers, open.answers)
        assertSame(nxMotionAnswers(true), answers.value, "asked for less movement")
        assertEquals(true, store.value.value.reduceMotion)
        choice.set(false)
        assertSame(nxMotionAnswers(false), answers.value, "asked for all of it again")
    }

    @Test
    fun `where its own setting says nothing, the choice kept in customization dot json holds, and is written into its own`() {
        dir.resolve("customization.json").writeText("""{"surfaceBlur":true,"reduceMotion":true,"navHoverHighlight":true}""")
        assertTrue(bootReduceMotion(Boot(NxMotionSettings())), "before the boot")
        val store = Store(NxMotionSettings())
        val open = NxMotionProvider.open(context(store = store))
        assertSame(nxMotionAnswers(true), open.answers.value, "once up")
        assertEquals(NxMotionSettings(reduceMotion = true), store.value.value, "carried over into its own setting")

        dir.resolve("customization.json").writeText("""{"surfaceBlur":true}""")
        assertTrue(bootReduceMotion(Boot(NxMotionSettings(reduceMotion = true))), "its own setting, whatever the old file says now")
    }

    @Test
    fun `its own setting wins over the old file, and an old file that says nothing or cannot be read is no reduce motion`() {
        dir.resolve("customization.json").writeText("""{"reduceMotion":true}""")
        assertFalse(bootReduceMotion(Boot(NxMotionSettings(reduceMotion = false))))
        val store = Store(NxMotionSettings(reduceMotion = false))
        NxMotionProvider.open(context(store = store))
        assertEquals(NxMotionSettings(reduceMotion = false), store.value.value, "nothing written over a choice made here")

        Files.delete(dir.resolve("customization.json"))
        assertFalse(bootReduceMotion(Boot(NxMotionSettings())), "no old file")
        dir.resolve("customization.json").writeText("not json")
        assertFalse(bootReduceMotion(Boot(NxMotionSettings())), "an old file that cannot be read")
        dir.resolve("customization.json").writeText("""{"reduceMotion":"yes"}""")
        assertFalse(carriedOver(dir), "a value that is not a switch")
    }

    @Test
    fun `it reads its own module's settings and nothing of another module's`() {
        val boot = Boot(NxMotionSettings(reduceMotion = true))
        NxMotionProvider.open(context(boot = boot))
        assertEquals(listOf(NxMotionSettingsDomain.id), boot.asked, "before the boot, its own domain alone")

        val services = Services(Store(NxMotionSettings(reduceMotion = true)))
        NxMotionProvider.open(Context(services, null))
        assertEquals(listOf<KClass<*>>(NxMotionChoice::class), services.asked, "once up, the module's own choice alone")
    }

    private fun context(store: Store? = null, boot: BootContext? = null): LookContext = Context(Services(store), boot)

    private inner class Context(override val services: LevelServices, override val boot: BootContext?) : LookContext {
        override val coroutineScope: CoroutineScope = scope
    }

    /** The settings store the host keeps for the module's domain, in memory. */
    private class Store(initial: NxMotionSettings) : SettingsStore<NxMotionSettings> {
        private val flow = MutableStateFlow(initial)
        override val value: StateFlow<NxMotionSettings> = flow
        override val readOnly: Boolean = false
        override fun update(change: SettingsChange<NxMotionSettings>) {
            flow.value = change.apply(flow.value)
        }
    }

    /** The services a provider of this module is handed once the host is up, which note what was asked of them. */
    private inner class Services(store: Store?) : LevelServices {
        val asked = ArrayList<KClass<*>>()
        private val choice = store?.let { NxMotionChoice(it, { carriedOver(dir) }, scope) }

        @Suppress("UNCHECKED_CAST")
        // The choice is handed out for its own type only.
        override fun <T : Any> find(type: KClass<T>): T? {
            asked += type
            return if (type == NxMotionChoice::class) choice as T? else null
        }

        override fun <T : Any> get(type: KClass<T>): T = find(type) ?: throw NoSuchElementException(type.qualifiedName)
    }

    /** This module's boot context over [settings] and the test's data directory, which notes which domains were read. */
    private inner class Boot(private val settings: NxMotionSettings) : BootContext {
        val asked = ArrayList<String>()
        override val dataDir: Path = dir

        @Suppress("UNCHECKED_CAST")
        // The module's domain holds its settings, and no other domain is answered.
        override fun <T : Any> settings(domain: SettingsDomain<T>): T {
            asked += domain.id
            check(domain.id == NxMotionSettingsDomain.id) { "asked for another module's domain ${domain.id}" }
            return settings as T
        }

        override val services: LevelServices = Services(null)
    }
}
