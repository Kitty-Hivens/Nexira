package hivens.look.stroke.api

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.widget.model.ApiVersion
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import java.lang.reflect.Modifier as Reflected
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StrokeApiTest {

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.lookStrokeApi")) { "the build did not pass nexira.lookStrokeApi" }
        assertEquals(StrokeApi.VERSION, ApiVersion.parse(stamped))
    }

    @Test
    fun `the compiled module brings the stroke axis, answered in its own type with the base answers where none answers`() {
        assertEquals(listOf<Any>(Stroke), StrokeModule.contributions)
        assertEquals("stroke", Stroke.id)
        assertEquals(StrokeApi.VERSION, Stroke.contract)
        assertEquals(StrokeAnswers::class, Stroke.type)
        assertSame(StrokeAnswers.BASE, Stroke.base)
        assertEquals(mapOf(StrokeApi.ID to StrokeApi.VERSION), StrokeModule.provides)
        assertEquals(mapOf(StrokeApi.ID to setOf("hivens.look.stroke.api")), StrokeModule.apiPackages)
        assertTrue(StrokeModule.strings.tables.values.all { Stroke.label in it }, "the axis is named in every language the module ships")
    }

    @Test
    fun `every role of the answers is listed here, a provider can fill every one, and every one is asked through Stroke`() {
        val roles = publicMembers(StrokeAnswers::class.java)
        assertEquals(roles, sample(StrokeAnswers.BASE).keys, "a role this test does not list goes unchecked below")
        assertEquals(roles, fillable(), "a role a provider cannot fill can only ever hold its base answer")
        assertEquals(roles, accessors(), "every role is asked through Stroke")
    }

    @Test
    fun `the base answers are Compose's hairline, one device pixel with butt ends, mitred joins and no dash`() {
        sample(StrokeAnswers.BASE).values.forEach { role ->
            assertEquals(Dp.Hairline, role.width())
            val style = role.style(StrokeState.Rest, Density(2f))
            assertEquals(0f, style.width, "Compose draws a zero width stroke as one device pixel")
            assertEquals(StrokeCap.Butt, style.cap)
            assertEquals(StrokeJoin.Miter, style.join)
            assertNull(style.pathEffect)
            assertEquals(1, role.thickness(StrokeState.Rest, Density(3f)), "a layout leaves one pixel for a hairline")
        }
    }

    @Test
    fun `nothing filled is the base answers, role by role`() {
        assertEquals(sample(StrokeAnswers.BASE), sample(StrokeAnswers.build {}))
    }

    @Test
    fun `a role a provider fills is its own, and the rest keep their base`() {
        val thick = StrokeRole.solid(2.dp)
        val answers = StrokeAnswers.build {
            mark = thick
            placeholder = StrokeRole.solid(1.5.dp, dash = Dash(8.dp, 6.dp))
        }
        assertSame(thick, answers.mark)
        assertEquals(StrokeRole.solid(1.5.dp, dash = Dash(8.dp, 6.dp)), answers.placeholder)
        assertSame(StrokeAnswers.BASE.edge, answers.edge)
        assertSame(StrokeAnswers.BASE.divider, answers.divider)
    }

    @Test
    fun `a solid stroke draws in the style it was given, in pixels of the density it is drawn at`() {
        val role = StrokeRole.solid(1.5.dp, StrokeCap.Round, StrokeJoin.Bevel, miter = 2f)
        val style = role.style(StrokeState(hovered = true), Density(2f))
        assertEquals(3f, style.width)
        assertEquals(StrokeCap.Round, style.cap)
        assertEquals(StrokeJoin.Bevel, style.join)
        assertEquals(2f, style.miter)
        assertNull(style.pathEffect)
        assertEquals(1.5.dp, role.width(StrokeState(pressed = true)), "a solid stroke is the same in every state")
        assertEquals(3, role.thickness(StrokeState.Rest, Density(2f)))
        // A path effect is made by the graphics Compose draws with, which is there once a scene draws.
        var dashed: Any? = null
        render(4, 4, 2f) {
            Canvas(Modifier.size(1.dp)) { dashed = StrokeRole.solid(1.dp, dash = Dash(8.dp, 6.dp)).style(StrokeState.Rest, this).pathEffect }
        }
        assertNotNull(dashed, "a dash is drawn as a path effect")
    }

    @Test
    fun `a snapped stroke keeps to whole device pixels, never fewer than one`() {
        val hairline = StrokeRole.solid(0.5.dp, snap = true)
        assertEquals(1f, hairline.style(StrokeState.Rest, Density(1f)).width)
        assertEquals(1f, hairline.style(StrokeState.Rest, Density(1.5f)).width)
        assertEquals(2f, StrokeRole.solid(1.dp, snap = true).style(StrokeState.Rest, Density(1.5f)).width)
        assertEquals(1.5f, StrokeRole.solid(1.dp).style(StrokeState.Rest, Density(1.5f)).width, "unsnapped it is the plain conversion")
    }

    @Test
    fun `a stroke by state is the answer for the state it is asked in`() {
        val thin = StrokeRole.solid(1.dp)
        val thick = StrokeRole.solid(2.dp, StrokeCap.Round)
        val role = StrokeRole.byState { if (it.hovered || it.focused) thick else thin }
        assertEquals(1.dp, role.width())
        assertEquals(2.dp, role.width(StrokeState(hovered = true)))
        assertEquals(2.dp, role.width(StrokeState(focused = true, selected = true)))
        assertEquals(StrokeCap.Round, role.style(StrokeState(hovered = true), Density(1f)).cap)
        assertEquals(StrokeCap.Butt, role.style(StrokeState(enabled = false), Density(1f)).cap)
    }

    @Test
    fun `solid strokes and dashes are values, a state is a value and rest is all at rest`() {
        assertEquals(StrokeRole.solid(1.dp), StrokeRole.solid(1.dp))
        assertEquals(StrokeRole.solid(1.dp).hashCode(), StrokeRole.solid(1.dp).hashCode())
        assertNotEquals(StrokeRole.solid(1.dp), StrokeRole.solid(1.dp, snap = true))
        assertNotEquals(StrokeRole.solid(1.dp, dash = Dash(8.dp, 6.dp)), StrokeRole.solid(1.dp, dash = Dash(8.dp, 5.dp)))
        assertEquals(Dash(8.dp, 6.dp), Dash(8.dp, 6.dp, 0.dp))
        assertEquals(StrokeState(), StrokeState.Rest)
        assertEquals(StrokeState(hovered = true).hashCode(), StrokeState(hovered = true).hashCode())
        assertNotEquals(StrokeState(hovered = true), StrokeState(pressed = true))
        with(StrokeState.Rest) {
            assertFalse(hovered || focused || pressed || dragged || selected)
            assertTrue(enabled)
        }
    }

    @Test
    fun `outside any look a widget reads the base answers, and inside one what it provides`() {
        val own = StrokeAnswers.build { divider = StrokeRole.solid(1.dp) }
        val seen = HashMap<String, Any?>()
        composeOnce {
            seen["outside"] = Stroke.divider
            CompositionLocalProvider(Stroke.local provides own) {
                seen["inside"] = Stroke.divider
                seen["edge"] = Stroke.edge
                seen["mark"] = Stroke.mark
                seen["placeholder"] = Stroke.placeholder
            }
        }
        assertSame(StrokeAnswers.BASE.divider, seen["outside"])
        assertSame(own.divider, seen["inside"])
        assertSame(own.edge, seen["edge"])
        assertSame(own.mark, seen["mark"])
        assertSame(own.placeholder, seen["placeholder"])
    }

    @Test
    fun `an interaction source's state is what it reports, with what the widget knows of itself`() {
        val source = MutableInteractionSource()
        val seen = ArrayList<StrokeState>()
        render(8, 8, 1f, frames = 3, between = { frame ->
            if (frame == 0) {
                source.tryEmit(HoverInteraction.Enter())
                source.tryEmit(PressInteraction.Press(Offset.Zero))
            }
        }) {
            seen += source.collectStrokeState(enabled = false, selected = true)
        }
        assertEquals(StrokeState(enabled = false, selected = true), seen.first())
        assertEquals(StrokeState(hovered = true, pressed = true, enabled = false, selected = true), seen.last())
    }

    @Test
    fun `a plain edge outlines a node in exactly the pixels Compose's border does`() {
        val shape = RoundedCornerShape(6.dp)
        for (density in listOf(1f, 1.25f, 2f)) {
            for (width in listOf(Dp.Hairline, 1.dp, 1.5.dp)) {
                val border = render(40, 24, density) { Box(Modifier.size(30.dp, 18.dp).border(width, Color.Red, shape)) }
                val stroke = render(40, 24, density) { Box(Modifier.size(30.dp, 18.dp).stroke(StrokeRole.solid(width), Color.Red, shape)) }
                assertContentEquals(border, stroke, "a $width edge at density $density")
            }
        }
    }

    @Test
    fun `a line is exactly the line Compose draws with the same width and cap`() {
        for (cap in listOf(StrokeCap.Butt, StrokeCap.Round, StrokeCap.Square)) {
            val plain = render(40, 12, 1.5f) {
                Canvas(Modifier.size(30.dp, 8.dp)) { drawLine(Color.Blue, Offset(2f, 4f), Offset(size.width - 2f, 4f), 2.dp.toPx(), cap) }
            }
            val role = StrokeRole.solid(2.dp, cap)
            val stroked = render(40, 12, 1.5f) {
                Canvas(Modifier.size(30.dp, 8.dp)) { drawStroke(role, Offset(2f, 4f), Offset(size.width - 2f, 4f), SolidColor(Color.Blue)) }
            }
            assertContentEquals(plain, stroked, "a line with $cap ends")
        }
    }

    @Test
    fun `a dashed outline leaves gaps along its edge, and stays inside the node it outlines`() {
        val role = StrokeRole.solid(2.dp, dash = Dash(4.dp, 4.dp))
        val pixels = render(40, 40, 1f) {
            Box(Modifier.padding(5.dp).size(30.dp).stroke(role, Color.Red, RectangleShape))
        }
        val top = (5 until 35).map { x -> alpha(pixels, 40, x, 5) }
        assertTrue(top.any { it == 0 } && top.any { it == 255 }, "the top edge is dashed: $top")
        for (i in 0 until 40) {
            for (edge in listOf(0, 4, 35, 39)) {
                assertEquals(0, alpha(pixels, 40, i, edge), "nothing at ($i, $edge), outside the node")
                assertEquals(0, alpha(pixels, 40, edge, i), "nothing at ($edge, $i), outside the node")
            }
        }
    }

    @Test
    fun `a stroke by state outlines a node in the stroke for the state it is given`() {
        val role = StrokeRole.byState { if (it.hovered) StrokeRole.solid(3.dp) else StrokeRole.solid(1.dp) }
        val rest = render(20, 20, 1f) { Box(Modifier.size(20.dp).stroke(role, Color.Red, RectangleShape)) }
        val hovered = render(20, 20, 1f) { Box(Modifier.size(20.dp).stroke(role, Color.Red, RectangleShape, StrokeState(hovered = true))) }
        assertEquals(0, alpha(rest, 20, 10, 2))
        assertEquals(255, alpha(hovered, 20, 10, 2))
    }

    /** The names of [type]'s public members, a getter by its property's name. */
    private fun publicMembers(type: Class<*>): Set<String> = type.declaredMethods
        .filter { Reflected.isPublic(it.modifiers) && !Reflected.isStatic(it.modifiers) && !it.isSynthetic }
        .map { method -> method.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) }
        .toSet()

    /** The roles the builder has a field for. */
    private fun fillable(): Set<String> = StrokeAnswers.Builder::class.java.declaredMethods
        .filter { Reflected.isPublic(it.modifiers) && it.name.startsWith("set") }
        .map { it.name.removePrefix("set").replaceFirstChar(Char::lowercaseChar) }
        .toSet()

    /** The roles [Stroke] asks as properties. */
    private fun accessors(): Set<String> = Stroke::class.java.declaredMethods
        .filter { Reflected.isPublic(it.modifiers) && it.name.startsWith("get") && it.name != "getLabel" }
        .map { it.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) }
        .toSet()

    /** Every role of [answers] by role name. */
    private fun sample(answers: StrokeAnswers): Map<String, StrokeRole> = mapOf(
        "edge" to answers.edge,
        "mark" to answers.mark,
        "divider" to answers.divider,
        "placeholder" to answers.placeholder,
    )
}

/**
 * [content] drawn off screen at [width] by [height] pixels and [density], as its RGBA bytes once
 * [frames] frames have been drawn, with [between] run before each.
 */
@OptIn(ExperimentalComposeUiApi::class)
private fun render(
    width: Int,
    height: Int,
    density: Float,
    frames: Int = 1,
    between: (frame: Int) -> Unit = {},
    content: @Composable () -> Unit,
): ByteArray {
    val scene = ImageComposeScene(width, height, Density(density), content = content)
    try {
        var image = scene.render(0L)
        for (frame in 0 until frames) {
            image.close()
            between(frame)
            image = scene.render((frame + 1) * 16_000_000L)
        }
        image.use { return requireNotNull(Bitmap.makeFromImage(it).readPixels()) }
    } finally {
        scene.close()
    }
}

/** The alpha of the pixel at [x], [y] in [pixels], a row [width] pixels wide. */
private fun alpha(pixels: ByteArray, width: Int, x: Int, y: Int): Int = pixels[(y * width + x) * 4 + 3].toInt() and 0xFF

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
