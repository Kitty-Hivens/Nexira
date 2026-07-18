---
title: SmartycraftV1Protocol -- wire spec
description: Empirically captured wire spec of the SmartyCraft launcher protocol that Nexira speaks.
---

Empirically captured 2026-05-14 against live `https://www.smartycraft.ru`
using a test account + analysis of smrt-deco source.
Direct connection (no SOCKS5 proxy). All requests POST to
`/launcher2/index.php` with `Content-Type: application/x-www-form-urlencoded`.

## Common request envelope

```
POST /launcher2/index.php HTTP/1.1
Host: www.smartycraft.ru
User-Agent: SMARTYlauncher/<MIMIC_VERSION>
Content-Type: application/x-www-form-urlencoded

action=<verb>&json=<URL-encoded-JSON-payload>[&check=<MD5-signature>]
```

`check=` only present for **signed actions** (skinupload, cloakupload,
tospawn, twoauth) — see signing scheme below.

## Common response envelope

```
HTTP/2 200
Content-Type: text/html; charset=UTF-8       ← server lies, body is JSON
x-powered-by: PHP/8.5.1
[no Set-Cookie — protocol is fully stateless]

{"status":"<bf-enum-value>", ...optional fields...}
```

`status` enum (from smrt-deco `bf.java`):
`OK`, `ERROR`, `UPDATE`, `PROXY`, `LOGIN`, `PASSWORD`, `SERVER`, `ACTIVE`,
`TWOAUTH`, `VIRTUAL`, `CODE`, `UPLOAD`, `TYPE`, `SIZE`, `HD`, `INTERNAL`.

Server returns HTTP **500** if request payload is malformed (e.g. missing
required JSON field, server PHP null-derefs). HTTP **200 + 0 bytes** if
backend is broken (fatal PHP error with display_errors=Off).

## Action: `loader` — dashboard / server list

**Unsigned. Unauthenticated.** Sends version+cheksum so server can decide
whether to force launcher update.

Request payload:
```json
{
  "version": "3.6.5",
  "cheksum": "<MD5 of own launcher binary>",
  "format": "jar" | "exe" | "error",
  "testModeKey": "<from local config, usually empty>",
  "debug": "false"
}
```

Note: server field `cheksum` is misspelled (no second `c`) — preserve as-is.

Response on success:
```json
{
  "status": "OK",
  "servers": [
    {
      "name": "Industrial",
      "address": "industrial.smartycraft.ru",
      "port": 25566,
      "version": "1.12.2",
      "optionalMods": { "<modId>": {
          "name": "<display>",
          "selected": <bool>,
          "jars": ["<file.jar>", ...],
          "excludings": ["<other modId>", ...]
      }, ... }
    },
    ... (4 servers as of 2026-05-14: Industrial/RPG/Nevermine/TechnoMagic)
  ],
  "news": [...],     // not always present
  "testMode": false
}
```

Response on stale launcher: `{"status":"UPDATE"}` — client must redownload
`/downloads/smartycraft.jar`, recompute MD5, retry.

## Action: `login` — primary auth

**Unsigned.** Returns full session.

Request payload (12 fields, all required — server returns HTTP 500 if any missing):
```json
{
  "login":        "<username>",
  "password":     "<MD5(plaintext-password)>",          // no salt
  "server":       "<server name e.g. Industrial>",
  "session":      "<random 32-char hex client session id>",
  "mac":          "<XX:XX:XX:XX:XX:XX or 'virtual'>",
  "osName":       "<System.getProperty('os.name')>",
  "osBitness":    32 | 64,
  "javaVersion":  "<System.getProperty('java.version')>",
  "javaBitness":  32 | 64,
  "javaHome":     "<absolute path>",
  "classPath":    "<colon-separated classpath, ignored content>",
  "rtCheckSum":   "<MD5 of empty string is fine: d41d8cd98f00b204e9800998ecf8427e>"
}
```

Response on success:
```json
{
  "status":     "OK",
  "playername": "<canonical username>",
  "uid":        "<128-char hex — SHA-512-shaped, used for signing>",
  "uuid":       "<32-char Minecraft UUID without dashes>",
  "session":    "<Base64 — 32 bytes — AES-encrypted session token>",
  "money":      <int>,
  "hd":         <int 0|1 — HD skin permission?>,
  "clan":       null | "<clan name>",
  "cape":       null | "<cape id>",
  "skintime":   <unix-seconds — last skin upload>,
  "capetime":   null | <unix-seconds>,
  "client": {
    "directories": {
      "libraries-1.12.2": { "files": { "<jar>": {"md5": "<hex>", "size": <bytes>}, ... } },
      "bin":              { "files": {...} },
      "<server-name>":    { "directories": {...nested mods/config...} }
    },
    "files": { "<root-level-file>": {"md5": ..., "size": ...} }
  },
  "testModeKey": null | "<key>"
}
```

