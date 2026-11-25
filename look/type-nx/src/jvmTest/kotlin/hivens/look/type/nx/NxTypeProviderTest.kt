package hivens.look.type.nx

import hivens.look.manager.api.LookContext
import hivens.look.type.api.Type
import hivens.look.type.api.TypeApi
import hivens.look.type.api.TextRole
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The module's type provider answers the type axis with nx's faces, the ones nx waited before the
 * axis existed, the same before the boot as once the host is up, and asks nothing of the host for them.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NxTypeProviderTest {

    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `it answers the type axis as the nx family, named in every language the module ships`() {
        assertEquals(Type.id, NxTypeProvider.axis)
        assertEquals("nx", NxTypeProvider.family)
        assertEquals(TypeApi.VERSION, NxTypeProvider.contract)
        assertEquals(listOf<Any>(NxTypeProvider), TypeNxModule.contributions)
        assertTrue(TypeNxModule.strings.tables.values.all { NxTypeProvider.label in it && TypeNxModule.name in it })
    }

    @Test
    fun `before the boot it answers what it answers once the host is up, and asks the host nothing for it`() {
        val services = Services()
        val boot = Boot()
        val atBoot = NxTypeProvider.open(Context(services, boot)).answers.value
        val live = NxTypeProvider.open(Context(services, null)).answers.value
        assertSame(nxTypeAnswers(), atBoot)
        assertSame(nxTypeAnswers(), live)
        assertEquals(emptyList(), services.asked)
        assertEquals(emptyList(), boot.asked)
    }

    @Test
    fun `nx's faces leave the weight to the component, and only a script Roboto Flex lacks gets a face of its own`() {
        val nx = nxTypeAnswers()
        TextRole.entries.forEach {
            assertSame(nx.face(TextRole.Body), nx.face(it), "one face for every role")
            assertNull(nx.face(it).weight)
            assertNull(nx.face(it).style)
        }
        assertNull(nx.forText("Latin, Кириллица, Ελληνικά"))
        assertNotNull(nx.forText("\u65e5\u672c\u8a9e"))
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
