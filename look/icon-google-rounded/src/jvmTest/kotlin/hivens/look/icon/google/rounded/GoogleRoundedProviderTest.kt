package hivens.look.icon.google.rounded

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.look.icon.api.Icon
import hivens.look.icon.api.IconAnswers
import hivens.look.icon.api.IconApi
import hivens.look.icon.api.IconDrawing
import hivens.look.icon.api.IconKey
import hivens.look.icon.api.IconStyle
import hivens.look.icon.api.Icons
import hivens.look.icon.api.drawIcon
import hivens.look.manager.api.LookContext
import hivens.widget.api.BootContext
import hivens.widget.api.LevelServices
import hivens.widget.api.SettingsDomain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.nio.file.Path
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The Google icon provider answers the icon axis with every icon of Material Symbols Rounded, asked by
 * the library's own names, and reads its font once whatever the icons are drawn like.
 */
@OptIn(ExperimentalComposeUiApi::class)
class GoogleRoundedProviderTest {

    @Test
    fun `it answers the icon axis as the google-rounded family, named in every language the module ships`() {
        assertEquals(Icon.id, GoogleRoundedProvider.axis)
        assertEquals("google-rounded", GoogleRoundedProvider.family)
        assertEquals(IconApi.VERSION, GoogleRoundedProvider.contract)
        assertEquals(listOf<Any>(GoogleRoundedProvider), IconGoogleRoundedModule.contributions)
        assertTrue(IconGoogleRoundedModule.strings.tables.values.all { GoogleRoundedProvider.label in it && IconGoogleRoundedModule.name in it })
    }

    @Test
    fun `before the boot it answers what it answers once the host is up, and asks the host nothing for it`() {
        val services = Services()
        val boot = Boot()
        assertSame(googleRoundedIcons(), GoogleRoundedProvider.open(Context(services, boot)).answers.value)
        assertSame(googleRoundedIcons(), GoogleRoundedProvider.open(Context(services, null)).answers.value)
        assertEquals(emptyList(), services.asked)
        assertEquals(emptyList(), boot.asked)
    }

    @Test
    fun `every icon the launcher draws is there, and so are names it never asks for, aliases among them`() {
        val launcher = Icons::class.java.declaredFields
            .filter { it.type == IconKey::class.java }
            .onEach { it.isAccessible = true }
            .map { it.get(null) as IconKey }
        assertTrue(launcher.isNotEmpty())
        launcher.forEach { assertNotNull(googleRoundedIcons().drawing(it), "$it") }
        assertTrue(SymbolFont.names().size > 4000, "the whole library is in the module, not the launcher's part")
        assertNotNull(SymbolFont.drawing("rocket_launch"))
        assertNotNull(SymbolFont.drawing("delete_outline"))
        assertNull(SymbolFont.drawing("trash"), "a name of another library is no name here")
        assertSame(SymbolFont.drawing("star"), SymbolFont.drawing("star"))
    }

    @Test
    fun `the font is one face, and a point on its axes is the same one wherever it is asked`() {
        val regular = SymbolAxes(0, 400, 48)
        assertSame(SymbolFont.ink(regular), SymbolFont.ink(SymbolAxes(0, 400, 48)))
        val points = listOf(regular, SymbolAxes(32, 400, 48), SymbolAxes(0, 500, 48), SymbolAxes(0, 400, 96))
        assertEquals(points.size, points.map(SymbolFont::ink).toSet().size)
        assertEquals(points.size, points.map { it.key }.toSet().size, "two points under one name would draw as the first of them")
    }

    @Test
    fun `an icon is drawn, in the colour asked, differently filled and differently heavy`() {
        val outline = draw(Icons.Star)
        assertTrue(outline.ink > 0, "nothing was drawn")
        assertTrue(draw(Icons.Star, fill = 1f).ink > outline.ink * 1.3, "the filling fills")
        assertTrue(draw(Icons.Star, weight = 700).ink > outline.ink * 1.1, "the weight weighs")
        assertNotEquals(outline, draw(Icons.Home), "two icons are two drawings")
        assertEquals(outline, draw(Icons.Star), "the same ask is the same drawing")
        assertTrue(draw(Icons.Star, tint = Color.Red).reddish, "in the tint")
    }

