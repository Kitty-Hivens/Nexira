package hivens.core.data

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionDataTest {

    @Test
    fun `the printed session carries none of its secrets`() {
        val session = SessionData(
            playerName = "Steve",
            uid = "9f2c4e1ab7d03c58e6f1a2b4c9d0e7f3",
            accessToken = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6",
            cachedPassword = "correct horse battery",
            refreshToken = "M.R3_BAY.refresh-token-value",
        )
        val printed = session.toString()
        assertFalse(printed.contains(session.uid))
        assertFalse(printed.contains(session.accessToken))
        assertFalse(printed.contains(session.cachedPassword!!))
        assertFalse(printed.contains(session.refreshToken!!))
        // What a log needs stays: who, and that each secret is there.
        assertTrue(printed.contains("Steve"))
        assertTrue(printed.contains("uid=<redacted:32>"))
    }
}
