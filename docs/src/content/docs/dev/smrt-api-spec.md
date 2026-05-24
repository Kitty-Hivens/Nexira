---
title: smrt -- mirror API spec (v2)
description: HTTP API spec for the smrt mirror service that delivers SmartyCraft pack manifests, server metadata, self-hosted mod jars, and curated static assets to Nexira.
---

Spec for the `smrt.hivens.dev` HTTP service. The mirror sits between Nexira and SmartyCraft: pack manifests declare every mod and every static asset (configs, resource packs, shaders, Nexira UI overrides) with an explicit source per item, decoupling pack composition from SC's archive entirely. SC's own infrastructure remains the source of truth for live game-server endpoints and authentication; the mirror does not proxy those.

## Schema versioning

Manifests carry `schema_version` at the top level. **This document describes schema_version 2.** The wire-incompatible jump from v1 is: per-mod sources moved from `sources[]` array to a single `source` object, a new `assets[]` array landed alongside `mods[]`, and a third source type `smrt_static` was added for mirror-hosted curated files.

Clients must reject manifests whose `schema_version` they do not know. Forward-compatible changes (new optional fields, new source variants) do not bump the version.

## Scope

The mirror serves:

- **Pack manifests** -- per-pack JSON declaring every mod and every static asset with explicit source per item.
- **Server metadata** -- curated descriptions, banners, MOTD overrides, Discord links, tags for SC game servers.
- **Featured / category indices** -- editorial surfaces consumed by the Nexira home screen.
- **Self-hosted mod cache** -- jar files for mods unreachable through Modrinth. Content-addressed by SHA-1.
- **Curated static assets** -- per-pack mirror-hosted files (mod configs, options.txt, resource packs we curate, shader packs, Nexira UI overrides). Path-addressed under each pack.

The mirror does **not** serve:

- Auth handshake or session tokens (SC-side concern).
- Live game-server addresses (delivered by SC after auth).
- Minecraft vanilla assets (client pulls from Mojang launcher meta directly).
- Java runtimes (Nexira's `JavaManagerService` resolves separately).
- The pack-unique SC `extra.zip` blob in opaque form (replaced by structured `assets[]`; bootstrap tooling still extracts it as a starting point for curation).

## Design principles

- **Declarative pack composition.** Each pack is a curated JSON config that names every mod and every asset, with one source per item (Modrinth, smrt_cache, or smrt_static). The mirror builds the wire manifest from that config; it does not derive pack content from SC archives at runtime.
- **Read-mostly, content-addressed where possible.** All mutable state is admin-driven; clients only GET. Mod jars in smrt_cache are addressed by SHA-1 so client-side caches dedup across packs.
- **No SC dependency at runtime.** The mirror's own ingestor takes an SC archive as a one-shot bootstrap input, but once a pack config exists the mirror serves clients without ever touching SC's CDN.
- **Forward-compatible.** Clients ignore unknown fields, unknown asset entries, and unknown source variants. The mirror can add fields without breaking existing client versions; only true wire-incompatible changes bump `schema_version`.
- **Takedown-safe.** Every cached mod has a documented takedown channel; valid requests purge the SHA-1 from cache and from any manifest that referenced it within 24 hours.

## Concepts

### Pack

A pack corresponds to one SC server-pack. Identified by a stable string slug -- the SC `assetDir` value (e.g. `Industrial`, `SkyBlock`, `Create`). Each pack has one or more **versions** over time; each version is a frozen snapshot with a generated manifest.

### Pack version

A version string of the form `YYYY.MM.DD[.N]` (no zero-padding on `N`), assigned by the admin when running `smrt-pack build` against an updated pack config. The producer must emit **canonical form**: no trailing `.0` segments. So `2026.05.22` is canonical for the first build of that day, `2026.05.22.1` for the second; the producer is forbidden from emitting `2026.05.22.0` or `2026.05.22.0.0`. The mirror's `smrt-pack build` validates this and refuses non-canonical input. Combined with the ordering rule below, canonical form guarantees that two pack_version strings are equal under tuple comparison if and only if they are byte-equal as strings, so clients can use plain string equality to answer "did the latest version change?" without re-running the comparator.

Ordering is **numeric tuple comparison with missing trailing segments treated as 0**: split on `.`, parse each segment as an integer, pad the shorter tuple with trailing zeros to match the longer one, then compare element-wise. Concretely this means `2026.05.22.10` sorts after `2026.05.22.2` (the breaking case for string sort), and a non-canonical `2026.05.22.0` (which the mirror will not produce) would compare equal to `2026.05.22`. String / lexicographic sort is unsafe (`.10` < `.2` under string sort); clients and the mirror must use this tuple comparison for ordering. Clients pin to a version when installed; updates compute the diff against the new version's manifest.

### Mod entry

One entry in a pack manifest's `mods` array. Identified by `filename` (the destination name in the client's `mods/` directory). Carries the canonical SHA-1, expected size, required/optional flag, a single `source` pointer, and an optional `display` block with human-readable metadata for the launcher UI.

