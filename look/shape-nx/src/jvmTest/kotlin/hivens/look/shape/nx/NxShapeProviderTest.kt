package hivens.look.shape.nx

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import hivens.look.manager.api.LookContext
import hivens.look.shape.api.PlateOutline
import hivens.look.shape.api.Shape
import hivens.look.shape.api.ShapeApi
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
 * The module's shape provider answers the shape axis with nx's corners, the ones nx drew before the
 * axis existed, the same before the boot as once the host is up, and asks nothing of the host for them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NxShapeProviderTest {

    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `it answers the shape axis as the nx family, named in every language the module ships`() {
        assertEquals(Shape.id, NxShapeProvider.axis)
        assertEquals("nx", NxShapeProvider.family)
        assertEquals(ShapeApi.VERSION, NxShapeProvider.contract)
        assertEquals(listOf<Any>(NxShapeProvider), ShapeNxModule.contributions)
        assertTrue(ShapeNxModule.strings.tables.values.all { NxShapeProvider.label in it && ShapeNxModule.name in it })
    }

    @Test
    fun `before the boot it answers what it answers once the host is up, and asks the host nothing for it`() {
        val services = Services()
        val boot = Boot()
        val atBoot = NxShapeProvider.open(Context(services, boot)).answers.value
        val live = NxShapeProvider.open(Context(services, null)).answers.value
        assertSame(nxShapeAnswers(), atBoot)
        assertSame(nxShapeAnswers(), live)
        assertEquals(emptyList(), services.asked)
        assertEquals(emptyList(), boot.asked)
    }

    @Test
    fun `nx's corners are the ones nx drew before the shape axis`() {
        val nx = nxShapeAnswers()
        assertEquals(RoundedCornerShape(8.dp), nx.control)
        assertEquals(RoundedCornerShape(12.dp), nx.card)
        assertEquals(RoundedCornerShape(50), nx.capsule)
        assertEquals(RectangleShape, nx.indicator)
        assertEquals(RoundedCornerShape(12.dp), nx.menu)
        assertEquals(RoundedCornerShape(4.dp), nx.tooltip)
        assertEquals(RoundedCornerShape(16.dp), nx.sheet)
        assertEquals(RoundedCornerShape(24.dp), nx.dialog)
        assertEquals(RoundedCornerShape(8.dp), nx.thumb)
        assertEquals(PlateOutline(12.dp, 0f, 0f), nx.plate)
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