Error responses:
- `{"status":"PASSWORD"}` — wrong password (NOT plaintext "Bad login" — Nexira's text-search check is stale)
- `{"status":"LOGIN"}` — username not found
- `{"status":"ACTIVE"}` — account not activated
- `{"status":"VIRTUAL"}` — virtual account (??)
- `{"status":"SERVER"}` — invalid server name
- `{"status":"TWOAUTH"}` — 2FA required, follow up with action=twoauth
- `{"status":"UPDATE"}` — launcher too old (cheksum check)

## Action: `tospawn` — game session start

**Signed.** Begins authenticated game session for given server.

Request payload:
```json
{
  "login":  "<username>",
  "server": "<server name>"
}
```

Signature (`check` URL param):
```
md5( (currentUnixSeconds / 10) + "|" + uid + "|" + login + "|" + server )
```

Response: `{"status":"OK"}` or `{"status":"ERROR"}` if signature invalid.

The `(time/10)` divisor gives ~10-second tolerance window — replay
protection. uid comes from prior login response (raw 128-char hex).

## Action: `skinupload` / `cloakupload`

**Signed.** Multipart-style upload (need to inspect actual binary upload
to confirm exact form — not tested in this session, only signature shape
captured from smrt-deco aj.java).

Signature:
```
md5( (currentUnixSeconds / 10) + "|" + uid + "|" + login )
```

Response statuses include `UPLOAD`, `TYPE`, `SIZE`, `HD` for various
upload validation failures (unknown which means what — needs empirical
test if this becomes important).

## Action: `twoauth` — TOTP 2FA verification

**Signed.** Sent after login response with `status:TWOAUTH`. User typed
6-digit code from authenticator app.

Request payload:
```json
{
  "login": "<username>",
  "code":  "<6-digit code>"
}
```

Signature:
```
md5( (currentUnixSeconds / 10) + "|" + uid + "|" + login + "|" + code )
```

Response: `{"status":"OK"}` (proceed), `{"status":"CODE"}` (wrong code),
`{"status":"LOGIN"}` (session expired, restart full login).

Known caveat (per `reference_smartycraft_community.md`): server sometimes
returns `OK` for accounts with 2FA configured instead of `TWOAUTH` —
race condition / cache stale on the server-side PHP. Trust server's call.

## Action: `report` — crash report

**Signed.** Sends crash log to the upstream. Nexira doesn't currently submit
these; could opt-in for future Beacon integration. Smrt-deco class `ae`.

## Cross-cutting observations

**Stateless protocol**. No cookies set, no server-side session state
across requests. All auth carried in body. The `session` field returned
by login is the AES-encrypted continuation token; signed-action
signatures use uid + raw login + time bucket + per-action payload.

**No `Authorization` header.** All auth via form-data + signature.

**Direct connection works.** SOCKS5 proxy through proxy.smartycraft.ru:58613
is FALLBACK only (per official launcher). Direct adds 100-300ms saving.

**Wire format = old PHP-era.** Form-encoded (not JSON body), errors via
status enum (not HTTP codes), file manifest deeply nested. A modern REST
redesign (e.g. for Project Mirror) could ship a parallel V2 endpoint and
have launchers negotiate via Accept header.

## Spec gaps to investigate later

1. Exact multipart shape for skinupload/cloakupload (binary file + form
   fields + signature) — needs cooperation or a test upload to verify.
2. What `hd: 1` means in login response (HD skin permission? VIP flag?).
3. The `news` array shape inside loader response — only present sometimes.
4. AES decryption of `session` token — smrt-deco x.java has the recipe
   (`AES/ECB/PKCS5Padding`, key = first 16 chars of `MD5(uid + AUTH_SALT)`),
   but we haven't validated the round-trip yet.

## Network condition matrix (2026-05-14)

Empirical RTT for `action=loader` from German residential IP (~470ms baseline RTT to RU):

| Channel                     | RTT                  | Notes                                     |
|-----------------------------|----------------------|-------------------------------------------|
| HTTPS direct                | **426 ms**           | baseline target for 99% of users          |
| HTTP direct (no TLS)        | 398 ms               | server has plain HTTP listener on :80 too |
| HTTPS via SOCKS5+auth proxy | **928 ms (+502 ms)** | proxy works correctly, ~50% overhead      |
| HTTP via SOCKS5+auth proxy  | 489 ms               | mixed mode                                |
| HTTPS direct + HTTP/1.1     | 485 ms               | no penalty for HTTP/1.1                   |
| HTTPS direct + HTTP/2       | 487 ms               | **HTTP/2 works fine on smartycraft**      |
| HTTPS proxied + HTTP/2      | 731 ms               | proxy supports HTTP/2 too                 |

### Implications

- **Switch default channel to direct** saves ~500 ms per request (~10-20 calls per session = 5-10 s lifetime savings).
- **`FORCE_HTTP1_FOR_SMARTYCRAFT` workaround in `Network.kt` is obsolete as of 2026-05-14** — HTTP/2 negotiation works on both direct and proxied paths. Remove and rely on okhttp's ALPN default. Document removal in Conduit Phase 4 cleanup. (If issue resurfaces in future upstream server reconfig, add back with concrete reproduction case.)
- **No need for the no-SSL fallback** that smrt-deco has (`ck.q = false`) — direct HTTPS works for typical user. Skip that intermediate state in Nexira's retry chain. Just direct → proxy on IOException, two states not three.
- **Proxy is functionally fine** when our creds are correct. Initial assumption "creds rotated" was a transcription error on my side — Nexira's hardcoded `Network.Proxy.PASS = "ngyxvpFfiUz4FB2OPx1nqEa4TEKigbKc"` matches the URL-safe-Base64-decoded value from smrt-deco line 83. Don't propose rotating these unless the actual proxy stops accepting them.
- **Server validation surface is permissive**: User-Agent not checked, HTTP version not pinned, even `Host:` header doesn't have to be precise. So we don't need to obsess about mimicry headers — only the form-data shape matters.

### What ABOUT bypassing censorship for specific users

If a user is in a region where direct access is blocked but proxy works:
- IOException retry chain (Conduit Phase 2) handles it transparently
- Settings → Network → "Force proxy mode" toggle for users who know they need it day-1

If a user is in a region where BOTH direct and proxy are blocked:
- Outside of what we can reasonably solve from launcher side
- Workaround for them: VPN or system-level SOCKS proxy (their problem, not ours)
