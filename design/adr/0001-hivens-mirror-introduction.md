---
title: ADR 0001 -- Hivens Mirror introduction strategy
description: How the experimental smrt mirror integrates into Nexira without destabilizing the SC code path. Per-server opt-in, global kill-switch, scope limited to pack content delivery.
---

## Status

Proposed -- 2026-05-22. Pending implementation, gated on PR #216 (disabled-mods cleanup fix) merging and at least one curated pack on the mirror.

## Context

Nexira today talks exclusively to SmartyCraft (SC):
- Auth flow: SC `action=loader` for server list, SC login per-server for session token
- File manifest: post-login response with per-file md5 + size, dual-location structure (`mods/` core + `mods/{mcversion}/` optional pool)
- CDN: SC-hosted, paths derived from manifest
- Optional mods metadata: embedded in the `action=loader` response per server, separate from the file manifest

A parallel pack-delivery backend (the smrt mirror at `smrt.hivens.dev`) is now live with schema_version 2:
- `/v1/packs/:id/manifest` returns a flat `mods[] + assets[]` shape with `required: true/false` per entry
- Files come from three source tiers: `modrinth` (CDN), `smrt_cache` (mirror-hosted SHA-1-addressed jars), `smrt_static` (mirror-hosted per-pack assets)
- No auth on read endpoints
- The mirror has no equivalent for SC's auth or game-server addresses -- those remain SC-only

The question this ADR settles: how do we introduce the mirror as a working code path in Nexira without forcing all-or-nothing migration, without destabilizing the SC path, and without growing a combinatorial explosion of "what if subsystem A is SC but B is smrt" toggles.

## Decision

**One per-server radio toggle (`Source: SC | Hivens Mirror (experimental)`) plus a global Settings kill-switch.** Scope of the mirror code path is restricted to pack content delivery: manifest, file CDN, optional-mod schema, version detection. Auth, server list (and addresses), and live game-session protocol stay on SC unconditionally for the foreseeable future.

### What flips when a server is set to Mirror

| Subsystem | SC code path | smrt code path |
|---|---|---|
| Pack manifest | Per-server post-login response, recursive `{directories,files}` tree | `GET /v1/packs/:id/manifest`, flat `mods[] + assets[]` with `required: bool` |
| File CDN | SC CDN paths derived from manifest | URL inline in each `mods[].source` / `assets[].source` (`modrinth` / `smrt_cache` / `smrt_static`) |
| Optional-mod schema | `optionalMods` JSON embedded in server-list response, two-layer (server-required core vs version-subdir optional pool) | Inline `mods[].required` field, single flat layer |
| Pack version detection | `extraCheckSum` field on server entry | `pack_version` string, compared via numeric-tuple semantics |
| Pack metadata (display name, tagline, tags) | Embedded in server-list entry | `GET /v1/packs/:id` summary |

### What does NOT flip

- **Auth** -- SC API only. The mirror has no account system and we are not building Yggdrasil-equivalent in this iteration.
- **Server-list (which servers exist) and game-server addresses** -- come from SC after login. The mirror does not know game-server IPs.
- **In-game session protocol** (Minecraft handshake, MOTD, gameplay) -- not our layer regardless.

The per-server "Source: Mirror" radio therefore means strictly "this pack's files come from Hivens, login still goes through SC, and the game server itself is still the SC server you'd connect to anyway".

### Toggle semantics

- Global kill-switch in **Settings → Experimental → "Hivens Mirror (alpha)"** defaults to OFF.
- When OFF: per-server radios are ignored; everything routes to SC. This is the panic button if the mirror returns garbage or goes down.
- When ON: per-server radios are honoured. Servers default to SC; users opt-in per server.
- A server set to Mirror with global kill-switch OFF logs a warning at sync time ("mirror configured for $serverId but global mirror toggle is OFF; falling back to SC") and uses SC.

### Failure mode: no silent fallback

A server configured for Mirror that fails to fetch from `smrt.hivens.dev` (network error, 5xx, schema mismatch) **fails loudly**. We do not silently fall back to SC for the same sync run. The UI shows the failure with a clear "experimental mirror unavailable; switch this server back to SC to continue" message and a one-click revert.

Rationale: silent fallback masks real bugs in the mirror. Users in the experimental mode are explicitly opting into a less-stable path; they need to see when it fails so we can fix it.

