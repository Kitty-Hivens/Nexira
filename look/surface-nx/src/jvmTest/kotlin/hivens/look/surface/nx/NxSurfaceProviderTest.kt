package hivens.look.surface.nx

import androidx.compose.ui.graphics.Color
import hivens.look.manager.api.LookContext
import hivens.look.surface.api.Backdrop
import hivens.look.surface.api.Plane
import hivens.look.surface.api.PlateAnswer
import hivens.look.surface.api.Surface
import hivens.look.surface.api.SurfaceApi
import hivens.look.surface.api.SurfaceKind
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
 * The module's surface provider answers the surface axis with nx's planes, the ones nx drew before the
 * axis existed, the same before the boot as once the host is up, and asks nothing of the host for them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NxSurfaceProviderTest {

    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `it answers the surface axis as the nx family, named in every language the module ships`() {
        assertEquals(Surface.id, NxSurfaceProvider.axis)
        assertEquals("nx", NxSurfaceProvider.family)
        assertEquals(SurfaceApi.VERSION, NxSurfaceProvider.contract)
        assertEquals(listOf<Any>(NxSurfaceProvider), SurfaceNxModule.contributions)
        assertTrue(SurfaceNxModule.strings.tables.values.all { NxSurfaceProvider.label in it && SurfaceNxModule.name in it })
    }

    @Test
    fun `before the boot it answers what it answers once the host is up, and asks the host nothing for it`() {
        val services = Services()
        val boot = Boot()
        val atBoot = NxSurfaceProvider.open(Context(services, boot)).answers.value
        val live = NxSurfaceProvider.open(Context(services, null)).answers.value
        assertSame(nxSurfaceAnswers(), atBoot)
        assertSame(nxSurfaceAnswers(), live)
        assertEquals(emptyList(), services.asked)
        assertEquals(emptyList(), boot.asked)
    }

    @Test
    fun `nx's planes are opaque, a card one step above its holder with a bevelled edge, chrome with none`() {
        val nx = nxSurfaceAnswers()
        val holder = Plane(1, Color.Gray, Backdrop.Page)
        val card = nx.plane(SurfaceKind.Card, holder, 4)
        assertEquals(listOf(2, 2), listOf(card.depth, card.contentDepth))
        assertEquals(listOf(1f, 1f), listOf(card.opacity, card.edgeDp))
        val chrome = nx.plane(SurfaceKind.Chrome, holder, 4)
        assertEquals(listOf(2, 1), listOf(chrome.depth, chrome.contentDepth))
        assertEquals(0f, chrome.edgeDp)
        assertEquals(PlateAnswer(null, 0f), nx.plate)
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
