package hivens.widget.model

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The persisted form must only ever grow.
 *
 * The layout is wiped once to adopt this, and it must not need wiping again, so what
 * these pin is the property that makes a second wipe unnecessary: every field carries
 * a default, so a file written before a field existed reads it as absent; unknown keys
 * are ignored, so a file written after a field was retired still loads; and nothing on
 * the wire is an enum, so renaming a shape kind is a change to a parser rather than a
 * break in the format.
 */
class SurfaceSpecTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lenient = Json { ignoreUnknownKeys = true }

    // --- format stability ---

    @Test
    fun `an empty object decodes to every default`() {
        assertEquals(SurfaceSpec(), lenient.decodeFromString<SurfaceSpec>("{}"))
    }

    @Test
    fun `a file from before a field existed still decodes`() {
        // Exactly what an older build would have written: a subset of the keys.
        val old = """{"fill":"raised","opacity":0.8}"""
        val spec = lenient.decodeFromString<SurfaceSpec>(old)
        assertEquals("raised", spec.fill)
        assertEquals(0.8f, spec.opacity)
        assertEquals(SurfaceSpec().shape, spec.shape)
        assertEquals(null, spec.blurDp)
    }

    @Test
    fun `a file carrying a retired field still decodes`() {
        val future = """{"fill":"","opacity":0.5,"frostTier":"Heavy","glassAlphaPct":45}"""
        assertEquals(0.5f, lenient.decodeFromString<SurfaceSpec>(future).opacity)
    }

    @Test
    fun `a round trip is lossless`() {
        val spec = SurfaceSpec(
            fill = "#CC1E1E1E",
            opacity = 0.42f,
            blurDp = 18f,
            shape = SurfaceShape(kind = "pill", corners = SurfaceCorners(topStart = 2f), smoothing = 0.3f),
            border = SurfaceBorder(widthDp = 1f, color = "outline", opacity = 0.5f),
            shadowDp = 4f,
            padding = SurfaceInsets(all = 8f, top = 12f),
        )
        assertEquals(spec, json.decodeFromString<SurfaceSpec>(json.encodeToString(spec)))
    }

    @Test
    fun `nothing on the wire is an enum`() {
        // An enum constant renamed upstream breaks every file that named it; a string
        // is a parser change. LenientEnumSerializer exists in this module because that
        // lesson was already paid for once.
        val encoded = json.encodeToString(SurfaceSpec(shape = SurfaceShape(kind = "pill")))
        assertTrue(""""kind":"pill"""" in encoded, encoded)
    }

    // --- the fill grammar ---

    @Test
    fun `blank fill inherits`() {
        assertEquals(FillSource.Inherit, parseFill(""))
        assertEquals(FillSource.Inherit, parseFill("   "))
    }

    @Test
    fun `a rung is recognised by name, in any case`() {
        assertEquals(FillSource.Rung("raised"), parseFill("raised"))
        assertEquals(FillSource.Rung("floating"), parseFill("Floating"))
        assertEquals(FillSource.Rung("sunken"), parseFill("  SUNKEN "))
    }

    @Test
    fun `every declared rung parses`() {
        for (rung in SURFACE_RUNGS) assertEquals(FillSource.Rung(rung), parseFill(rung))
    }

    @Test
    fun `six digits gain full alpha and eight are taken as written`() {
        assertEquals(FillSource.Literal(0xFF1E1E1E.toInt()), parseFill("#1E1E1E"))
        assertEquals(FillSource.Literal(0xCC1E1E1E.toInt()), parseFill("#CC1E1E1E"))
    }

    @Test
    fun `a typo inherits rather than resolving to black`() {
        // This parses a file a human edits by hand. A mistake has to fall back to the
        // theme's own colour; resolving to black would look like a deliberate choice.
        for (bad in listOf("#12", "#GGGGGG", "#1E1E1E1E1E", "raisd", "0x1E1E1E", "rgb(1,2,3)")) {
            assertEquals(FillSource.Inherit, parseFill(bad), "\"$bad\" should inherit")
        }
    }

    // --- baseline plus overrides ---

    @Test
    fun `an unset corner takes the baseline, and an unset baseline takes the style`() {
        assertEquals(12f, SurfaceCorners().topStart(fallback = 12f))
        assertEquals(4f, SurfaceCorners(all = 4f).bottomEnd(fallback = 12f))
        assertEquals(0f, SurfaceCorners(all = 4f, topEnd = 0f).topEnd(fallback = 12f))
    }

    @Test
    fun `zero is a decision and null is an absence`() {
        // The distinction the old chrome could not make: its corner defaulted to 0 and
        // 0 meant "do not clip", so an untouched backing drew a square nobody chose.
        assertEquals(0f, SurfaceCorners(all = 0f).topStart(fallback = 12f))
        assertEquals(12f, SurfaceCorners(all = null).topStart(fallback = 12f))
    }

    @Test
    fun `uniformity is decided after the fallbacks resolve`() {
        assertTrue(SurfaceCorners().isUniform(fallback = 12f))
        assertTrue(SurfaceCorners(all = 4f).isUniform(fallback = 12f))
        assertTrue(!SurfaceCorners(all = 4f, topEnd = 0f).isUniform(fallback = 12f))
        // The case the user asked about: rounded on one side, square on the other.
        assertTrue(!SurfaceCorners(topStart = 0f, bottomStart = 0f, topEnd = 4f, bottomEnd = 4f).isUniform(12f))
    }

    /**
     * Moving the baseline must leave the per-corner overrides alone.
     *
     * The panel's corner slider used to write a fresh record, so dragging the one
     * that means "all of them" silently discarded corners set one at a time. It
     * writes the baseline onto the existing record now, and this is the property
     * that makes that correct.
     */
    @Test
    fun `changing the baseline leaves per-corner overrides alone`() {
        val squareOnTheStartSide = SurfaceCorners(topStart = 0f, bottomStart = 0f)
        val wider = squareOnTheStartSide.copy(all = 8f)
        assertEquals(0f, wider.topStart(fallback = 12f))
        assertEquals(0f, wider.bottomStart(fallback = 12f))
        assertEquals(8f, wider.topEnd(fallback = 12f))
        assertEquals(8f, wider.bottomEnd(fallback = 12f))
    }

    @Test
    fun `insets follow the same rule as corners`() {
        assertEquals(6f, SurfaceInsets().top(fallback = 6f))
        assertEquals(8f, SurfaceInsets(all = 8f).end(fallback = 6f))
        assertEquals(0f, SurfaceInsets(all = 8f, bottom = 0f).bottom(fallback = 6f))
    }
}
