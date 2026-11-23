package hivens.look.timing.nx

import hivens.look.manager.api.LookContext
import hivens.look.timing.api.Timing
import hivens.look.timing.api.TimingApi
import hivens.look.timing.api.TooltipKind
import hivens.ui.theme.nxTimingAnswers
import hivens.widget.api.BootContext
import hivens.widget.api.LevelServices
import hivens.widget.api.SettingsDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The module's timing provider answers the timing axis with nx's waits, the ones nx waited before the
 * axis existed, the same before the boot as once the host is up, and asks nothing of the host for them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NxTimingProviderTest {

    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `it answers the timing axis as the nx family, named in every language the module ships`() {
        assertEquals(Timing.id, NxTimingProvider.axis)
        assertEquals("nx", NxTimingProvider.family)
        assertEquals(TimingApi.VERSION, NxTimingProvider.contract)
        assertEquals(listOf<Any>(NxTimingProvider), TimingNxModule.contributions)
        assertTrue(TimingNxModule.strings.tables.values.all { NxTimingProvider.label in it && TimingNxModule.name in it })
    }

    @Test
    fun `before the boot it answers what it answers once the host is up, and asks the host nothing for it`() {
        val services = Services()
        val boot = Boot()
        val atBoot = NxTimingProvider.open(Context(services, boot)).answers.value
        val live = NxTimingProvider.open(Context(services, null)).answers.value
        assertSame(nxTimingAnswers(), atBoot)
        assertSame(nxTimingAnswers(), live)
        assertEquals(emptyList(), services.asked)
        assertEquals(emptyList(), boot.asked)
    }

    @Test
    fun `nx's waits are the ones nx's tooltips waited before the timing axis`() {
        val nx = nxTimingAnswers()
        assertEquals(400, nx.tooltipDelay(TooltipKind.Label))
        assertEquals(600, nx.tooltipDelay(TooltipKind.Explain))
        assertEquals(700, nx.tooltipDelay(TooltipKind.Preview))
    }

    private inner class Context(override val services: LevelServices, override val boot: BootContext?) : LookContext {
        override val coroutineScope: CoroutineScope = scope
    }

    /** Services that note what was asked of them and hand out nothing. */
    private class Services : LevelServices {
        val asked = ArrayList<KClass<*>>()

        override fun <T : Any> find(type: KClass<T>): T? {
            asked += type
            return null
        }

        override fun <T : Any> get(type: KClass<T>): T = find(type) ?: throw NoSuchElementException(type.qualifiedName)
    }

    /** A boot context that notes which domains were read and holds none. */
    private class Boot : BootContext {
        val asked = ArrayList<String>()
        override val dataDir: Path = Path.of("unused")

        override fun <T : Any> settings(domain: SettingsDomain<T>): T {
            asked += domain.id
            return domain.defaults()
        }

        override val services: LevelServices = Services()
    }
}
