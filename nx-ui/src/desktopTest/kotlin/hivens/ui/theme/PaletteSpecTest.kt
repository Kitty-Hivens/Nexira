package hivens.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.hct.Hct
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Properties the generated palette owes every screen, asserted across all nine
 * variants rather than pinned to colour values: a variant is free to look however
 * it likes, but a plane must stay a plane, a label on an accent must stay readable,
 * and a severity must stay distinguishable from the others.
 */
class PaletteSpecTest {

    private val seed = 0xFF3B82F6.toInt()   // a cool blue, the common wallpaper case
    private val warmSeed = 0xFFE0533A.toInt()

    private fun spec(
        variant: PaletteVariant,
        dark: Boolean = true,
        contrast: Double = 0.0,
        second: Int? = null,
        seedArgb: Int = seed,
    ) = PaletteSpec(
        seedArgb = seedArgb,
        dark = dark,
        variant = variant,
        contrastLevel = contrast,
        secondarySeedArgb = second,
    )

    private fun colors(s: PaletteSpec) = generatedNxColors(if (s.dark) DarkColorPalette else LightColorPalette, s)

    // --- the surface ladder stays a ladder ---

    // Ordered, not merely different: a sunken plane rendering lighter than a base one
    // still passes an absolute-difference check, and that is exactly what shipped.
    // The background is part of the ladder because every plane sits on it.
    @Test
    fun `the plane ladder climbs in order, in every variant and both themes`() {
        for (variant in PaletteVariant.entries) {
            for (dark in listOf(true, false)) {
                val c = colors(spec(variant, dark = dark))
                // `background` is an alias of `surface` in the colour science and is
                // deliberately not a step of its own; the planes climb away from it.
                assertEquals(c.background, c.surface, "$variant dark=$dark: background drifted off surface")
                val groundStep = lstar(c.surfaceContainerLow) - lstar(c.background)
                val clearsGround = if (dark) groundStep >= MIN_PLANE_STEP else groundStep <= -MIN_PLANE_STEP
                assertTrue(clearsGround, "$variant dark=$dark: the base plane sits $groundStep from the ground")
                // The four planes the surface levels address, from recessed to
                // floating. Only the sunken one is allowed below the ground; the rest
                // must clear it, or a card is flush with what it sits on.
                val ladder = listOf(
                    "Sunken" to c.surfaceContainerLowest,
                    "Base" to c.surfaceContainerLow,
                    "Raised" to c.surfaceContainer,
                    "Floating" to c.surfaceContainerHigh,
                )
                val ordered = if (dark) ladder else ladder.reversed()
                ordered.zipWithNext { (an, a), (bn, b) ->
                    val step = lstar(b) - lstar(a)
                    assertTrue(
                        step >= MIN_PLANE_STEP,
                        "$variant dark=$dark: $an to $bn steps $step, which is flat or backwards",
                    )
                }
            }
        }
    }

    // --- a label on an accent stays readable ---

    @Test
    fun `on-colours clear the contrast floor in every variant`() {
        for (variant in PaletteVariant.entries) {
            for (dark in listOf(true, false)) {
                val c = colors(spec(variant, dark = dark))
                assertTrue(
                    contrastRatio(c.onPrimary, c.primary) >= MIN_ON_CONTRAST,
                    "$variant dark=$dark: onPrimary/primary at ${contrastRatio(c.onPrimary, c.primary)}",
                )
                assertTrue(
                    contrastRatio(c.textPrimary, c.surface) >= MIN_ON_CONTRAST,
                    "$variant dark=$dark: textPrimary/surface at ${contrastRatio(c.textPrimary, c.surface)}",
                )
            }
        }
    }

    @Test
    fun `raising contrast never lowers it`() {
        for (variant in PaletteVariant.entries) {
            val standard = colors(spec(variant))
            val high = colors(spec(variant, contrast = 1.0))
            assertTrue(
                contrastRatio(high.textPrimary, high.surface) >= contrastRatio(standard.textPrimary, standard.surface),
                "$variant: maximum contrast came out below standard",
            )
        }
    }

    // --- severity stays a code, not decoration ---

    @Test
    fun `the four severities stay distinguishable in every variant`() {
        for (variant in PaletteVariant.entries) {
            val c = colors(spec(variant))
            val severities = mapOf(
                "success" to c.success,
                "warn" to c.warnAccent,
                "progress" to c.progressAccent,
                "critical" to c.criticalAccent,
            )
            val pairs = severities.entries.toList()
            for (i in pairs.indices) for (j in i + 1 until pairs.size) {
                val d = hueDistance(pairs[i].value, pairs[j].value)
                assertTrue(
                    d >= MIN_SEVERITY_HUE_GAP,
                    "$variant: ${pairs[i].key} and ${pairs[j].key} only $d degrees apart",
                )
            }
        }
    }

    // Monochrome is not a desaturation filter: it greys its own accents and leaves the
    // error palette alone, so severity must stay visible there -- but never louder
    // than the error it sits beside.
    @Test
    fun `monochrome greys the accent while severity tracks the error role`() {
        val c = colors(spec(PaletteVariant.Monochrome))
        assertTrue(chroma(c.primary) < 8.0, "Monochrome kept chroma ${chroma(c.primary)} on the accent")
        listOf(c.success, c.warnAccent).forEach {
            assertTrue(chroma(it) > 20.0, "a severity was greyed out to chroma ${chroma(it)}")
        }
    }

