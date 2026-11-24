package hivens.look.cursor.nx

import androidx.compose.ui.input.pointer.PointerIcon
import hivens.look.cursor.api.Cursor
import hivens.look.cursor.api.CursorAnswers
import hivens.look.cursor.api.CursorApi
import hivens.look.manager.api.LookContext
import hivens.widget.api.BootContext
import hivens.widget.api.LevelServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** nx's pointers are the system's, which it stands on by name and knows nothing more of. */
@OptIn(ExperimentalCoroutinesApi::class)
class NxCursorProviderTest {

    private val scope = CoroutineScope(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `it answers the cursor axis as the nx family on the system family, named in every language the module ships`() {
        assertEquals(Cursor.id, NxCursorProvider.axis)
        assertEquals("nx", NxCursorProvider.family)
        assertEquals("system", NxCursorProvider.basedOn)
        assertEquals(CursorApi.VERSION, NxCursorProvider.contract)
        assertEquals(listOf<Any>(NxCursorProvider), CursorNxModule.contributions)
        assertTrue(CursorNxModule.strings.tables.values.all { NxCursorProvider.label in it && CursorNxModule.name in it })
    }

    @Test
    fun `it answers what it stands on, as that changes, and the axis's base answers where there is nothing`() {
        val system = CursorAnswers.build { pointer = { PointerIcon.Crosshair } }
        val other = CursorAnswers.build { pointer = { PointerIcon.Hand } }
        val under = MutableStateFlow<Any>(system)
        val answers = NxCursorProvider.open(Context(under)).answers
        assertSame(system, answers.value)
        under.value = other
        assertSame(other, answers.value)
        assertSame(CursorAnswers.BASE, NxCursorProvider.open(Context(null)).answers.value)
    }

    private inner class Context(override val under: StateFlow<Any>?) : LookContext {
        override val coroutineScope: CoroutineScope = scope
        override val boot: BootContext? = null
        override val services: LevelServices = object : LevelServices {
            override fun <T : Any> find(type: KClass<T>): T? = null
            override fun <T : Any> get(type: KClass<T>): T = throw NoSuchElementException(type.qualifiedName)
        }
    }
}