## Why per-server, not per-subsystem or global-only

**Per-subsystem (`auth source`, `manifest source`, `cdn source` as independent toggles):** rejected. Combinatorial explosion of states. Users cannot reason about it. No production scenario actually wants the subsystems split (e.g. "manifest from SC but downloads from us" is unimplementable -- downloads need source URLs from the manifest).

**Global-only (one toggle "use mirror for everything"):** rejected. Forces all packs onto mirror simultaneously. Bug in one pack's manifest blocks every other pack. Mirror is alpha; one bad day breaks the whole launcher. Per-server granularity keeps blast radius to one pack at a time.

**Per-server + global kill-switch:** accepted. User opts in per pack. Industrial via mirror, SkyBlock still SC. If the mirror cluster catches fire, flip kill-switch off, every server reverts immediately.

## Implementation outline

The launcher's existing `IFileDownloadService` and `IManifestProcessorService` already abstract over the implementation. Add a parallel set:

```
client-core/api/interfaces/
  IFileDownloadService       (existing, SC)
  IManifestProcessorService  (existing, SC dual-tier)

client-launcher/
  FileDownloadService        (existing, SC)
  ManifestProcessorService   (existing, SC)
  smrt/
    SmrtFileDownloadService       (new, talks to smrt /v1/packs/:id/manifest + per-source URLs)
    SmrtManifestProcessorService  (new, flat mods[] + assets[] with required field)
    SmrtPackClient                (new, thin HTTP client for the spec)
```

Selection of which pair to use happens at the sync boundary, driven by `ServerProfile.source` (new field, `SC` or `Mirror`, default `SC`):

```kotlin
val (downloader, processor) = when (resolveSource(profile, settings)) {
    Source.Sc     -> scDownloader to scProcessor
    Source.Mirror -> smrtDownloader to smrtProcessor
}
```

`resolveSource` collapses the per-server radio and global kill-switch into a single answer, logging when a per-server Mirror choice is overridden by the global OFF.

The dual implementation runs the same outer contract (processSession in/out shape), so the rest of the launcher (LauncherController, AutoSyncService, UI states) is unchanged.

## Tests required before shipping

- **Parity integration test**: a known-content pack served by both a stub SC backend and a stub smrt backend should produce byte-identical on-disk state after sync, with the same set of files at the same paths. Catches both serialization mistakes and asymmetric behaviour (e.g. one path delete-on-disable, the other not).
- **Loud-failure test**: when the smrt mirror returns 5xx, Mirror-mode sync fails with a clear error and the SC fallback is NOT silently invoked.
- **Kill-switch override test**: a server marked Mirror but with global toggle OFF logs the warning and uses SC.
- **Per-server isolation test**: with Industrial set to Mirror and SkyBlock set to SC, a 5xx from the mirror during Industrial sync does NOT prevent SkyBlock from syncing in the same auto-sync pass.

## Out of scope for this ADR

- **Auth migration** -- requires its own infrastructure (account DB, Yggdrasil-equivalent, or third-party SSO) and is a separate decision.
- **Hosting game servers** -- the mirror serves pack files, not Minecraft servers. Tied to the long-term Nexira-becomes-its-own-ecosystem trajectory, which is several ADRs away.
- **Mod-loader-level changes** -- this ADR does not touch Forge handshake, FML mod-list, or anything inside the JVM the game runs in. The toggle changes only Nexira's sync layer.

## Open questions

- **Pack version equality after source flip.** A user with Industrial installed via SC who flips to Mirror will compare their installed `pack_version` (an SC `extraCheckSum`) against the mirror's `pack_version` string (numeric tuple). The two are not commensurate. First-flip behaviour likely: forced full re-sync. Worth confirming this is acceptable before shipping.
- **Local override of `mirror_base_url`.** Defaults to `https://smrt.hivens.dev`. Power-user override (env var or hidden setting) for developers running the mirror locally during smrt development. Not user-facing.
- **Telemetry on Mirror failures.** Experimental mode is most useful if failure data flows back. Need to decide whether to log to a public issue tracker, prompt the user to file an issue, or stay silent. Privacy stance forbids automatic phone-home; explicit user-triggered "send this log" is the likely shape.
