package hivens.look.shape.api

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import hivens.widget.model.ApiVersion
import kotlinx.coroutines.Dispatchers
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ShapeApiTest {

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.lookShapeApi")) { "the build did not pass nexira.lookShapeApi" }
        assertEquals(ShapeApi.VERSION, ApiVersion.parse(stamped))
    }

    @Test
    fun `the compiled module brings the shape axis, answered in its own type with the base answers where none answers`() {
        assertEquals(listOf<Any>(Shape), ShapeModule.contributions)
        assertEquals("shape", Shape.id)
        assertEquals(ShapeApi.VERSION, Shape.contract)
        assertEquals(ShapeAnswers::class, Shape.type)
        assertSame(ShapeAnswers.BASE, Shape.base)
        assertEquals(mapOf(ShapeApi.ID to ShapeApi.VERSION), ShapeModule.provides)
        assertEquals(mapOf(ShapeApi.ID to setOf("hivens.look.shape.api")), ShapeModule.apiPackages)
        assertTrue(ShapeModule.strings.tables.values.all { Shape.label in it }, "the axis is named in every language the module ships")
    }

    @Test
    fun `every role of the answers is listed here, a provider can fill every one, and every one is asked through Shape`() {
        val roles = publicMembers(ShapeAnswers::class.java)
        assertEquals(roles, sample(ShapeAnswers.BASE).keys, "a role this test does not list goes unchecked below")
        assertEquals(roles, fillable(), "a role a provider cannot fill can only ever hold its base answer")
        assertEquals(roles, accessors(), "every role is asked through Shape")
    }

    @Test
    fun `the base answers are square as Compose draws them, the capsule round and the thumb Compose's own`() {
        val base = ShapeAnswers.BASE
        listOf(base.control, base.card, base.indicator, base.menu, base.tooltip, base.sheet, base.dialog).forEach { role ->
            assertEquals(RoundedCornerShape(0.dp), role)
            assertEquals(Outline.Rectangle(BOX.toRect()), role.createOutline(BOX, LayoutDirection.Ltr, DENSITY), "square, no corner at all")
        }
        assertEquals(RoundedCornerShape(50), base.capsule)
        assertEquals(RoundedCornerShape(4.dp), base.thumb)
        assertEquals(PlateOutline(0.dp, 0f, 0f), base.plate)
    }

    @Test
    fun `every base answer is a corner shape, so a design system's corner slots can take it`() {
        val base = ShapeAnswers.BASE
        listOf(base.control, base.card, base.capsule, base.indicator, base.menu, base.tooltip, base.sheet, base.dialog, base.thumb)
            .forEach { assertIs<CornerBasedShape>(it) }
    }

    @Test
    fun `nothing filled is the base answers, role by role`() {
        assertEquals(sample(ShapeAnswers.BASE), sample(ShapeAnswers.build {}))
    }

    @Test
    fun `a role a provider fills is its own, in any family, and the rest keep their base`() {
        val cut = CutCornerShape(6.dp)
        val answers = ShapeAnswers.build {
            control = cut
            dialog = RoundedCornerShape(24.dp)
            plate = PlateOutline(12.dp, 0.5f, 0.1f)
        }
        assertSame(cut, answers.control)
        assertEquals(RoundedCornerShape(24.dp), answers.dialog)
        assertEquals(PlateOutline(12.dp, 0.5f, 0.1f), answers.plate)
        assertSame(ShapeAnswers.BASE.card, answers.card)
        assertSame(ShapeAnswers.BASE.capsule, answers.capsule)
        assertSame(ShapeAnswers.BASE.thumb, answers.thumb)
    }

    @Test
    fun `a plate outline is a value`() {
        assertEquals(PlateOutline(12.dp, 0f, 0f), PlateOutline(12.dp, 0f, 0f))
        assertEquals(PlateOutline(12.dp, 0f, 0f).hashCode(), PlateOutline(12.dp, 0f, 0f).hashCode())
        assertNotEquals(PlateOutline(12.dp, 0f, 0f), PlateOutline(12.dp, 0.2f, 0f))
        assertNotEquals(PlateOutline(12.dp, 0f, 0f), PlateOutline(10.dp, 0f, 0f))
        assertNotEquals(PlateOutline(12.dp, 0f, 0f), PlateOutline(12.dp, 0f, 0.3f))
    }

    @Test
    fun `outside any look a widget reads the base answers, and inside one what it provides`() {
        val own = ShapeAnswers.build { menu = RoundedCornerShape(12.dp) }
        val seen = HashMap<String, Any?>()
        composeOnce {
            seen["outside"] = Shape.menu
            seen["plate"] = Shape.plate
            CompositionLocalProvider(Shape.local provides own) {
                seen["inside"] = Shape.menu
                seen["card"] = Shape.card
            }
        }
        assertSame(ShapeAnswers.BASE.menu, seen["outside"])
        assertSame(ShapeAnswers.BASE.plate, seen["plate"])
        assertSame(own.menu, seen["inside"])
        assertSame(own.card, seen["card"])
    }

    /** The names of [type]'s public members, a getter by its property's name. */
    private fun publicMembers(type: Class<*>): Set<String> = type.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && !Modifier.isStatic(it.modifiers) && !it.isSynthetic }
        .map { method -> method.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) }
        .toSet()

    /** The roles the builder has a field for. */
    private fun fillable(): Set<String> = ShapeAnswers.Builder::class.java.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && it.name.startsWith("set") }
        .map { it.name.removePrefix("set").replaceFirstChar(Char::lowercaseChar) }
        .toSet()

    /** The roles [Shape] asks as properties. */
    private fun accessors(): Set<String> = Shape::class.java.declaredMethods
        .filter { Modifier.isPublic(it.modifiers) && it.name.startsWith("get") && it.name != "getLabel" }
        .map { it.name.removePrefix("get").replaceFirstChar(Char::lowercaseChar) }
        .toSet()

    /** Every role of [answers] by role name, as values that compare. */
    private fun sample(answers: ShapeAnswers): Map<String, Any> = mapOf(
        "control" to answers.control,
        "card" to answers.card,
        "capsule" to answers.capsule,
        "indicator" to answers.indicator,
        "menu" to answers.menu,
        "tooltip" to answers.tooltip,
        "sheet" to answers.sheet,
        "dialog" to answers.dialog,
        "thumb" to answers.thumb,
        "plate" to answers.plate,
    )

    private companion object {
        val BOX = Size(120f, 40f)
        val DENSITY = Density(2f)
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
