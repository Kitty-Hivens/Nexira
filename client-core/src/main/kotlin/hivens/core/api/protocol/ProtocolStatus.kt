package hivens.core.api.protocol

/**
 * Status enum returned by every SmartyCraft protocol response. Captured
 * empirically against live `www.smartycraft.ru` and cross-validated
 * against smrt-deco's `bf.java` decompiled enum. Server returns these
 * as JSON `{"status":"<name>"}`; names are case-sensitive on the wire.
 *
 * Not every status is reachable from every action -- e.g. [TWOAUTH]
 * only on login, [SIZE] / [TYPE] / [HD] only on uploads -- but the
 * wire schema is shared so we model them all here.
 *
 * Wire spec: `docs/dev/smartycraft-v1-protocol.md`.
 */
enum class ProtocolStatus {
    /** Operation succeeded. */
    OK,

    /** Generic server-side error. Body usually has no further detail. */
    ERROR,

    /**
     * Launcher binary hash mismatch -- server demands fresh `cheksum`.
     * Recovery: download `OFFICIAL_JAR_URL`, recompute MD5, retry once.
     * Capped to 2 retries to avoid loops on misconfigured servers.
     */
    UPDATE,

    /** Reserved historical "use proxy" hint, never seen in practice. */
    PROXY,

    /** Username does not exist (NOT wrong-password; that is [PASSWORD]). */
    LOGIN,

    /** Wrong password (login known, password mismatch). */
    PASSWORD,

    /** Unknown or invalid `server` field in request. */
    SERVER,

    /** Account exists but is not activated (email-confirm pending or banned). */
    ACTIVE,

    /**
     * Login OK but account has TOTP 2FA configured -- client must follow
     * up with `action=twoauth` carrying the user's 6-digit code.
     *
     * Known caveat: the server sometimes returns [OK] for accounts WITH
     * 2FA configured (race / cache-stale on the server-side PHP). Trust
     * the server's call -- don't fail closed if 2FA expected but [OK] seen.
     */
    TWOAUTH,

    /** Virtual account (guest / temporary tier; exact semantics unclear). */
    VIRTUAL,

    /**
     * Wrong 2FA code on `action=twoauth` follow-up. Prompt user to re-enter;
     * cap at 3 attempts before forcing a full-login restart.
     */
    CODE,

    /** Skin / cape upload failed for an unspecified upload-side reason. */
    UPLOAD,

    /** Skin / cape upload had wrong file format (server expects PNG). */
    TYPE,

    /** Skin / cape upload had wrong dimensions (not 64x32 / 64x64). */
    SIZE,

    /** Skin / cape upload requires HD permission the account doesn't have. */
    HD,

    /** Server PHP / internal error, distinct from generic [ERROR]. Rare. */
    INTERNAL;

    companion object {
        /**
         * Parses the wire string. Returns [ERROR] on unknown values --
         * preserves forward compatibility when upstream adds new status
         * codes server-side without a launcher update.
         */
        fun fromWire(value: String?): ProtocolStatus =
            value?.uppercase()?.let { name ->
                runCatching { valueOf(name) }.getOrNull()
            } ?: ERROR
    }
}
