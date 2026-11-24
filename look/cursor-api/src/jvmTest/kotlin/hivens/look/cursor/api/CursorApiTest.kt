package hivens.look.cursor.api

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.input.pointer.PointerIcon
import hivens.widget.model.ApiVersion
import kotlinx.coroutines.Dispatchers
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CursorApiTest {

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.lookCursorApi")) { "the build did not pass nexira.lookCursorApi" }
        assertEquals(CursorApi.VERSION, ApiVersion.parse(stamped))
    }

    @Test
    fun `the compiled module brings the cursor axis, answered in its own type with the base answers when none is chosen`() {
        assertEquals(listOf<Any>(Cursor), CursorModule.contributions)
        assertEquals("cursor", Cursor.id)
        assertEquals(CursorApi.VERSION, Cursor.contract)
        assertEquals(CursorAnswers::class, Cursor.type)
        assertSame(CursorAnswers.BASE, Cursor.base)
        assertEquals(mapOf(CursorApi.ID to CursorApi.VERSION), CursorModule.provides)
        assertEquals(mapOf(CursorApi.ID to setOf("hivens.look.cursor.api")), CursorModule.apiPackages)
        assertTrue(CursorModule.strings.tables.values.all { Cursor.label in it }, "the axis is named in every language the module ships")
    }

    @Test
    fun `every rule of the answers is filled by the builder and asked through Cursor`() {
        fun members(type: Class<*>) = type.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name.substringBefore('-').removePrefix("get").removePrefix("set").replaceFirstChar(Char::lowercaseChar) }
            .toSet()
        assertEquals(setOf("pointer", "resize"), members(CursorAnswers::class.java))
        assertEquals(setOf("pointer", "resize"), members(CursorAnswers.Builder::class.java))
        assertEquals(setOf("pointer", "resize", "label"), members(Cursor::class.java))
    }

    @Test
    fun `the base pointers are the arrow over a press, Compose's own over text and links, and nothing for dragging`() {
        val base = CursorAnswers.BASE
        assertSame(PointerIcon.Default, base.pointer(CursorRole.Pressable))
        assertSame(PointerIcon.Default, base.pointer(CursorRole.Disabled))
        assertSame(PointerIcon.Hand, base.pointer(CursorRole.Link))
        assertSame(PointerIcon.Text, base.pointer(CursorRole.Text))
        listOf(CursorRole.Draggable, CursorRole.Dragging, CursorRole.Refused).forEach { assertNull(base.pointer(it), "$it") }
    }

    @Test
    fun `every edge has the platform's arrow, the same one on every ask, and no two edges share one`() {
        val arrows = CursorEdge.entries.map { assertNotNull(CursorAnswers.BASE.resize(it)) }
        assertEquals(CursorEdge.entries.size, arrows.toSet().size)
        CursorEdge.entries.forEach { assertSame(CursorAnswers.BASE.resize(it), CursorAnswers.BASE.resize(it)) }
    }

    @Test
    fun `a rule left alone keeps that of the answers it is built over, and a role answered with null is left alone`() {
        val hand = CursorAnswers.build { pointer = { if (it == CursorRole.Pressable) PointerIcon.Hand else null } }
        assertSame(PointerIcon.Hand, hand.pointer(CursorRole.Pressable))
        assertNull(hand.pointer(CursorRole.Text), "answered with nothing, which is an answer")
        assertSame(CursorAnswers.BASE.resize(CursorEdge.East), hand.resize(CursorEdge.East))

        val over = CursorAnswers.build(over = hand) { resize = { PointerIcon.Crosshair } }
        assertSame(PointerIcon.Hand, over.pointer(CursorRole.Pressable))
        assertSame(PointerIcon.Crosshair, over.resize(CursorEdge.North))
    }

    @Test
    fun `outside any look a widget reads the base answers, and inside one what it provides`() {
        val own = CursorAnswers.build { pointer = { PointerIcon.Crosshair } }
        val seen = HashMap<String, PointerIcon?>()
        composeOnce {
            seen["outside"] = Cursor.pointer(CursorRole.Link)
            CompositionLocalProvider(Cursor.local provides own) { seen["inside"] = Cursor.pointer(CursorRole.Link) }
        }
        assertSame(PointerIcon.Hand, seen["outside"])
        assertSame(PointerIcon.Crosshair, seen["inside"])
    }
}

/** Composes [content] once, with nothing drawn, and disposes it. */
private fun composeOnce(content: @Composable () -> Unit) {
    val recomposer = Recomposer(Dispatchers.Unconfined)
    val composition = Composition(NothingApplier, recomposer)
    try {
        composition.setContent(content)
    } finally {
        composition.dispose()
        recomposer.cancel()
    }
}

private object NothingApplier : AbstractApplier<Unit>(Unit) {
    override fun insertTopDown(index: Int, instance: Unit) = Unit
    override fun insertBottomUp(index: Int, instance: Unit) = Unit
    override fun remove(index: Int, count: Int) = Unit
    override fun move(from: Int, to: Int, count: Int) = Unit
    override fun onClear() = Unit
}
