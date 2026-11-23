package hivens.look.type.api

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import hivens.widget.model.ApiVersion
import kotlinx.coroutines.Dispatchers
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TypeApiTest {

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.lookTypeApi")) { "the build did not pass nexira.lookTypeApi" }
        assertEquals(TypeApi.VERSION, ApiVersion.parse(stamped))
    }

    @Test
    fun `the compiled module brings the type axis, answered in its own type with the base answers when none is chosen`() {
        assertEquals(listOf<Any>(Type), TypeModule.contributions)
        assertEquals("type", Type.id)
        assertEquals(TypeApi.VERSION, Type.contract)
        assertEquals(TypeAnswers::class, Type.type)
        assertSame(TypeAnswers.BASE, Type.base)
        assertEquals(mapOf(TypeApi.ID to TypeApi.VERSION), TypeModule.provides)
        assertEquals(mapOf(TypeApi.ID to setOf("hivens.look.type.api")), TypeModule.apiPackages)
        assertTrue(TypeModule.strings.tables.values.all { Type.label in it }, "the axis is named in every language the module ships")
    }

    @Test
    fun `every role of the answers is filled by the builder and asked through Type`() {
        fun members(type: Class<*>) = type.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name.substringBefore('-').removePrefix("get").removePrefix("set").replaceFirstChar(Char::lowercaseChar) }
            .toSet()
        assertEquals(setOf("face", "mono", "forText"), members(TypeAnswers::class.java))
        assertEquals(setOf("face", "mono", "forText"), members(TypeAnswers.Builder::class.java))
        assertEquals(setOf("face", "mono", "forText", "label"), members(Type::class.java))
    }

    @Test
    fun `the base faces are Compose's own families, leaving weight and style to the component`() {
        val families = HashMap<String, FontFamily>()
        composeOnce {
            TextRole.entries.forEach { families[it.name] = TypeAnswers.BASE.face(it).family() }
            families["mono"] = TypeAnswers.BASE.mono.family()
        }
        TextRole.entries.forEach { assertEquals(FontFamily.Default, families[it.name], "$it") }
        assertEquals(FontFamily.Monospace, families["mono"])
        TextRole.entries.forEach {
            assertNull(TypeAnswers.BASE.face(it).weight)
            assertNull(TypeAnswers.BASE.face(it).style)
        }
        assertNull(TypeAnswers.BASE.forText("\u65e5\u672c\u8a9e"), "no face of its own for any script")
    }

    @Test
    fun `a face is a value, and a provider's faces are its own`() {
        assertEquals(TypeFace.of(FontFamily.Serif, FontWeight.Bold), TypeFace.of(FontFamily.Serif, FontWeight.Bold))
        assertNotEquals(TypeFace.of(FontFamily.Serif), TypeFace.of(FontFamily.Serif, style = FontStyle.Italic))
        val serif = TypeFace.of(FontFamily.Serif)
        val own = TypeAnswers.build {
            face = { if (it == TextRole.Heading) serif else TypeFace.of(FontFamily.SansSerif) }
            forText = { text -> if (text.any { it.code > 0x2E80 }) serif else null }
        }
        assertSame(serif, own.face(TextRole.Heading))
        assertEquals(TypeFace.of(FontFamily.SansSerif), own.face(TextRole.Body))
        assertSame(serif, own.forText("\u65e5"))
        assertNull(own.forText("Latin"))
        assertSame(TypeAnswers.BASE.mono, own.mono, "a role left alone keeps its base answer")
    }

    @Test
    fun `outside any look a widget reads the base answers, and inside one what it provides`() {
        val own = TypeAnswers.build { mono = TypeFace.of(FontFamily.Serif) }
        val seen = HashMap<String, Any?>()
        composeOnce {
            seen["outside"] = Type.mono
            CompositionLocalProvider(Type.local provides own) {
                seen["inside"] = Type.mono
                seen["face"] = Type.face(TextRole.Title)
            }
        }
        assertSame(TypeAnswers.BASE.mono, seen["outside"])
        assertSame(own.mono, seen["inside"])
        assertSame(own.face(TextRole.Title), seen["face"])
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
