package hivens.look.icon.api

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import hivens.widget.model.ApiVersion
import kotlinx.coroutines.Dispatchers
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class IconApiTest {

    private object Star : IconDrawing() {
        @Composable
        override fun Draw(style: IconStyle) = Unit
    }

    private object Dot : IconDrawing() {
        @Composable
        override fun Draw(style: IconStyle) = Unit
    }

    /** A library that has one icon. */
    private val library = IconAnswers.build { library = { name -> Star.takeIf { name == "star" } } }

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.lookIconApi")) { "the build did not pass nexira.lookIconApi" }
        assertEquals(IconApi.VERSION, ApiVersion.parse(stamped))
    }

    @Test
    fun `the compiled module brings the icon axis, answered in its own type with the base answers when none is chosen`() {
        assertEquals(listOf<Any>(Icon), IconModule.contributions)
        assertEquals("icon", Icon.id)
        assertEquals(IconApi.VERSION, Icon.contract)
        assertEquals(IconAnswers::class, Icon.type)
        assertSame(IconAnswers.BASE, Icon.base)
        assertEquals(mapOf(IconApi.ID to IconApi.VERSION), IconModule.provides)
        assertEquals(mapOf(IconApi.ID to setOf("hivens.look.icon.api")), IconModule.apiPackages)
        assertTrue(IconModule.strings.tables.values.all { Icon.label in it }, "the axis is named in every language the module ships")
    }

    @Test
    fun `a provider fills in the library and nothing else`() {
        fun members(type: Class<*>) = type.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
            .map { it.name.substringBefore('-').removePrefix("get").removePrefix("set").replaceFirstChar(Char::lowercaseChar) }
            .toSet()
        assertEquals(setOf("drawing"), members(IconAnswers::class.java))
        assertEquals(setOf("library"), members(IconAnswers.Builder::class.java))
    }

    @Test
    fun `a name is drawn by the library, then by the component, then by the contract, then not at all`() {
        assertSame(Star, library.drawing(IconKey.named("star")))
        assertSame(Star, library.drawing(IconKey.named("star", otherwise = Dot)), "the library has it, so the component's own is not asked")
        assertSame(Dot, library.drawing(IconKey.named("rocket_launch", otherwise = Dot)))
        assertSame(Dot, library.drawing(IconKey.named("close", otherwise = Dot)), "the component's own comes before the contract's")
        assertSame(Essentials.of("close"), library.drawing(IconKey.named("close")))
        assertNull(library.drawing(IconKey.named("rocket_launch")))
    }

    @Test
    fun `an icon a component carries is drawn by it under every look`() {
        assertSame(Dot, library.drawing(IconKey.of(Dot)))
        assertSame(Dot, IconAnswers.BASE.drawing(IconKey.of(Dot)))
    }

    @Test
    fun `with no library the icons a control cannot be found without are drawn and every other is blank`() {
        listOf(Icons.Close, Icons.ArrowBack, Icons.Check, Icons.Add, Icons.MoreVert, Icons.ChevronLeft, Icons.ChevronRight, Icons.ExpandMore, Icons.ExpandLess, Icons.KeyboardArrowDown, Icons.KeyboardArrowUp, Icons.ArrowDropDown)
            .forEach { assertNotNull(IconAnswers.BASE.drawing(it), "$it") }
        assertNull(IconAnswers.BASE.drawing(Icons.Star))
        val listed = listed().values.map { it.name }.toSet()
        assertEquals(emptySet(), Essentials.names - listed, "the contract draws a name the launcher never asks for")
    }

    @Test
    fun `the launcher's icons are each one name, and the constant is that name`() {
        val listed = listed()
        assertTrue(listed.isNotEmpty())
        listed.forEach { (constant, key) ->
            assertNull(key.own, constant)
            assertEquals(constant, requireNotNull(key.name).split('_').joinToString("") { it.replaceFirstChar(Char::uppercaseChar) })
        }
    }

    @Test
    fun `an icon key is a value`() {
        assertEquals(IconKey.named("star"), IconKey.named("star"))
        assertEquals(IconKey.named("star", Dot), IconKey.named("star", Dot))
        assertNotEquals(IconKey.named("star"), IconKey.named("star", Dot))
        assertEquals(IconKey.of(Dot), IconKey.of(Dot))
        assertNotEquals(IconKey.of(Dot), IconKey.of(Star))
    }

    @Test
    fun `outside any look a widget reads the base answers, and inside one what it provides`() {
        val seen = HashMap<String, IconDrawing?>()
        composeOnce {
            seen["outside"] = Icon.drawing(Icons.Star)
            CompositionLocalProvider(Icon.local provides library) { seen["inside"] = Icon.drawing(Icons.Star) }
        }
        assertNull(seen["outside"])
        assertSame(Star, seen["inside"])
    }

    private fun listed(): Map<String, IconKey> = Icons::class.java.declaredFields
        .filter { it.type == IconKey::class.java }
        .onEach { it.isAccessible = true }
        .associate { it.name to it.get(null) as IconKey }
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
