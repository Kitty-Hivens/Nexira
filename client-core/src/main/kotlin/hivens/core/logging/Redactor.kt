package hivens.core.logging

/**
 * Redactor for sensitive patterns in log messages. Applied at two seams:
 * the Pulse logback pipeline (via `RedactingMessageConverter` in
 * client-ui) so disk logs never store raw tokens, and the in-app
 * `ConsoleWindow` append path so screenshots / copy-paste don't leak
 * credentials when users share for support.
 *
 * Two independent layers, because neither alone is enough:
 *
 * 1. Marker patterns, permissive on the LEFT (key/marker) and
 *    conservative on the RIGHT (value) -- missing a leaked accessToken
 *    is much worse than over-redacting an unrelated value. These are the
 *    only layer that works on a log file from an EARLIER run, which the
 *    console re-reads and the diagnostic bundle copies.
 * 2. [registerSecret], which masks a known secret's exact value wherever
 *    it appears. A marker pattern only catches message shapes we thought
 *    of, and the shape that actually leaked the session token was one
 *    nobody predicted: Mojang's authlib cannot parse a SmartyCraft token
 *    as a JWT and logs `Failed to parse into SignedJWT: <token>` from
 *    inside the game process. Registering the value covers whatever
 *    wording a third party invents.
 *
 * UUID redaction covers both the 8-4-4-4-12 form and the undashed
 * 32-hex form behind a `uuid` marker. The undashed form is NOT masked
 * unanchored: a bare 32-hex run is far more often an MD5 (file hashes,
 * the launcher hash) than an identifier, and blanking those would gut
 * sync debugging.
 */
object Redactor {

    /**
     * Registered secrets, newest last. Copy-on-write behind [secretsLock]:
     * reads happen on every log line from many threads, writes once per
     * launch.
     */
    @Volatile
    private var secrets: List<String> = emptyList()
    private val secretsLock = Any()

    /**
     * Mask [value] wherever it appears from now on. Called with a live
     * access token as it is handed to the game process, so the process's
     * own output cannot leak it back through a message shape no pattern
     * anticipates.
     *
     * Values shorter than [MIN_SECRET_LENGTH] are ignored: a short or
     * placeholder secret ("0" for a blank token) occurs inside ordinary
     * log text, and masking every occurrence would corrupt the log
     * without protecting anything.
     */
    fun registerSecret(value: String) {
        if (value.length < MIN_SECRET_LENGTH) return
        synchronized(secretsLock) {
            if (value in secrets) return
            secrets = (secrets + value).takeLast(MAX_SECRETS)
        }
    }

    /** Drop every registered secret. Sign-out, and test isolation. */
    fun forgetSecrets() {
        synchronized(secretsLock) { secrets = emptyList() }
    }

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
        // The two SmartyCraft wire keys. Neither ends in "Token", so the rule above
        // never saw them, while the protocol's own model calls uid the input to
        // every signed action and session the input to the game token.
        //
        // `uid` is anchored on a long hex run so it cannot fire on prose, and the
        // word boundary keeps it off the `uuid` the rule below owns. `session` needs
        // a long token-shaped value for the same reason: the bare word opens far too
        // many ordinary log lines to redact whatever follows it.
        Regex("""(?i)(\buid["'\s:=]+)([0-9a-fA-F]{16,})""") to "$1<redacted>",
        Regex("""(?i)(\bsession["'\s:=]+)($TOKEN_CHARS{20,}=*)""") to "$1<redacted>",
        // Bearer-rule alone covers the Authorization-header leak surface.
        // A generic "Authorization: ..." rule would either eat the "Bearer"
        // marker (breaking this rule) or -- if run first -- strip "Bearer"
        // and leave the token raw.
        Regex("""(?i)(Bearer\s+)($TOKEN_CHARS{8,}=*)""") to "$1<redacted>",
        // Mojang's authlib echoes a token it could not parse as a JWT. The
        // value carries no key of its own, so this marker is the only thing
        // standing between a session token and game.log.
        Regex("""(?i)(SignedJWT[:\s]+)($TOKEN_CHARS{6,}=*)""") to "$1<redacted>",
        Regex("""\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\b""") to "<uuid>",
        // The launcher passes `--uuid` undashed, which the dashed rule above
        // does not see. Anchored to the marker so ordinary 32-hex MD5s in
        // sync logs survive.
        Regex("""(?i)(uuid["'\s:=]+)([0-9a-fA-F]{32})\b""") to "$1<uuid>",
    )

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        // Exact values first: a registered token inside `accessToken=<token>`
        // collapses to the same output the marker rule would have produced,
        // so the two layers cannot disagree.
        for (secret in secrets) {
            out = out.replace(secret, "<redacted>")
        }
        for ((re, repl) in rules) {
            out = re.replace(out, repl)
        }
        return out
    }

    /**
     * Shortest value worth registering. A 32-hex token and a JWT both clear
     * this comfortably; a placeholder or a stray word does not.
     */
    private const val MIN_SECRET_LENGTH = 12

    /** Retained registered secrets. A session touches one token per account. */
    private const val MAX_SECRETS = 8
}
