package hivens.launcher.protocol

import hivens.core.util.HashUtils

/**
 * Builds the `check=` MD5 signature SmartyCraft requires on signed actions
 * (spawn, twoauth, uploadSkin, uploadCloak, report).
 *
 * Scheme is `MD5(timeBucket | uid | login | <action-specific-fields>)` where
 * [timeBucket] is `currentTimeSec / 10` — gives ~10-second tolerance window
 * for clock drift / network latency, also serves as anti-replay (signature
 * valid only briefly).
 *
 * Pipe `|` is the field separator; fields are joined raw (no escaping).
 * Server-side validates by recomputing with the same scheme and comparing.
 *
 * Source-of-truth: smrt-deco's `aw.java` (twoauth), `ax.java` (tospawn),
 * `aj.java` (skinupload), `ae.java` (report). Wire spec in
 * `docs/dev/smartycraft-v1-protocol.md`.
 */
internal object SmartycraftSignatureBuilder {

    /**
     * Current ten-second time bucket. Exposed for testing — production code
     * computes its own. [System.currentTimeMillis] is read once per call.
     */
    fun currentTimeBucket(): Long = System.currentTimeMillis() / 1000L / 10L

    /**
     * Signature for `action=spawn`: `MD5(timeBucket | uid | login | server)`.
     */
    fun forSpawn(uid: String, login: String, server: String, timeBucket: Long = currentTimeBucket()): String =
        HashUtils.md5("$timeBucket|$uid|$login|$server")

    /**
     * Signature for `action=twoauth`: `MD5(timeBucket | uid | login | code)`.
     */
    fun forTwoAuth(uid: String, login: String, code: String, timeBucket: Long = currentTimeBucket()): String =
        HashUtils.md5("$timeBucket|$uid|$login|$code")

    /**
     * Signature for `action=skinupload` and `action=cloakupload`:
     * `MD5(timeBucket | uid | login)`. No upload-specific fields participate.
     */
    fun forUpload(uid: String, login: String, timeBucket: Long = currentTimeBucket()): String =
        HashUtils.md5("$timeBucket|$uid|$login")
}
