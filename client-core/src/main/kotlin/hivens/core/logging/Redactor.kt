package hivens.core.logging

/**
 * Pure-function redactor for sensitive patterns in log messages.
 *
 * Applied at two seams:
 *   - Pulse logback pipeline (via `RedactingMessageConverter` in client-ui)
 *     so disk logs never store raw tokens/passwords/UUIDs.
 *   - In-app `ConsoleWindow` append path so screenshots / copy-paste don't
 *     leak credentials when the user shares them for support.
 *
 * Trade-off: UUID redaction is broad — any 8-4-4-4-12 hex string is masked
 * regardless of context. Player UUIDs go in `--uuid` args, accessTokens,
 * and crash reports; the cost of catching them is occasional masking of
 * unrelated UUIDs in debug output. Worth the trade for a launcher whose
 * logs users routinely paste into support chats.
 *
 * Patterns are intentionally permissive on the LEFT (key/marker) and
 * conservative on the RIGHT (value) — losing one accessToken in a log to
 * a missed match is much worse than over-redacting an unrelated value.
 */
object Redactor {

    private val rules: List<Pair<Regex, String>> = listOf(
        // accessToken=<value> / accessToken: <value> / "accessToken": "<value>"
        Regex("""(?i)(accessToken["'\s:=]+)([A-Za-z0-9._\-]{6,})""") to "$1<redacted>",
        // password=<value> in any quoting style
        Regex("""(?i)(password["'\s:=]+)([^\s"',&}]{1,})""") to "$1<redacted>",
        // sessionToken / refreshToken / authToken / apiToken
        Regex("""(?i)((?:session|refresh|auth|api)Token["'\s:=]+)([A-Za-z0-9._\-]{6,})""") to "$1<redacted>",
        // Bearer <token> in Authorization headers. We deliberately don't have a
        // generic "Authorization: ..." rule because it would either eat the
        // "Bearer" marker (breaking the bearer rule that follows) or — if it
        // runs first — strip "Bearer" and leave the token raw. The bearer rule
        // alone covers the realistic Authorization-header leak surface.
        Regex("""(?i)(Bearer\s+)([A-Za-z0-9._\-]{8,})""") to "$1<redacted>",
        // 8-4-4-4-12 UUID — player UUIDs leak through almost every flow
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""") to "<uuid>",
    )

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        for ((re, repl) in rules) {
            out = re.replace(out, repl)
        }
        return out
    }
}
