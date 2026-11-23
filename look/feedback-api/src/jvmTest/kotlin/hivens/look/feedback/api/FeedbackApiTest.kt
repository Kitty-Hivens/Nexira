package hivens.look.feedback.api

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.widget.model.ApiVersion
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import java.lang.reflect.Modifier as Reflected
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FeedbackApiTest {

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.lookFeedbackApi")) { "the build did not pass nexira.lookFeedbackApi" }
        assertEquals(FeedbackApi.VERSION, ApiVersion.parse(stamped))
    }

    @Test
    fun `the compiled module brings the feedback axis, answered in its own type with the base answers where none is chosen`() {
        assertEquals(listOf<Any>(Feedback), FeedbackModule.contributions)
        assertEquals("feedback", Feedback.id)
        assertEquals(FeedbackApi.VERSION, Feedback.contract)
        assertEquals(FeedbackAnswers::class, Feedback.type)
        assertSame(FeedbackAnswers.BASE, Feedback.base)
        assertEquals(mapOf(FeedbackApi.ID to FeedbackApi.VERSION), FeedbackModule.provides)
        assertEquals(mapOf(FeedbackApi.ID to setOf("hivens.look.feedback.api")), FeedbackModule.apiPackages)
        assertTrue(FeedbackModule.strings.tables.values.all { Feedback.label in it }, "the axis is named in every language the module ships")
    }

    @Test
    fun `every role of the answers is filled by the builder and asked through Feedback`() {
        fun members(type: Class<*>) = type.declaredMethods
            .filter { Reflected.isPublic(it.modifiers) && !Reflected.isStatic(it.modifiers) && !it.isSynthetic }
            // A parameter of an inline class mangles the name after a dash.
            .map { it.name.substringBefore('-').removePrefix("get").removePrefix("set").replaceFirstChar(Char::lowercaseChar) }
            .filter { !it.endsWith("\$default") }
            .toSet()
        assertEquals(setOf("indication", "disabledAlpha"), members(FeedbackAnswers::class.java))
        assertEquals(setOf("indication", "disabledAlpha"), members(FeedbackAnswers.Builder::class.java))
        assertEquals(setOf("indication", "disabledAlpha", "label"), members(Feedback::class.java))
    }

    @Test
    fun `the base answers are Compose's own indication for every target, and disabled content at Material's opacity`() {
        FeedbackTarget.entries.forEach { target ->
            assertSame(Neutral.INDICATION, FeedbackAnswers.BASE.indication(target, null, Color.Red))
        }
        assertEquals(0.38f, FeedbackAnswers.BASE.disabledAlpha)
        val own = FeedbackAnswers.build {
            indication = { _, _, _ -> Neutral.INDICATION }
            disabledAlpha = 0.5f
        }
        assertEquals(0.5f, own.disabledAlpha)
        assertEquals(0.38f, FeedbackAnswers.build {}.disabledAlpha)
    }

    @Test
    fun `the base indication draws black at a tenth under the pointer and three tenths under a press, over white`() {
        val source = MutableInteractionSource()
        val seen = renderSteps(source, listOf({ }, { source.tryEmit(HoverInteraction.Enter()) }, { source.tryEmit(PressInteraction.Press(Offset.Zero)) }))
        assertEquals(255, seen[0], "at rest the white shows")
        // 255 at 0.9 and at 0.7, which the blend may round either way.
        assertTrue(seen[1] in 229..230, "hovered, black at 0.1 over white: ${seen[1]}")
        assertTrue(seen[2] in 178..179, "pressed, black at 0.3 over white: ${seen[2]}")
    }

    @Test
    fun `outside any look a widget reads the base answers, and inside one what it provides`() {
        val own = FeedbackAnswers.build { disabledAlpha = 0.6f }
        val seen = HashMap<String, Any>()
        composeOnce {
            seen["outside"] = Feedback.disabledAlpha
            seen["indication"] = Feedback.indication(FeedbackTarget.Control)
            CompositionLocalProvider(Feedback.local provides own) { seen["inside"] = Feedback.disabledAlpha }
        }
        assertEquals(0.38f, seen["outside"])
        assertSame(Neutral.INDICATION, seen["indication"])
        assertEquals(0.6f, seen["inside"])
    }
}

/** The red channel of the middle pixel of a white box drawn with the base indication, after each of [steps]. */
@OptIn(ExperimentalComposeUiApi::class)
private fun renderSteps(source: MutableInteractionSource, steps: List<() -> Unit>): List<Int> {
    val indication = Neutral.INDICATION
    val scene = ImageComposeScene(8, 8, Density(1f)) {
        Box(Modifier.size(8.dp).background(Color.White).indication(source, indication))
    }
    try {
        scene.render(0L).close()
        return steps.mapIndexed { index, step ->
            step()
            scene.render((index + 1) * 16_000_000L).use { image ->
                val bytes = requireNotNull(Bitmap.makeFromImage(image).readPixels())
                val at = (4 * 8 + 4) * 4
                // The channels of a grey are equal, so the byte order does not matter.
                bytes[at].toInt() and 0xFF
            }
        }
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
