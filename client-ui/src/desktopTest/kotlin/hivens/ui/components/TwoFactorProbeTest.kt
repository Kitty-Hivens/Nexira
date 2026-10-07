package hivens.ui.components

import hivens.core.data.SessionData
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TwoFactorProbeTest {

    @Test
    fun `a session a code unlocked does not release the gate`() {
        // What the provider hands back from its cache moments after a code.
        val unlocked = SessionData(playerName = "tester", accessToken = "t", twoFactor = true, mintedNow = true)

        assertFalse(unlocked.provesNoSecondFactor())
    }

    @Test
    fun `a sign-in the server answered without a demand does`() {
        val plain = SessionData(playerName = "tester", accessToken = "t")

        assertTrue(plain.provesNoSecondFactor())
    }
}
