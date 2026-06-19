package hivens.core.logging

/**
 * Pure-function redactor for sensitive patterns in log messages.
 * Applied at two seams: the Pulse logback pipeline (via
 * `RedactingMessageConverter` in client-ui) so disk logs never store
 * raw tokens, and the in-app `ConsoleWindow` append path so
 * screenshots / copy-paste don't leak credentials when users share
 * for support.
 *
 * UUID redaction is broad on purpose: any 8-4-4-4-12 hex string is
 * masked regardless of context. Player UUIDs go in `--uuid` args,
 * accessTokens, and crash reports; occasional masking of unrelated
 * UUIDs in debug output is the worthwhile trade.
 *
 * Patterns are permissive on the LEFT (key/marker) and conservative
 * on the RIGHT (value) -- missing a leaked accessToken is much worse
 * than over-redacting an unrelated value.
 */
object Redactor {

    /**
     * RFC 6750 `b64token` charset plus the JWT separator `.`:
     *     b64token = 1*( ALPHA / DIGIT / "-" / "." / "_" / "~" / "+" / "/" ) *"="
     *
     * `=` only at the end (RFC 6750 allows trailing padding only;
     * middle `=` would not be a valid token char).
     */
    private const val TOKEN_CHARS = """[A-Za-z0-9._~+/\-]"""

    private val rules: List<Pair<Regex, String>> = listOf(
        Regex("""(?i)(accessToken["'\s:=]+)($TOKEN_CHARS{6,}=*)""") to "$1<redacted>",
        // Value stops at structural delimiters / line end, NOT whitespace: a
        // password may contain spaces, and stopping on \s leaked everything after
        // the first space. Over-redacting prose that reads "password ..." is the
        // accepted trade (see the conservative-RIGHT note above).
        Regex("""(?i)(password["'\s:=]+)([^"',&}\r\n]+)""") to "$1<redacted>",
        Regex("""(?i)((?:session|refresh|auth|api)Token["'\s:=]+)($TOKEN_CHARS{6,}=*)""") to "$1<redacted>",
        // Bearer-rule alone covers the Authorization-header leak surface.
        // A generic "Authorization: ..." rule would either eat the "Bearer"
        // marker (breaking this rule) or -- if run first -- strip "Bearer"
        // and leave the token raw.
        Regex("""(?i)(Bearer\s+)($TOKEN_CHARS{8,}=*)""") to "$1<redacted>",
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