### Asset entry

One entry in a pack manifest's `assets` array. Carries a destination path (`dest`) within the pack root, expected SHA-1, expected size, required/optional flag, a single `source` pointer, and an optional `display` block. Covers everything that is not a mod jar: mod configs, options.txt, resource packs, shader packs, server lists, Nexira UI overrides, anything pack-unique.

### Display block

Optional, additive-only metadata on both mod and asset entries. Clients ignore the whole block if absent and fall back to defaults derived from `filename` / `dest`. Fields:

- `name` -- human-readable label. Defaults to filename with extension stripped.
- `description` -- short text shown next to the toggle. Markdown allowed for emphasis only (no images / no script). Defaults to empty.
- `category` -- semantic group identifier. Free-form string; well-known values include `minimap`, `world-overlay`, `inventory`, `performance`, `audio`, `visual`, `client-cosmetic`, `shader`, `resource-pack`, `config`, `ui`, `render`, `tooltip`, `recipe-viewer`, `world-tools`, `chat`, `misc`, `content`, `cosmetic`, `admin-tool`, `lib`. Launchers may group entries with the same category in the UI; unknown values fall back to "Other". The `lib` category is a convention for hidden-from-UI dependencies that should always install when the depending entry is enabled.
- `incompatible_with` -- array of `filename` (for mods) or `dest` (for assets) strings. Two entries in this list are mutually exclusive; a launcher may render a `minimap` category whose members are pair-wise incompatible as a radio group rather than checkboxes.
- `license` -- SPDX identifier where known (e.g. `MIT`, `LGPL-3.0-only`, `CC-BY-NC-SA-3.0`). Lets a launcher flag non-redistributable mods to the user. Absent for proprietary mods without an SPDX-compatible declaration.
- `url` -- source / project / wiki link. Used for a "Learn more" affordance. Preferred sources in order: the mod's own `mcmod.info`/`mods.toml` URL, the Modrinth project's `source_url`, the CurseForge project page.

The block is purely advisory. It does not affect handshake, download routing, or file resolution -- only how a launcher shows the entry. Adding or removing `display` fields is forward-compatible and does not bump `schema_version`.

### Source

A pointer to where an item's file can be obtained. Three source types:

- `modrinth` -- file is fetched from Modrinth's CDN by project + version. Client uses Modrinth's URL directly; the mirror stores no bytes.
- `smrt_cache` -- mod jar is hosted by the mirror under `/v1/cache/{prefix}/{sha1}.jar`. Used when Modrinth has no matching version (typical for 1.7-1.12 era industrial mods).
- `smrt_static` -- curated asset is hosted by the mirror under `/v1/packs/{pack_id}/static/{rel_path}`. Used for mod configs, custom options.txt, mirror-curated resource packs, Nexira UI overrides.

Each item has exactly one source. If a Modrinth source becomes invalid (mod removed upstream), the admin updates the pack config to point at a new version or moves the entry to smrt_cache; the client never falls back automatically.

