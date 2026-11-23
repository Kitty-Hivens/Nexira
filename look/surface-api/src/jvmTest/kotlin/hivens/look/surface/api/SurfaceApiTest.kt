package hivens.look.surface.api

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.widget.model.ApiVersion
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import java.lang.reflect.Modifier as Reflected
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SurfaceApiTest {

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.lookSurfaceApi")) { "the build did not pass nexira.lookSurfaceApi" }
        assertEquals(SurfaceApi.VERSION, ApiVersion.parse(stamped))
    }

    @Test
    fun `the compiled module brings the surface axis, answered in its own type with the base answers where none answers`() {
        assertEquals(listOf<Any>(Surface), SurfaceModule.contributions)
        assertEquals("surface", Surface.id)
        assertEquals(SurfaceApi.VERSION, Surface.contract)
        assertEquals(SurfaceAnswers::class, Surface.type)
        assertSame(SurfaceAnswers.BASE, Surface.base)
        assertEquals(mapOf(SurfaceApi.ID to SurfaceApi.VERSION), SurfaceModule.provides)
        assertEquals(mapOf(SurfaceApi.ID to setOf("hivens.look.surface.api")), SurfaceModule.apiPackages)
        assertTrue(SurfaceModule.strings.tables.values.all { Surface.label in it }, "the axis is named in every language the module ships")
    }

    @Test
    fun `every role of the answers is listed here, a provider can fill every one, and every one is asked through Surface`() {
        val roles = publicMembers(SurfaceAnswers::class.java)
        assertEquals(setOf("plane", "plate"), roles, "a role this test does not list goes unchecked below")
        assertEquals(roles, fillable(), "a role a provider cannot fill can only ever hold its base answer")
        assertEquals(roles, publicMembers(Surface::class.java) - "label", "every role is asked through Surface")
    }

    @Test
    fun `the base planes are flat in the colour of what holds them, opaque, with no edge, the page at the bottom`() {
        val holder = Plane(3, Color.Gray, Backdrop.Plane)
        SurfaceKind.entries.forEach { kind ->
            val plane = SurfaceAnswers.BASE.plane(kind, holder, floating = 5)
            val step = if (kind == SurfaceKind.Page) 0 else 3
            assertEquals(PlaneAnswer(step, step, 1f, 0f, PlaneMaterial.Flat), plane, "$kind")
        }
        assertEquals(PlateAnswer(null, 0f), SurfaceAnswers.BASE.plate)
    }

    @Test
    fun `nothing filled is the base answers, and a role a provider fills is its own`() {
        val holder = Plane(1, Color.Gray, Backdrop.Page)
        val empty = SurfaceAnswers.build {}
        assertEquals(SurfaceAnswers.BASE.plane(SurfaceKind.Card, holder, 4), empty.plane(SurfaceKind.Card, holder, 4))
        assertEquals(SurfaceAnswers.BASE.plate, empty.plate)

        val raised = PlaneAnswer(2, 2, 0.5f, 1f, PlaneMaterial.Flat)
        val own = SurfaceAnswers.build {
            plane = { kind, held, _ -> if (kind == SurfaceKind.Card) raised else SurfaceAnswers.BASE.plane(kind, held, 0) }
            plate = PlateAnswer(0.8f, 1f)
        }
        assertSame(raised, own.plane(SurfaceKind.Card, holder, 4))
        assertEquals(PlateAnswer(0.8f, 1f), own.plate)
    }

    @Test
    fun `a plane and a plate are values`() {
        assertEquals(PlaneAnswer(1, 1, 1f, 1f, PlaneMaterial.Flat), PlaneAnswer(1, 1, 1f, 1f, PlaneMaterial.Flat))
        assertEquals(PlaneAnswer(1, 1, 1f, 1f, PlaneMaterial.Flat).hashCode(), PlaneAnswer(1, 1, 1f, 1f, PlaneMaterial.Flat).hashCode())
        assertNotEquals(PlaneAnswer(1, 1, 1f, 1f, PlaneMaterial.Flat), PlaneAnswer(1, 0, 1f, 1f, PlaneMaterial.Flat))
        assertEquals(PlateAnswer(null, 0f), PlateAnswer(null, 0f))
        assertNotEquals(PlateAnswer(null, 0f), PlateAnswer(1f, 0f))
    }

    @Test
    fun `the flat material draws its body over the backdrop, and its edge is the plane's own colour`() {
        val half = Color.Blue.copy(alpha = 0.5f)
        val drawn = render {
            Canvas(Modifier.size(4.dp)) { drawPlaneBody(PlaneMaterial.Flat, half) { drawRect(Color.Red) } }
        }
        val plain = render {
            Canvas(Modifier.size(4.dp)) {
                drawRect(Color.Red)
                drawRect(half)
            }
        }
        assertTrue(drawn.contentEquals(plain), "half blue over the red backdrop")
        assertEquals(half, PlaneMaterial.Flat.edge(half))
    }

    @Test
    fun `outside any look a widget reads the base answers, and inside one what it provides`() {
        val own = SurfaceAnswers.build { plate = PlateAnswer(0.5f, 1f) }
        val seen = HashMap<String, Any?>()
        composeOnce {
            seen["outside"] = Surface.plate
            seen["plane"] = Surface.plane(SurfaceKind.Page, Plane(2, Color.Black, Backdrop.Page), 4)
            CompositionLocalProvider(Surface.local provides own) {
                seen["inside"] = Surface.plate
            }
        }
        assertSame(SurfaceAnswers.BASE.plate, seen["outside"])
        assertEquals(PlaneAnswer(0, 0, 1f, 0f, PlaneMaterial.Flat), seen["plane"])
        assertSame(own.plate, seen["inside"])
    }

    /** The names of [type]'s public members, a getter by its property's name. */
    private fun publicMembers(type: Class<*>): Set<String> = type.declaredMethods
        .filter { Reflected.isPublic(it.modifiers) && !Reflected.isStatic(it.modifiers) && !it.isSynthetic }
        .filter { it.name !in setOf("equals", "hashCode", "toString") }
        .map { method -> method.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) }
        .toSet()

    /** The roles the builder has a field for. */
    private fun fillable(): Set<String> = SurfaceAnswers.Builder::class.java.declaredMethods
        .filter { Reflected.isPublic(it.modifiers) && it.name.startsWith("set") }
        .map { it.name.removePrefix("set").replaceFirstChar(Char::lowercaseChar) }
        .toSet()
}

/** The pixels of [content], drawn off screen. */
@OptIn(ExperimentalComposeUiApi::class)
private fun render(content: @Composable () -> Unit): ByteArray {
    val scene = ImageComposeScene(4, 4, Density(1f), content = content)
    try {
        scene.render(0L).use { image -> return requireNotNull(Bitmap.makeFromImage(image).readPixels()) }
    } finally {
        scene.close()
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