    @Test
    fun `an icon takes no more room than it is given, and grows with it`() {
        val small = draw(Icons.Star, side = 16)
        val large = draw(Icons.Star, side = 64)
        assertTrue(small.width in 8..18, "16 of room, ${small.width} of ink")
        assertTrue(large.width in 40..66, "64 of room, ${large.width} of ink")
    }

    @Test
    fun `drawn into a component's own drawing it is the same picture, where it was put`() {
        val laid = draw(Icons.Star, side = 48)
        val into = canvas { drawIcon(requireNotNull(SymbolFont.drawing("star")), Color.Black, size = Size(48f, 48f)) }
        assertEquals(laid, into)
        val moved = canvas { drawIcon(requireNotNull(SymbolFont.drawing("star")), Color.Black, topLeft = Offset(40f, 30f), size = Size(48f, 48f)) }
        assertEquals(laid.ink, moved.ink, "the same ink elsewhere")
        assertNotEquals(laid, moved)
        assertEquals(0, canvas { drawIcon(requireNotNull(SymbolFont.drawing("star")), Color.Black, size = Size.Zero) }.ink, "no room is no icon")
    }

    @Test
    fun `drawn into a component's own drawing it has the room and the style asked for, kept to what a style can be`() {
        val seen = ArrayList<Pair<Size, IconStyle>>()
        val noting = object : IconDrawing() {
            override fun DrawScope.draw(style: IconStyle) {
                seen += size to style
            }
        }
        val _ = canvas {
            drawIcon(noting, Color.Red)
            drawIcon(noting, Color.Red, topLeft = Offset(10f, 20f))
            drawIcon(noting, Color.Blue, topLeft = Offset(10f, 20f), size = Size(24f, 24f), fill = 3f, weight = 20)
        }
        assertEquals(listOf(Size(100f, 100f), Size(90f, 80f), Size(24f, 24f)), seen.map { it.first })
        assertEquals(listOf(Color.Red, Color.Red, Color.Blue), seen.map { it.second.tint })
        assertEquals(0f to 400, seen[0].second.let { it.fill to it.weight })
        assertEquals(1f to 100, seen[2].second.let { it.fill to it.weight })
    }

    private fun canvas(block: DrawScope.() -> Unit): Drawn {
        val scene = ImageComposeScene(100, 100, Density(1f)) { Canvas(Modifier.size(100.dp), block) }
        try {
            return scene.render(0L).use { Drawn.of(it) }
        } finally {
            scene.close()
        }
    }

    /** What an icon left on a transparent 100 by 100 canvas it was drawn on at [side] by [side]. */
    private fun draw(key: IconKey, side: Int = 48, tint: Color = Color.Black, fill: Float = 0f, weight: Int = 400): Drawn {
        val scene = ImageComposeScene(100, 100, Density(1f)) {
            CompositionLocalProvider(Icon.local provides ANSWERED) { Icon.Draw(key, Modifier.size(side.dp), tint, fill, weight) }
        }
        try {
            return scene.render(0L).use { Drawn.of(it) }
        } finally {
            scene.close()
        }
    }

    private data class Drawn(val ink: Int, val width: Int, val reddish: Boolean, private val pixels: List<Byte>) {
        companion object {
            fun of(image: Image): Drawn {
                val bitmap = Bitmap.makeFromImage(image)
                var ink = 0
                var left = image.width
                var right = -1
                var red = 0
                for (y in 0 until image.height) for (x in 0 until image.width) {
                    val color = bitmap.getColor(x, y)
                    val alpha = color ushr 24
                    if (alpha == 0) continue
                    ink += alpha
                    left = minOf(left, x)
                    right = maxOf(right, x)
                    if ((color shr 16 and 0xFF) > (color and 0xFF)) red++
                }
                return Drawn(ink, right - left + 1, red > 0, requireNotNull(bitmap.readPixels()).toList())
            }
        }
    }

    private class Context(override val services: LevelServices, override val boot: BootContext?) : LookContext {
        override val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
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

    private companion object {
        val ANSWERED: IconAnswers = googleRoundedIcons()
    }
}