### Server entry

A curated description of one game server reachable through Nexira. Carries the network address the client connects to, the auth provider the server expects, plus display metadata (name, MOTD override, tagline, banner, tags, owner handle, Discord / website links). Decoupled from packs (multiple servers can run the same pack version) and from auth providers (the mirror may list servers that use different auth backends in the same response).

## Wire formats

All JSON is UTF-8. Integers are JSON numbers. SHA-1 values are 40-char lowercase hex strings. Timestamps are RFC 3339 / ISO 8601 with timezone (typically `Z` for UTC).

### Pack manifest

```
GET /v1/packs/{pack_id}/manifest             -> latest version
GET /v1/packs/{pack_id}/manifest/{version}   -> specific version
```

```json
{
  "schema_version": 2,
  "pack_id": "Industrial",
  "pack_version": "2026.05.22",
  "generated_at": "2026-05-22T13:14:00Z",
  "minecraft": {
    "version": "1.12.2"
  },
  "loader": {
    "name": "forge",
    "version": "14.23.5.2847"
  },
  "java": {
    "major": 8
  },
  "mods": [
    {
      "filename": "Quark.jar",
      "sha1": "35521fd8911b6ef893afbfe7d0d5d04962b3360d",
      "size_bytes": 3877577,
      "required": true,
      "source": {
        "type": "modrinth",
        "project_id": "qnQsVE2z",
        "version_id": "MPJKDJmI"
      }
    },
    {
      "filename": "Thaumcraft-1.7.10-4.2.3.5.jar",
      "sha1": "fed987cba654...",
      "size_bytes": 8421376,
      "required": true,
      "source": {
        "type": "smrt_cache",
        "url": "https://smrt.hivens.dev/v1/cache/fe/fed987cba654.jar"
      }
    },
    {
      "filename": "Sodium-1.12.2-fork.jar",
      "sha1": "...",
      "size_bytes": 256000,
      "required": false,
      "source": {
        "type": "modrinth",
        "project_id": "...",
        "version_id": "..."
      },
      "display": {
        "name": "Sodium (1.12 fork)",
        "description": "Render-thread rewrite for higher FPS. Conflicts with OptiFine.",
        "category": "performance",
        "incompatible_with": ["OptiFine.jar"],
        "license": "LGPL-3.0-only",
        "url": "https://github.com/CaffeineMC/sodium-fabric"
      }
    }
  ],
  "assets": [
    {
      "dest": "config/quark.cfg",
      "sha1": "111aaa...",
      "size_bytes": 8420,
      "required": true,
      "source": {
        "type": "smrt_static",
        "url": "https://smrt.hivens.dev/v1/packs/Industrial/static/configs/quark.cfg"
      }
    },
    {
      "dest": "options.txt",
      "sha1": "222bbb...",
      "size_bytes": 5120,
      "required": true,
      "source": {
        "type": "smrt_static",
        "url": "https://smrt.hivens.dev/v1/packs/Industrial/static/options/industrial-defaults.txt"
      }
    },
    {
      "dest": "resourcepacks/Faithful-32x.zip",
      "sha1": "...",
      "size_bytes": 10485760,
      "required": false,
      "source": {
        "type": "modrinth",
        "project_id": "...",
        "version_id": "..."
      }
    }
  ]
}
```

Field semantics:

