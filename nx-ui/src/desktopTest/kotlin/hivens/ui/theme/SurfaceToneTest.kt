package hivens.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SurfaceToneTest {

    @Test
    fun `dark body bevels lighter`() {
        val body = Color(0xFF1E1E1E)
        assertTrue(bevelHairline(body).luminance() > body.luminance())
    }

    @Test
    fun `light body bevels darker`() {
        val body = Color(0xFFEAECF2)
        assertTrue(bevelHairline(body).luminance() < body.luminance())
    }

    @Test
    fun `zero delta is identity`() {
        val body = Color(0xFF222222)
        assertEquals(body, bevelHairline(body, delta = 0f))
    }
}
