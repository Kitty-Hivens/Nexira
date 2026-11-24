package hivens.look.icon.google.rounded

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import hivens.look.icon.api.IconDrawing
import hivens.look.icon.api.IconStyle
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * The font the icons are drawn with and the names it knows.
 *
 * The file is read once and every point on the axes of the font is a light copy of that one face
 * ([SymbolFace]). Read the way Compose reads a bundled font, the file would be loaded whole for each
 * point, and an icon that moves between an outline and a filling passes through many.
 */
internal object SymbolFont {
    private val names: Map<String, Int> by lazy {
        resource("names.tsv").reader().useLines { lines ->
            lines.associate { line -> line.substringBefore('\t') to line.substringAfter('\t').toInt(16) }
        }
    }

    private val face: SymbolFace by lazy { symbolFace(resource("material_symbols_rounded.ttf").use { it.readBytes() }) }

    private val drawings = ConcurrentHashMap<String, IconDrawing>()
    private val families = ConcurrentHashMap<SymbolAxes, FontFamily>()

    /** Reads the font and the names, where they are not read yet. */
    fun load() {
        check(names.isNotEmpty()) { "the icon names are empty" }
        check(face === face)
    }

    /** Every name an icon can be asked by. */
    fun names(): Set<String> = names.keys

    fun drawing(name: String): IconDrawing? {
        val codepoint = names[name] ?: return null
        return drawings.computeIfAbsent(name) { Symbol(name, String(Character.toChars(codepoint))) }
    }

    /** The family at [axes], the same instance for the same point. */
    fun family(axes: SymbolAxes): FontFamily = families.computeIfAbsent(axes, face::at)

    private fun resource(name: String) = requireNotNull(SymbolFont::class.java.getResourceAsStream(name)) { "$name is not in the module" }
}

/**
 * One point on the axes of the font. The filling and the optical size are kept to steps no eye tells
 * apart, so a moving icon has a bounded number of points to pass through.
 */
internal data class SymbolAxes(private val fillSteps: Int, val weight: Int, private val opticalHalves: Int) {
    val fill: Float get() = fillSteps / FILL_STEPS.toFloat()
    val opticalSize: Float get() = opticalHalves / 2f

    /** A name of this point and no other, for the platforms that keep faces by name. */
    val key: String get() = "material-symbols-rounded-$fillSteps-$weight-$opticalHalves"

    companion object {
        private const val FILL_STEPS = 32

        fun of(style: IconStyle, sizeSp: Float) = SymbolAxes(
            fillSteps = (style.fill * FILL_STEPS).roundToInt(),
            weight = style.weight,
            opticalHalves = (sizeSp.coerceIn(24f, 48f) * 2f).roundToInt(),
        )
    }
}

/** The face read from the font's bytes, and its copies at points on its axes. */
internal interface SymbolFace {
    /** The family of the face at [axes], every other axis at its default. */
    fun at(axes: SymbolAxes): FontFamily
}

/** The face the font's [bytes] hold. */
internal expect fun symbolFace(bytes: ByteArray): SymbolFace

/** The icon [name], drawn as the one character [glyph] of the font. */
private class Symbol(private val name: String, private val glyph: String) : IconDrawing() {

    @Composable
    override fun Draw(style: IconStyle) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Density's public toSp, which lint resolves through an interface it reads as
            // restricted. Not worked out from fontScale by hand: that would lose the
            // non-linear font scaling toSp applies.
            @Suppress("RestrictedApi")
            val size = with(LocalDensity.current) { minOf(maxWidth, maxHeight).toSp() }
            val family = SymbolFont.family(SymbolAxes.of(style, size.value))
            val text = remember(style.tint, size, family) {
                TextStyle(
                    color = style.tint,
                    fontSize = size,
                    fontFamily = family,
                    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
                )
            }
            // Unbounded, so a glyph whose ink reaches the edges of its em is not cut off by the
            // room: it runs over by a hair into whatever is around.
            BasicText(glyph, Modifier.wrapContentSize(unbounded = true), text)
        }
    }

    override fun toString(): String = "Material Symbols Rounded $name"
}