- `schema_version` -- always `2` for this generation.
- `pack_id` -- matches SC `assetDir`. Stable across versions.
- `pack_version` -- assigned by the admin / build pipeline. Ordering is numeric tuple comparison, not lexicographic (see "Pack version" above).
- `generated_at` -- when the build produced this manifest.
- `minecraft.version` -- exact MC version (e.g. `1.12.2`, `1.21.1`). Used by the client to select JDK + loader.
- `loader.name` -- `forge`, `neoforge`, or `fabric`.
- `loader.version` -- loader-specific version string in the loader's native format.
- `java.major` -- major Java version required (`8`, `17`, `21`).
- `mods[].filename` -- destination filename in the client's `mods/` directory.
- `mods[].sha1` -- expected SHA-1; client verifies the downloaded file and rejects mismatches.
- `mods[].size_bytes` -- expected size for progress reporting and early-detect short reads.
- `mods[].required` -- if `false`, the client may skip the mod when the user has explicitly disabled optional mods. Defaults to `true` when omitted.
- `mods[].source` -- single source object (see below).
- `assets[].dest` -- destination path relative to the pack root in the client install (e.g. `config/X.cfg`, `resourcepacks/Y.zip`, `_nexira/background.png`). Path traversal (`..`, absolute paths) is rejected by clients.
- `assets[].sha1`, `size_bytes`, `required`, `source` -- same shape and semantics as mods.

### Source object shapes

```json
{ "type": "modrinth", "project_id": "<modrinth-project-id>", "version_id": "<modrinth-version-id>" }
```

Client resolves the actual download URL via Modrinth's `/v2/project/{project_id}/version/{version_id}` and picks the file whose `primary` flag is `true` from the `files` array (falling back to `files[0]` only if no entry is marked primary). Modrinth versions often ship multiple artifacts (sources, deobf, signatures); `files[0]` is not guaranteed to be the installable jar. SHA-1 verification against the manifest's `sha1` field catches both version-drift on Modrinth's side and accidental selection of a non-primary file.

```json
{ "type": "smrt_cache", "url": "https://smrt.hivens.dev/v1/cache/{prefix}/{sha1}.jar" }
```

Direct download from the mirror's content-addressed cache endpoint.

```json
{ "type": "smrt_static", "url": "https://smrt.hivens.dev/v1/packs/{pack_id}/static/{rel_path}" }
```

Direct download from the mirror's per-pack static asset endpoint. Used for curated configs / resource packs / Nexira UI overrides.

### Pack listing

```
GET /v1/packs
```

```json
{
  "schema_version": 2,
  "generated_at": "2026-05-22T03:15:00Z",
  "packs": [
    {
      "pack_id": "Industrial",
      "display_name": "Industrial",
      "tagline": "Production-grade tech progression on 1.12.2",
      "minecraft_version": "1.12.2",
      "latest_pack_version": "2026.05.22",
      "tags": ["tech", "industrial", "1.12.2"],
      "featured": false
    }
  ]
}
```

Pack summaries for the home-screen carousel. Full manifest fetched on demand.

### Server metadata

```
GET /v1/servers
GET /v1/servers/{server_id}
```

```json
{
  "schema_version": 2,
  "server_id": "industrial.smartycraft.ru",
  "address": "industrial.smartycraft.ru:25566",
  "pack_id": "Industrial",
  "display_name": "Industrial",
  "tagline": "Vanilla-friendly Industrial progression",
  "description_md": "# Industrial\n\nLong-form markdown description...",
  "banner_url": "https://smrt.hivens.dev/v1/packs/Industrial/static/_nexira/banner.png",
  "gallery_urls": [],
  "tags": ["tech", "survival"],
  "discord_url": "https://discord.gg/...",
  "website_url": null,
  "owner_display": "ServerAdmin",
  "motd_override": "Industrial Server\nWelcome back, %playername%",
  "founded_at": "2024-03-12",
  "featured": false,
  "auth": {
    "type": "smartycraft",
    "endpoint": "https://www.smartycraft.ru/launcher/"
  }
}
```

`motd_override` is optional; when present, the Nexira client renders it instead of querying SLP for the server's live MOTD. `banner_url` may reference a `smrt_static`-hosted asset under the relevant pack.

#### `address`

Network address the client connects to, in `host:port` form. Optional for backward compatibility; when absent, the client treats `server_id` as the host and uses the Minecraft default port 25565. New entries SHOULD always set `address` explicitly because `server_id` is an opaque identifier in principle and is not guaranteed to be a resolvable hostname.

#### `auth`

