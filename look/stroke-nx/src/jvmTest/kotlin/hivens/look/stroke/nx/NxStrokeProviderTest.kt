package hivens.look.stroke.nx

import androidx.compose.ui.unit.dp
import hivens.look.manager.api.LookContext
import hivens.look.stroke.api.Dash
import hivens.look.stroke.api.Stroke
import hivens.look.stroke.api.StrokeApi
import hivens.look.stroke.api.StrokeRole
import hivens.ui.theme.nxStrokeAnswers
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
 * The module's stroke provider answers the stroke axis with nx's strokes, the ones nx drew before the
 * axis existed, the same before the boot as once the host is up, and asks nothing of the host for them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NxStrokeProviderTest {

    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `it answers the stroke axis as the nx family, named in every language the module ships`() {
        assertEquals(Stroke.id, NxStrokeProvider.axis)
        assertEquals("nx", NxStrokeProvider.family)
        assertEquals(StrokeApi.VERSION, NxStrokeProvider.contract)
        assertEquals(listOf<Any>(NxStrokeProvider), StrokeNxModule.contributions)
        assertTrue(StrokeNxModule.strings.tables.values.all { NxStrokeProvider.label in it && StrokeNxModule.name in it })
    }

    @Test
    fun `before the boot it answers what it answers once the host is up, and asks the host nothing for it`() {
        val services = Services()
        val boot = Boot()
        val atBoot = NxStrokeProvider.open(Context(services, boot)).answers.value
        val live = NxStrokeProvider.open(Context(services, null)).answers.value
        assertSame(nxStrokeAnswers(), atBoot)
        assertSame(nxStrokeAnswers(), live)
        assertEquals(emptyList(), services.asked)
        assertEquals(emptyList(), boot.asked)
    }

    @Test
    fun `nx's strokes are the ones nx drew before the stroke axis`() {
        val nx = nxStrokeAnswers()
        assertEquals(StrokeRole.solid(1.dp), nx.edge)
        assertEquals(StrokeRole.solid(2.dp, snap = true), nx.mark)
        assertEquals(StrokeRole.solid(1.dp), nx.divider)
        assertEquals(StrokeRole.solid(1.5.dp, dash = Dash(8.dp, 6.dp)), nx.placeholder)
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
