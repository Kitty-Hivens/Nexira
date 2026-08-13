package hivens.launcher.network

import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The certificate decision used to live inside the login form, which made it the
 * only door to a host that also serves the roster and the news -- neither of which
 * needs a session. What these pin is the gate being askable from anywhere without
 * turning into a dialog that stacks or nags.
 */
class CertificateTrustGateTest {

    private val host = "www.smartycraft.ru"

    @BeforeTest
    fun isolate() {
        // NetworkState is a process-wide singleton with on-disk persistence; point it
        // at a scratch file so a grant here neither reads nor writes the real one.
        NetworkState.initialize(Files.createTempDirectory("cert-gate").resolve("bypasses.json"))
    }

    @AfterTest
    fun clean() = NetworkState.revokeBypass(host)

    @Test
    fun `a refused certificate is raised for the user`() {
        val gate = CertificateTrustGate()

        gate.request(host)

        assertEquals(host, gate.pending.value?.host)
    }

    @Test
    fun `a host that is already trusted asks nothing`() {
        NetworkState.grantBypass(host, Instant.now().plus(1, ChronoUnit.HOURS))
        val gate = CertificateTrustGate()

        gate.request(host)

        assertNull(gate.pending.value, "the transport works; there is nothing to decide")
    }

    @Test
    fun `background reads do not stack a second question`() {
        // The roster, the news and the image loader all fail the same way at once.
        val gate = CertificateTrustGate()
        gate.request(host)

        gate.request(host)
        gate.request("other.example")

        assertEquals(host, gate.pending.value?.host)
    }

    @Test
    fun `accepting grants the bypass and runs what was waiting`() {
        var retried = false
        val gate = CertificateTrustGate()
        gate.request(host) { retried = true }

        gate.accept(Instant.now().plus(1, ChronoUnit.HOURS))

        assertTrue(NetworkState.bypassFor(host), "the transport may use the bypass client now")
        assertTrue(retried, "the login that provoked the question is the thing that resumes")
        assertNull(gate.pending.value)
    }

    @Test
    fun `a refusal is not asked again by a background read`() {
        val gate = CertificateTrustGate()
        gate.request(host)
        gate.dismiss()

        gate.request(host)

        assertNull(gate.pending.value, "the user answered; a retry in the background is not a new question")
    }

    @Test
    fun `an explicit attempt asks again after a refusal`() {
        // Signing in IS the user asking for that host, so the earlier "no" to a
        // background read must not silently swallow the attempt.
        val gate = CertificateTrustGate()
        gate.request(host)
        gate.dismiss()

        gate.request(host) { }

        assertEquals(host, gate.pending.value?.host)
    }

    @Test
    fun `an explicit attempt takes over a question raised in the background`() {
        val gate = CertificateTrustGate()
        gate.request(host)
        var retried = false

        gate.request(host) { retried = true }
        gate.accept(Instant.now().plus(1, ChronoUnit.HOURS))

        assertTrue(retried, "the answer has somewhere to go now, and it should go there")
    }
}
