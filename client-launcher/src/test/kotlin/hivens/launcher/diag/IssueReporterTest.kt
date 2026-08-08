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

    // ── Prompt language ─────────────────────────────────────────────────────

    /**
     * The body is read by whoever picks the issue up, so its structure is one
     * language. Only the lines addressed to the author are translated.
     */
    @Test
    fun `the default body is English throughout`() {
        val cyrillic = Regex("[\\u0400-\\u04FF]")
        val crash = decodedBody(IssueReporter.crashIssueUrl(fakeCrashReport()))
        val bundle = decodedBody(IssueReporter.bundleIssueUrl(Paths.get("nexira-diag.zip")))

        assertFalse(cyrillic.containsMatchIn(crash), "crash body carries text no default locale asked for")
        assertFalse(cyrillic.containsMatchIn(bundle), "bundle body carries text no default locale asked for")
    }

    @Test
    fun `prompts reach the body while the structure stays put`() {
        val prompts = ReportPrompts(
            describeHeading = "Beschreibung",
            bundleHint      = "Beschreiben Sie das Problem.",
            languageNudge   = "Bitte auf Englisch.",
            bundleCreated   = $$"Paket `$bundle` liegt bereit.",
            bundleAttach    = "**ZIP hierher ziehen.**",
        )

        val body = decodedBody(IssueReporter.bundleIssueUrl(Paths.get("some-bundle.zip"), prompts))

        assertTrue(body.contains("## Beschreibung"), "the author-facing heading follows the prompts")
        assertTrue(body.contains("Beschreiben Sie das Problem. Bitte auf Englisch."), "hint and nudge land together")
        assertTrue(body.contains("Paket `some-bundle.zip` liegt bereit."), "the bundle name fills its placeholder")
        assertTrue(body.contains("## Diagnostic bundle"), "the machine-read heading stays English")
    }

    @Test
    fun `an unfilled placeholder never reaches the body`() {
        val body = decodedBody(IssueReporter.bundleIssueUrl(Paths.get("b.zip")))

        assertFalse(body.contains($$"$bundle"), "the placeholder was rendered instead of substituted")
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
            "raw access token must NOT appear in the issue body -- Redactor must run")
        assertTrue(body.contains("<redacted>"),
            "redaction marker expected where the token was")
    }

    @Test
    fun `bundle issue url references the bundle FILENAME but not the absolute path`() {
        // The absolute path leaks home directory / username when the URL is
        // visited (browser history, proxy logs, GitHub request logs). The
        // user's clipboard carries the full path locally; the URL only needs
        // the filename for the "drag the X.zip here" instruction.
        val zip: Path = Paths.get("/home/user/.local/share/nexira/nexira-diagnostic-abc12345-2026-05-12.zip")
        val body = decodedBody(IssueReporter.bundleIssueUrl(zip))

        assertTrue(body.contains("nexira-diagnostic-abc12345-2026-05-12.zip"),
            "bundle filename must appear in body so the user knows what to drag-attach")
        assertFalse(body.contains("/home/user"),
            "absolute path with home dir must NOT appear in URL body -- that would leak username/host info")
        assertFalse(body.contains(".local/share"),
            "data directory path must NOT appear in URL body")
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
        // Synthesize a stack trace much larger than the truncation limit.
        val huge = (1..100_000).joinToString("\n") { "stackline-$it.com.example.foo.bar.Baz.method(Baz.kt:$it)" }
        val url = IssueReporter.crashIssueUrl(fakeCrashReport(stack = huge))
        // Loose ceiling: ≤ 16 KB total URL (URL-encoding ~doubles char count
        // because non-ASCII chars take 3 bytes each). Real-world Chrome /
        // Firefox limits are ≥ 32 KB, but our raw body cap is ~6 KB so even
        // worst-case encoded that's well under any browser limit.
        assertTrue(url.length < 16_000,
            "URL grew to ${url.length} chars -- truncation likely failed")
    }

    @Test
    fun `crash url is deterministic for the same input`() {
        val r = fakeCrashReport()
        assertEquals(IssueReporter.crashIssueUrl(r), IssueReporter.crashIssueUrl(r))
    }

    @Test
    fun `crash issue TITLE is also redacted -- tokens in exception messages must not leak via URL parameter`() {
        // Title is built from the first stack-trace line, which can carry a
        // sensitive query param (e.g. `accessToken=` in a failed-request
        // exception). The URL ends up in browser history / proxy logs /
        // GitHub request logs whether body is scrubbed or not -- title needs
        // the Redactor pass too.
        val report = fakeCrashReport(
            stack = "java.io.IOException: GET https://example/auth?accessToken=AAAA1234SECRET returned 500"
        )
        val url = IssueReporter.crashIssueUrl(report)
        val titleParam = url.substringAfter("?").split("&").first { it.startsWith("title=") }.removePrefix("title=")
        val decodedTitle = URLDecoder.decode(titleParam, Charsets.UTF_8)

        assertFalse(decodedTitle.contains("AAAA1234SECRET"),
            "raw token must NOT appear in the URL title parameter")
        assertTrue(decodedTitle.contains("<redacted>"),
            "redaction marker expected in title where the token was")
    }
}