    @Test
    fun `no severity outshouts the error it sits beside, in any variant`() {
        for (variant in PaletteVariant.entries) {
            val c = colors(spec(variant))
            val errorChroma = chroma(c.criticalAccent)
            listOf("success" to c.success, "warn" to c.warnAccent, "progress" to c.progressAccent)
                .forEach { (name, colour) ->
                    assertTrue(
                        chroma(colour) <= errorChroma + 1.0,
                        "$variant: $name at chroma ${chroma(colour)} against error at $errorChroma",
                    )
                }
        }
    }

    // --- the second seed owns exactly the supporting accents ---

    @Test
    fun `a secondary seed moves secondary and tertiary and leaves the rest alone`() {
        val single = colors(spec(PaletteVariant.TonalSpot))
        val twoTone = colors(spec(PaletteVariant.TonalSpot, second = warmSeed))

        assertEquals(single.primary, twoTone.primary, "the primary seed still owns the accent")
        assertEquals(single.surface, twoTone.surface, "surfaces stay with the primary seed")
        assertEquals(single.background, twoTone.background)
        assertNotEquals(single.secondary, twoTone.secondary, "the second seed drives secondary")
        assertNotEquals(single.tertiary, twoTone.tertiary, "the second seed drives tertiary")
    }

    @Test
    fun `a secondary seed pulls the supporting accent toward its own hue`() {
        val twoTone = colors(spec(PaletteVariant.TonalSpot, second = warmSeed))
        val toSeedHue = hueDistance(twoTone.secondary, Color(warmSeed))
        val toPrimaryHue = hueDistance(twoTone.secondary, Color(seed))
        assertTrue(toSeedHue < toPrimaryHue, "secondary sat closer to the primary seed than to its own")
    }

    // --- determinism and the spec version ---

    @Test
    fun `the same spec renders the same palette`() {
        val s = spec(PaletteVariant.Expressive, contrast = 0.5, second = warmSeed)
        assertEquals(colors(s), colors(s))
    }

    @Test
    fun `the 2025 spec is the default and differs from 2021`() {
        val s = spec(PaletteVariant.Vibrant)
        assertEquals(ColorSpec.SpecVersion.SPEC_2025, s.specVersion)
        val legacy = colors(s.copy(specVersion = ColorSpec.SpecVersion.SPEC_2021))
        assertNotEquals(colors(s), legacy)
    }

    // Not all nine differ for a given seed -- Content and Fidelity are both
    // source-preserving and coincide on many inputs -- so the invariant is the
    // spread of chroma the variants advertise, not pairwise distinctness.
    @Test
    fun `the variants order themselves by how much colour they admit`() {
        fun accentChroma(v: PaletteVariant) = chroma(colors(spec(v)).primary)
        val vibrant = accentChroma(PaletteVariant.Vibrant)
        val tonalSpot = accentChroma(PaletteVariant.TonalSpot)
        val neutral = accentChroma(PaletteVariant.Neutral)
        val monochrome = accentChroma(PaletteVariant.Monochrome)
        assertTrue(vibrant > tonalSpot, "Vibrant ($vibrant) admitted no more colour than TonalSpot ($tonalSpot)")
        assertTrue(tonalSpot > neutral, "TonalSpot ($tonalSpot) admitted no more colour than Neutral ($neutral)")
        assertTrue(neutral > monochrome, "Neutral ($neutral) admitted no more colour than Monochrome ($monochrome)")
    }

    // --- brand tokens are not the engine's business ---

    @Test
    fun `brand tokens survive generation`() {
        val c = colors(spec(PaletteVariant.Vibrant))
        assertEquals(DarkColorPalette.originModrinth, c.originModrinth)
        assertEquals(DarkColorPalette.originSmartycraft, c.originSmartycraft)
        assertEquals(DarkColorPalette.decorativeRamp, c.decorativeRamp)
    }

    private companion object {
        const val MIN_PLANE_STEP = 1.0
        const val MIN_ON_CONTRAST = 4.5
        const val MIN_SEVERITY_HUE_GAP = 25.0

        fun lstar(c: Color): Float {
            val y = c.luminance().toDouble()
            val l = if (y > 0.008856) 116.0 * Math.cbrt(y) - 16.0 else 903.3 * y
            return l.toFloat()
        }

        /** WCAG relative-luminance ratio, the same measure the accessibility floor uses. */
        fun contrastRatio(a: Color, b: Color): Double {
            val la = a.luminance().toDouble() + 0.05
            val lb = b.luminance().toDouble() + 0.05
            return if (la > lb) la / lb else lb / la
        }

        fun hct(c: Color): Hct = Hct.fromInt(c.value.toLong().shr(32).toInt())
        fun chroma(c: Color): Double = hct(c).chroma
        fun hueDistance(a: Color, b: Color): Double {
            val d = abs(hct(a).hue - hct(b).hue) % 360.0
            return if (d > 180.0) 360.0 - d else d
        }
    }
}
