package hivens.widget.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiVersionTest {

    @Test
    fun `a module built against an older or the same minor of the same major is served`() {
        val here = ApiVersion(1, 3)
        assertTrue(here.serves(ApiVersion(1, 0)))
        assertTrue(here.serves(ApiVersion(1, 3)))
    }

    @Test
    fun `a newer minor or another major is not served`() {
        val here = ApiVersion(1, 3)
        assertFalse(here.serves(ApiVersion(1, 4)), "a newer minor may use what this one lacks")
        assertFalse(here.serves(ApiVersion(2, 0)), "a newer major changed something")
        assertFalse(ApiVersion(2, 0).serves(ApiVersion(1, 0)), "an older major had something taken away")
    }

    @Test
    fun `a version reads back what it writes, and anything else is not a version`() {
        assertEquals(ApiVersion(1, 12), ApiVersion.parse(ApiVersion(1, 12).toString()))
        assertEquals(ApiVersion(2, 0), ApiVersion.parse(" 2.0 "))
        listOf("1", "1.0.0", "1.x", "-1.0", "", "1.-2").forEach { assertNull(ApiVersion.parse(it), "'$it' read as a version") }
    }

    @Test
    fun `a manifest list reads every entry, and one bad entry or a repeated id refuses the whole list`() {
        assertEquals(
            mapOf("nexira.packs" to ApiVersion(1, 0), "other" to ApiVersion(3, 2)),
            ApiVersion.parseAll("nexira.packs=1.0, other = 3.2"),
        )
        assertEquals(emptyMap(), ApiVersion.parseAll("  "))
        assertNull(ApiVersion.parseAll("nexira.packs=1.0, other"))
        assertNull(ApiVersion.parseAll("=1.0"))
        assertNull(ApiVersion.parseAll("a=1.0, a=1.1"))
    }
}