Declares the authentication provider the server expects. Optional for backward compatibility; when absent, the client defaults to `{"type": "smartycraft", "endpoint": "https://www.smartycraft.ru/launcher/"}`, matching the legacy single-provider deploy.

`type` is one of:

- `smartycraft` -- SmartyCraft's proprietary HTTP auth backend. `endpoint` required.
- `mojang` -- official Microsoft / Mojang OAuth. `endpoint` ignored.
- `elyby` -- Ely.by auth. `endpoint` may pin a specific instance; absent means `https://authserver.ely.by/`.
- `hivens` -- Hivens-side auth backend (future, not yet shipped). `endpoint` required when shipped.
- `offline` -- no auth, client picks a name locally. `endpoint` ignored. For LAN / dev / cracked targets.

Clients implement one cached session per `(type, endpoint)` pair stored in the local keyring. On a connect attempt the client looks the cached session up by the server's declared `auth` value and reuses it without prompting; a missing or expired session triggers the credential UI for that provider only. 2FA challenges are a provider-level concern: a provider that requires 2FA gates the *session refresh*, not every connect, so the user is prompted only when the cached access token has actually expired.

The spec does not constrain client-side session storage; the contract is just that the server's `auth` value is sufficient for the client to pick the right cached session or the right login flow.

### Featured / categories

```
GET /v1/featured
```

```json
{
  "schema_version": 2,
  "generated_at": "2026-05-22T03:15:00Z",
  "featured_servers": ["industrial.smartycraft.ru", "skyblock.smartycraft.ru"],
  "featured_packs": ["Industrial", "SkyBlock"]
}
```

## HTTP API

### Read endpoints (public, no auth)

| Method | Path                                     | Purpose                                                                                                                              |
|--------|------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------|
| GET    | `/v1/health`                             | Liveness probe. Returns `{"schema_version":2,"status":"ok","version":"X.Y.Z"}` (carries `schema_version` like every other response). |
| GET    | `/v1/packs`                              | List all packs with summary metadata.                                                                                                |
| GET    | `/v1/packs/{pack_id}`                    | Single pack summary.                                                                                                                 |
| GET    | `/v1/packs/{pack_id}/manifest`           | Latest manifest for the pack.                                                                                                        |
| GET    | `/v1/packs/{pack_id}/manifest/{version}` | Specific historical manifest.                                                                                                        |
| GET    | `/v1/packs/{pack_id}/manifest/versions`  | List of available manifest versions for the pack.                                                                                    |
| GET    | `/v1/packs/{pack_id}/static/{rel_path}`  | Curated static asset under the pack (mod configs, custom options, RPs, Nexira UI overrides).                                         |
| GET    | `/v1/servers`                            | List all curated SC servers.                                                                                                         |
| GET    | `/v1/servers/{server_id}`                | Single server metadata.                                                                                                              |
| GET    | `/v1/featured`                           | Editorial featured lists.                                                                                                            |
| GET    | `/v1/cache/{prefix}/{sha1}.jar`          | Self-hosted mod jar. `prefix` is the first two hex chars of `sha1`.                                                                  |
| GET    | `/v1/cache/inventory`                    | Public list of every SHA-1 currently in `smrt_cache` with size. Transparency surface.                                                |

### Admin endpoints (bearer token required)

| Method | Path                                          | Purpose                                                                                     |
|--------|-----------------------------------------------|---------------------------------------------------------------------------------------------|
| POST   | `/v1/admin/servers`                           | Create or update a server-metadata entry. Body: server JSON.                                |
| DELETE | `/v1/admin/servers/{server_id}`               | Remove a server-metadata entry.                                                             |
| PUT    | `/v1/admin/cache/{prefix}/{sha1}.jar`         | Upload a jar to `smrt_cache`. Body: jar bytes. SHA-1 is verified against the URL parameter. |
| DELETE | `/v1/admin/cache/{prefix}/{sha1}.jar`         | Remove a jar from `smrt_cache` and record the SHA-1 in `removed.txt`.                       |
| PUT    | `/v1/admin/packs/{pack_id}/static/{rel_path}` | Upload a curated static asset under the pack. Body: file bytes.                             |
| DELETE | `/v1/admin/packs/{pack_id}/static/{rel_path}` | Remove a static asset.                                                                      |
| POST   | `/v1/admin/featured`                          | Update the featured / categories surfaces.                                                  |

