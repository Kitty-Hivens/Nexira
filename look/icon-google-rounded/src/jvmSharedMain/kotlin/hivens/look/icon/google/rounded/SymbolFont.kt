package hivens.look.icon.google.rounded

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
    private val inks = ConcurrentHashMap<SymbolAxes, SymbolInk>()

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

    /** The face at [axes], the same instance for the same point. */
    fun ink(axes: SymbolAxes): SymbolInk = inks.computeIfAbsent(axes, face::at)

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
    /** The face at [axes], every other axis at its default. */
    fun at(axes: SymbolAxes): SymbolInk
}

/** The face at one point on its axes. */
internal interface SymbolInk {
    /**
     * Draws the one character [glyph] in the middle of the scope, its em the shorter side. A glyph whose
     * ink reaches the edges of its em is not cut off by the room: it runs over by a hair.
     */
    fun DrawScope.draw(glyph: String, tint: Color)
}

/** The face the font's [bytes] hold. */
internal expect fun symbolFace(bytes: ByteArray): SymbolFace

/** The icon [name], drawn as the one character [glyph] of the font. */
private class Symbol(private val name: String, private val glyph: String) : IconDrawing() {

    override fun DrawScope.draw(style: IconStyle) {
        if (size.minDimension <= 0f) return
        val ink = SymbolFont.ink(SymbolAxes.of(style, size.minDimension.toSp().value))
        with(ink) { draw(glyph, style.tint) }
    }

    override fun toString(): String = "Material Symbols Rounded $name"
}
