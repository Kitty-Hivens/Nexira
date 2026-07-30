package hivens.core.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackVersionsTest {

    @Test
    fun `two-digit subversion sorts after single-digit`() {
        // The breaking case for plain string sort: ".10" must sort after ".2".
        assertTrue(comparePackVersions("2026.05.22.2", "2026.05.22.10") < 0)
        assertTrue(comparePackVersions("2026.05.22.10", "2026.05.22.2") > 0)
    }

    @Test
    fun `dates order numerically`() {
        assertTrue(comparePackVersions("2026.05.22", "2026.05.23") < 0)
        assertTrue(comparePackVersions("2026.06.01", "2026.05.31") > 0)
    }

    @Test
    fun `missing trailing segment is zero`() {
        assertEquals(0, comparePackVersions("2026.05.22", "2026.05.22.0"))
        assertTrue(comparePackVersions("2026.05.22", "2026.05.22.1") < 0)
        assertEquals(0, comparePackVersions("2026.05.22.0.0", "2026.05.22"))
    }

    @Test
    fun `isNewer is strict`() {
        assertTrue(isNewerPackVersion("2026.05.23", "2026.05.22"))
        assertFalse(isNewerPackVersion("2026.05.22", "2026.05.22"))
        assertFalse(isNewerPackVersion("2026.05.22", "2026.05.23"))
    }

    @Test
    fun `non-numeric segment degrades to zero like the mirror`() {
        // Matches domain/version.rs parse-or-0; canonical date versions are
        // unaffected, a malformed one compares instead of throwing.
        assertEquals(0, comparePackVersions("x.y", "0.0"))
    }
}