Admin endpoints expect `Authorization: Bearer <token>` where the token is configured at deploy time via the `SMRT_ADMIN_TOKEN` environment variable. There is no token rotation API; rotation means restarting the service with a new env value.

### Takedown

| Method | Path                  | Purpose                                                               |
|--------|-----------------------|-----------------------------------------------------------------------|
| GET    | `/v1/policy/takedown` | Takedown policy text + contact email, content-negotiated HTML / JSON. |

The mirror's takedown contact is `takedown@hivens.dev`. Valid requests are honored within 24 hours by removing the SHA-1 from `smrt_cache` and from any active pack manifest that references it. The SHA-1 is recorded in a permanent removed-list to prevent re-ingestion in the next ingestor pass.

## Mod source tiers (operator-facing)

The admin classifies each mod into one of three sources when authoring the pack config:

- **`modrinth`** (preferred, ~5-15% of typical 1.12.2 packs, 80%+ of 1.21+ packs). Mod is available on Modrinth at a specific project + version. The mirror stores no bytes; the client downloads from Modrinth's CDN.
- **`smrt_cache`** (typical for 1.7-1.12 era industrial mods). Mod is not on Modrinth or has been relabeled / patched by SC. The mirror hosts the jar at `/v1/cache/...`.
- **`smrt_static`** (configs, RPs, UI overrides). Curated file specific to the pack. Hosted under `/v1/packs/{pack_id}/static/...`.

A CurseForge tier is intentionally absent. Overwolf's `allowModDistribution=false` opt-out and ToS restrictions on third-party download services make CF unusable in 2026; mods that exist only on CF go to `smrt_cache`.

**Empirically (2026-05-22):** SC's server-side FML mod-list check is **modid-only**. The `version` field from `@Mod` is ignored, and the jar contents are not inspected. This means a Modrinth-original of the right modid is interchangeable with SC's relabeled variant -- substitution does not require version-string spoofing client-side.

## Pack version semantics

The admin assigns a new `pack_version` when any of these change:

- A mod is added, removed, or its `source` / `sha1` changes.
- An asset is added, removed, or its `source` / `sha1` changes.
- The Minecraft / loader / Java version changes.

The client compares its installed `pack_version` against the mirror's latest on launch. If different, it computes the diff and downloads only items whose `sha1` changed.

## Versioning

The `schema_version` field is present on every JSON response from every endpoint in the read-endpoint table above, including `/v1/health` -- the canonical health response is `{"schema_version":2,"status":"ok","version":"X.Y.Z"}`. This field identifies the wire-format generation. Bumping `schema_version` is reserved for backwards-incompatible changes. Adding new optional fields or new source types is forwards-compatible and does not bump the version.

Clients must:

- Refuse to process responses with a `schema_version` higher than they know.
- Ignore unknown fields silently.
- Ignore unknown `source.type` values (treat the item as unavailable; if `required: true` the install fails with a clear error).

The URL path prefix (`/v1/...`) is the **API version** and is independent of the manifest `schema_version` field. A bump from `schema_version: 2` to `schema_version: 3` keeps the same `/v1/` prefix; the URL prefix would only change on a wholesale incompatible rewrite of the HTTP surface (routes, semantics, auth model). There is no `/v0/...` or `/v2/...` path today, and the mirror serves only the current schema version on `/v1/...` -- old-schema clients must update.

## Rate limiting

Read endpoints are rate-limited per source IP at 60 requests per minute, burst 120. Cache and static endpoints (`/v1/cache/...`, `/v1/packs/{id}/static/...`) are exempt to allow parallel asset downloads. Admin endpoints have no rate limit (single trusted caller).

