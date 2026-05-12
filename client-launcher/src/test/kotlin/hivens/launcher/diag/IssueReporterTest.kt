package hivens.launcher.diag

import hivens.config.Branding
import hivens.core.diag.ActionRing
import hivens.launcher.CrashReporter
import java.net.URLDecoder
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IssueReporterTest {

    @BeforeTest
    fun resetRing() { ActionRing.clear() }

    @AfterTest
    fun teardown() { ActionRing.clear() }

    private fun fakeCrashReport(stack: String = "java.lang.NullPointerException\n\tat foo.Bar.baz(Bar.kt:42)") =
        CrashReporter.CrashReport(
            timestamp   = "2026-05-12T10:00:00Z",
            version     = "2.2.11",
            osName      = "Linux",
            osVersion   = "7.0.3",
            osArch      = "amd64",
            jvmVersion  = "25.0.1",
            jvmVendor   = "JetBrains",
            maxMemoryMb = 4096,
            actions     = ActionRing.snapshot(),
            thread      = "main",
            stackTrace  = stack
        )

    private fun decodedBody(url: String): String {
        val bodyParam = url.substringAfter("?").split("&").first { it.startsWith("body=") }.removePrefix("body=")
        return URLDecoder.decode(bodyParam, Charsets.UTF_8)
    }

    @Test
    fun `crash issue url starts with the configured GitHub new-issue endpoint`() {
        val url = IssueReporter.crashIssueUrl(fakeCrashReport())
        assertTrue(url.startsWith(Branding.ISSUE_NEW_URL),
            "URL must point at ${Branding.ISSUE_NEW_URL}, got $url")
    }

    @Test
    fun `crash issue url carries title, body, labels query params`() {
        val url = IssueReporter.crashIssueUrl(fakeCrashReport())
        assertTrue(url.contains("title="))
        assertTrue(url.contains("body="))
        assertTrue(url.contains("labels="))
    }

    @Test
    fun `crash issue body includes version, OS, JVM, and stack trace`() {
        val body = decodedBody(IssueReporter.crashIssueUrl(fakeCrashReport()))
        assertTrue(body.contains("2.2.11"), "version expected in body")
        assertTrue(body.contains("Linux"),  "OS expected in body")
        assertTrue(body.contains("25.0.1"), "JVM version expected in body")
        assertTrue(body.contains("NullPointerException"), "stack trace head expected in body")
    }

    @Test
    fun `action ring entries flow into crash issue body`() {
        ActionRing.record("Launching: Industrial")
        ActionRing.record("Auto-sync started: 2 server(s)")

        val body = decodedBody(IssueReporter.crashIssueUrl(fakeCrashReport()))
        assertTrue(body.contains("Launching: Industrial"),    "action ring entry expected in body")
        assertTrue(body.contains("Auto-sync started"),         "second action ring entry expected in body")
    }

    @Test
    fun `crash issue body redacts sensitive values in stack trace`() {
        val report = fakeCrashReport(
            stack = "java.io.IOException: GET /auth?accessToken=AAAA1234BBBB5678 returned 500\n\tat foo.Bar.baz(Bar.kt:1)"
        )
        val body = decodedBody(IssueReporter.crashIssueUrl(report))
        assertFalse(body.contains("AAAA1234BBBB5678"),
            "raw access token must NOT appear in the issue body — Redactor must run")
        assertTrue(body.contains("<redacted>"),
            "redaction marker expected where the token was")
    }

    @Test
    fun `bundle issue url mentions the bundle path`() {
        val zip: Path = Paths.get("/tmp/aura-diagnostic-abc12345-2026-05-12.zip")
        val body = decodedBody(IssueReporter.bundleIssueUrl(zip))
        assertTrue(body.contains(zip.toString()),
            "bundle path must be referenced so the user knows what to drag-attach")
    }

    @Test
    fun `bundle issue url labels include with-bundle for triage`() {
        val zip = Paths.get("/tmp/x.zip")
        val url = IssueReporter.bundleIssueUrl(zip)
        val labelsParam = url.substringAfter("labels=")
        assertTrue(URLDecoder.decode(labelsParam, Charsets.UTF_8).contains("with-bundle"))
    }

    @Test
    fun `body truncation keeps URL under reasonable browser cap`() {
        // Synthesise a stack trace much larger than the truncation limit.
        val huge = (1..100_000).joinToString("\n") { "stackline-$it.com.example.foo.bar.Baz.method(Baz.kt:$it)" }
        val url = IssueReporter.crashIssueUrl(fakeCrashReport(stack = huge))
        // Loose ceiling: ≤ 16 KB total URL (URL-encoding ~doubles char count
        // because non-ASCII chars take 3 bytes each). Real-world Chrome /
        // Firefox limits are ≥ 32 KB, but our raw body cap is ~6 KB so even
        // worst-case encoded that's well under any browser limit.
        assertTrue(url.length < 16_000,
            "URL grew to ${url.length} chars — truncation likely failed")
    }

    @Test
    fun `crash url is deterministic for the same input`() {
        val r = fakeCrashReport()
        assertEquals(IssueReporter.crashIssueUrl(r), IssueReporter.crashIssueUrl(r))
    }
}