Rate-limit responses are HTTP 429 with `Retry-After` header.

## Error responses

```json
{
  "schema_version": 2,
  "error": {
    "code": "manifest_not_found",
    "message": "Pack 'XYZ' has no manifest at version '2024.01.01'.",
    "details": {}
  }
}
```

Standard error codes: `not_found`, `pack_not_found`, `manifest_not_found`, `server_not_found`, `cache_miss`, `static_not_found`, `unauthorized`, `rate_limited`, `bad_request`, `internal_error`.

## Storage layout (informational)

```
/var/lib/smrt/
- packs/
  - {pack_id}/
    - summary.json
    - manifests/
      - 2026.05.22.json
      - 2026.05.23.json
      - latest -> 2026.05.23.json
    - static/
      - configs/
        - quark.cfg
      - options/
        - industrial-defaults.txt
      - resourcepacks/
        - (rarely; usually Modrinth-sourced)
      - _nexira/
        - banner.png
        - background.png
- servers/
  - {server_id}.json
- cache/
  - ab/
    - abc123def456...jar
- featured.json
- removed.txt
```

`extras/` from v1 is gone; pack-unique files now live structured under `static/` with explicit `assets[]` manifest entries.

## Authoring workflow

Admin uses the `smrt-pack` CLI (separate binary in the same workspace):

```
smrt-pack bootstrap --sc-archive Industrial.zip --out industrial-starter.json
smrt-pack validate --config industrial.json --against-sc-archive Industrial.zip
smrt-pack build    --config industrial.json --storage /var/lib/smrt
```

- `bootstrap` takes an SC archive and emits a starter `PackConfig` JSON with one entry per mod and per extracted asset. Mods that batch-match on Modrinth get a `modrinth` source filled in; everything else gets a `smrt_cache` placeholder with a TODO marker for manual review. Assets extracted from `extra.zip` get `smrt_static` placeholders that the admin must upload separately via the admin API.
- `validate` cross-references the pack config against an SC archive: every modid present in the SC archive must appear in the pack config (either as the same mod or as a deliberate substitution with matching modid), otherwise the SC handshake will reject the client.
- `build` reads the curated config and produces the wire manifest under `/var/lib/smrt/packs/{pack_id}/manifests/{version}.json`, updates the `latest` symlink, and writes `summary.json`. It does not download Modrinth jars (clients do that at install time); it verifies smrt_cache entries exist locally and surfaces missing ones as errors so the admin uploads them before the manifest goes live.

## Authentication for the admin token

Set `SMRT_ADMIN_TOKEN` in the environment of the service process. Recommended length: 32 random bytes, base64-encoded. The token is checked against the `Authorization: Bearer ...` header in constant time. Rotation: change the env, restart the service.

## Open questions

The following are intentionally unresolved and tracked here so future implementation does not paper over them.

- **Pack-version GC.** Manifests accumulate forever. Disk usage stays bounded by manifest sizes (KBs) so this is not urgent; eventually a retention policy (keep latest 12, archive older) is worth defining.
- **Mod-set differential.** The client computes the diff client-side on launch. A server-side `/v1/packs/{pack_id}/diff/{from}/{to}` endpoint would let the client skip downloading two manifests just to diff. Defer until traffic shows it matters.
- **Modrinth API failure handling.** During `smrt-pack build`, if Modrinth is unreachable for a sha1 cross-check, should we abort or skip verification? Current preference: abort, retry later.
- **Asset hosting beyond banners.** Server gallery images may grow into significant storage. Decide on per-server quotas or front the asset endpoint with a CDN.
- **Public-API stability commitment.** The spec is forwards-compatible but does not promise indefinite availability. A versioning-and-deprecation policy is needed before third-party tools start consuming the API.
- **Asset-source `modrinth` semantics.** Modrinth hosts resource packs and shaders as separate project types; the `modrinth` source for an asset uses the same `project_id` + `version_id` shape but the project may be `project_type: "resourcepack"` rather than `"mod"`. Document the mapping when first used.
