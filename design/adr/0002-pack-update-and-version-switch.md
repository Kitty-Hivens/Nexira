---
title: ADR 0002 -- Pack update and version-switch contract
description: How a mirror pack instance moves from its installed build to another build (forward update or rollback) without losing user data, user edits, or optional-content choices. Client-side three-way reconcile, auto-update on with a compatibility gate, snapshots before unsafe changes, mirror slug as the one server-side prerequisite.
---

## Status

Proposed -- 2026-07-16. Supersedes nothing; extends ADR 0001 (mirror introduction) into the update lifecycle it left open.

Depends on:
- Mirror emits `slug` on optional mod entries (server-side prerequisite, see "Mirror-side prerequisites").
- `PackInstance.installedManifest` is written at install and after every apply (client wiring, currently the field exists but is never populated).

## Context

ADR 0001 introduced the mirror as a pack-content source. It settled how a pack is installed, not how an installed pack moves to a newer build. The current state, established by reading the code:

- Install pins and freezes. `PackInstaller.install` builds a `PackInstance` with `pinnedPackVersion = manifest.packVersion` and never revisits it. `LauncherController.preparePackLaunch` deliberately skips per-launch re-sync for mirror packs ("static, already on disk"). An installed mirror pack therefore never learns a newer build exists.
- The correct machinery is already written but dead. `UpdateReconciler.reconcile(baseline, target, current, isProtected)` returns an `UpdatePlan(toAdd, toUpdate, toDelete, conflicts, skippedProtected)`; `CompatGate.classifyCompat` grades a candidate build. Both are unit-tested with zero production callers. Their inputs -- `PackInstance.installedManifest` (a `FileManifest` baseline) and a target manifest -- are never produced in the mirror path.
- The live install-time path (`SmrtSyncService.sync`) is a stateless reconcile against the fetched manifest. It handles mods fully (download changed, `pruneOrphanMods` removes dropped ones) but has two properties that make it wrong for updates: it prunes any non-manifest jar in top-level `mods/` (so a user-added mod is swept), and it does not prune assets at all (a dropped asset lingers forever). With no baseline it cannot tell a user edit from a pack change, so it cannot resolve conflicts.
- The legacy SC path updates implicitly per-launch by MD5 diff of the login manifest; it is version-less and out of scope here.

Direction set for this ADR: build the full update lifecycle, not a minimal patch. Auto-update is on by default (the launcher is used by more than one person and stale packs desync from the live server). "On by default" must still be safe: a build that changes Minecraft or the loader family can break worlds and configs, so unsafe changes snapshot first and are never applied silently.

## Decision

**A mirror pack instance updates via a client-side three-way reconcile against a locally stored baseline, gated by a compatibility grade, with a snapshot taken before any unsafe change and full rollback available. Version switching (forward and backward) uses the same machinery against any build the mirror retains. The one server-side prerequisite is a stable `slug` on optional entries; everything else the mirror already serves.**

The reconcile is client-side by necessity: the correct plan depends on on-disk state (user edits, user-added files, optional toggles) that the server cannot see. A server-side diff endpoint would be the wrong shape and is explicitly not built (see "What we deliberately do not build").

## Update mechanism

The pipeline for one instance, forward or backward:

1. **Detect.** Cheap poll: `GET /v1/packs/{id}` summary, compare `latest_pack_version` (tuple comparison, `compare_pack_versions`) against the instance `pinnedPackVersion`. A newer value means "update available". No manifest fetch yet. When the manifest gains a consumed `fingerprint` (see below) the poll can additionally confirm content actually differs, not just the label.
2. **Fetch target.** `GET /v1/packs/{id}/manifest` (latest) or `/manifest/{version}` (a chosen build). This is the only manifest fetched -- the baseline is already on disk, so there is no need to fetch the old manifest.
3. **Scan on-disk.** Walk the instance directory into a `FileManifest` (`current`), mapping an optional mod's on-disk `.disabled` variant back to its canonical path so the reconcile keys line up (see "Optional content across updates").
4. **Reconcile.** `UpdateReconciler.reconcile(baseline = installedManifest, target = convert(targetManifest), current, isProtected = ProtectedPaths::isProtected)` produces an `UpdatePlan`.
5. **Grade.** `CompatGate.classifyCompat(cachedManifest, target.mc, target.loader.name, target.loader.version)` yields `Same | LoaderBump | McBump | LoaderSwap | Unknown`. Green (`isSafe`) is `Same` or `LoaderBump`; the rest are amber.
6. **Snapshot if unsafe** (see "Snapshots and rollback").
7. **Apply.** Download `toAdd` + `toUpdate`, delete `toDelete`, for each `conflicts` entry keep the on-disk file and write the pack's version beside it as `<path>.new`, never touch `skippedProtected` or user-added files. Apply is journaled and atomic-enough to resume or roll back on crash (see "Failure handling").
8. **Commit.** On success, overwrite `installedManifest` with the target baseline, set `pinnedPackVersion` to the target version, refresh `cachedManifest`, and persist. On failure, roll back to the pre-apply state and surface the error loudly.

The converter `SmrtPackManifest -> FileManifest` is the missing keystone that revives the dead path: each mod becomes `mods/{filename}` and each asset its `dest`, with `FileData(sha1, size)`. `FileData` already carries a `sha1` field for exactly this purpose (its own KDoc anticipates the mirror/mrpack sha1 baseline), so the install flow records the baseline "for free" and the reconciler diffs without re-hashing.

## Compatibility gate: auto vs confirm

Auto-update default is on. What "auto" does is graded, not unconditional:

- **Green (`Same`, `LoaderBump`)** -- Minecraft and loader family unchanged. The pack re-syncs without a structural break. Auto-apply silently in the background; a notification reports the result.
- **Amber (`McBump`, `LoaderSwap`, `Unknown`)** -- Minecraft version changed, loader family swapped, or no baseline to compare. These can invalidate worlds, configs, or mod state. Never silent. A snapshot is taken and the user is asked to confirm; a per-policy sub-setting can switch amber to "snapshot then apply" for users who accept the risk. `Unknown` (no baseline, e.g. a first update after this feature ships or after an SC-to-mirror flip) forces a full re-sync and is treated as amber.

The gate applies equally to a forward update and a version switch; a downgrade is graded against the target the same way and is always at least amber because rolling backward is inherently structural.

## Snapshots and rollback

Before any amber apply (and, per policy, optionally before every apply), the instance is snapshotted so the user can undo a bad update or a regretted version switch.

- **Scope.** Snapshot the pack-managed and user-config surface: `mods/` state, `config/`, `options.txt`, resource/shader pack lists, and the `installedManifest` + `pinnedPackVersion` + `cachedManifest` metadata. Exclude bulk immutable-elsewhere content where practical; worlds are the open question below.
- **Form.** A cheap copy under the instance's own snapshot area (hard-links or copy-on-write where the filesystem supports it, plain copy otherwise), labelled with the from/to versions and a timestamp passed in (scripts and pure code do not read the clock themselves).
- **Rollback.** Restores the snapshot and re-pins to the previous version. Because rollback is just "apply the older build with a snapshot", it reuses the same pipeline; the snapshot is the safety net if even that fails.
- **Retention.** Keep the last N snapshots per instance (N small, configurable), prune oldest. Snapshots are local only and never uploaded.

## Version pinning, switching, downgrade

The mirror retains full build history on disk (`manifests/{version}.json` never deleted; `latest` is a moving symlink) and serves `/v1/packs/{id}/manifest/versions`. The client exposes this:

- **Follow latest (default).** `pinnedPackVersion` tracks whatever the summary reports as latest; auto-update keeps it current.
- **Pinned.** The user pins a specific build; auto-update is suppressed for that instance and an "update available" affordance is informational only.
- **Switch / rollback.** The user picks any retained build from the version list and the pipeline reconciles toward it (forward or backward), snapshotting first. Downgrade is always graded amber.

This requires lifting the current cap: `MirrorPackCatalogue.versions()` returns only `latest_pack_version` today, so even though the historical-manifest endpoint exists, no build but latest is reachable from the UI. The catalogue must surface `/manifest/versions` for the switch UI.

## Optional content across updates

An optional mod toggled off lives on disk as `mods/{filename}.disabled`. The user's on/off choice must survive a version bump, but `filename` carries the mod version and changes every build, so it cannot be the identity key. `SmrtModEntry.stableKey` already resolves `slug -> modrinth:project_id -> filename`:

- Modrinth-sourced optionals already survive (their `project_id` is stable).
- `smrt_cache` optionals (the 1.7-1.12 industrial mods the mirror exists for) fall through to `filename` and lose their toggle state on every update, unless the mirror authors a `slug`.

Two obligations follow:

1. The mirror authors `slug` on optional entries (prerequisite below).
2. The apply step is optional-aware: it reads the persisted `ContentToggle` state keyed by `stableKey`, maps the target's canonical `mods/{filename}` to the correct on-disk variant (`{filename}` when enabled, `{filename}.disabled` when disabled), and the on-disk scan in step 3 normalizes `.disabled` back to the canonical path so the reconcile does not see a disabled optional as "missing" and re-add it enabled. Without this normalization, every update silently re-enables mods the user turned off.

## What is never touched

The reconciler's guarantees define the safety envelope, and the apply step must not widen it:

- A file present on disk but in neither baseline nor target is user-added and appears in no list -- never modified or deleted. User-dropped mods, personal configs, and screenshots are safe.
- A file the pack dropped that the user modified (on-disk hash differs from baseline) is kept, not deleted.
- A protected path (user-config surface per `ProtectedPaths`) is never written or deleted; when the pack changes it, the pack's version lands as `<path>.new` for the user to reconcile manually.
- Worlds are never in `toDelete` because they are never in a manifest.

## Failure handling

Consistent with ADR 0001's fail-loud stance:

- No silent partial success. A download error, sha1 mismatch, or IO failure aborts the apply and surfaces a clear error.
- Atomic-enough and resumable. The plan is journaled before apply; individual file writes go through a temp-then-atomic-move (as `SmrtSyncService.downloadToFile` already does). A crash mid-apply resumes from the journal or rolls back to the snapshot; it never leaves a half-updated instance that classloads garbage.
- Sha1 verification after every download; bad bytes are deleted so a retry refetches.
- A failed auto-update does not silently fall back to the old build as if nothing happened; it reports, leaves the instance on its snapshot-restored prior state, and lets the user retry.

## Settings and UI surfaces

Version and update are per-instance concerns; only the policy default is global.

Per-instance (`PackDetailScreen` plus an expanded `PackSettingsModal`, which is RAM-only today):

- Hero: a version chip beside the existing loader+MC label, with an "update available" badge when the poll finds a newer build. Computed on detail open (one small summary GET), surfacing the capability up front rather than on a click-then-fail.
- A "Version and updates" section: current version and last-checked time; a check-now action; mode radio (Follow latest / Pinned); the available update with an apply action and its compatibility color (green safe, amber snapshot-first); a version history list with switch and rollback, each snapshot-gated.

Global Settings (`SettingsCategory` enum, seven sections today: Appearance, Console, Network, Smarty, Experimental, Advanced, Diagnostics) -- policy only:

- Phase 1: an "Auto-update packs" toggle in Experimental (default on), alongside the existing `autoSyncAllPacks` (which drives SC-server re-sync, a different axis). A sub-control for amber behavior: Ask / Snapshot then apply / Hold.
- Later: a dedicated "Updates" or "Packs" category holding auto-update policy, amber behavior, snapshot retention count, and update-check cadence (on launch / on app start / manual).

Notifications: "update available" and "pack updated" flow through the existing notifications surface rather than a modal interrupt.

## Mirror-side prerequisites

The mirror already serves everything the update path needs -- per-version manifests, the versions list, per-file sha1, and a content `fingerprint` on the wire. One authoring gap remains:

- **`slug` on optional entries.** The wire `ModEntry` (`domain/manifest.rs`) has no `slug` field, and the authoring input `DeclaredMod` does not carry one; the `slug` values present elsewhere in the mirror are the Modrinth project-slug in harvest and the registry's canonical mod slug, neither of which reaches the pack manifest. Add a curator-assigned `slug` to the wire `ModEntry`, thread it from `DeclaredMod` through `smrt-pack build`, and author slugs for optional `smrt_cache` mods in the pack configs. Additive; `schema_version` stays at 2. This is the one change that makes optional-toggle-survives-update actually hold for non-Modrinth mods.

Not required on the mirror:

- `fingerprint` is already emitted (`skip_serializing_if` when absent); consuming it is a client-only change -- add the field to `SmrtPackManifest`, which currently drops it via `ignoreUnknownKeys`. Low priority: label comparison already answers "is there a newer build".
- Document the `fingerprint` field in the API spec so its meaning is contractual rather than incidental.

## What we deliberately do not build

- **Server-side `/v1/packs/{id}/diff/{from}/{to}`.** The reconcile needs the client's local baseline and on-disk state, which the server does not have; a server diff would compute the wrong thing and save only a single manifest fetch the client does not make. The spec already lists this as deferred; this ADR upgrades that to "not built by design".
- **ETag / conditional GET on the manifest.** The summary poll already gives cheap change detection. Revisit only if traffic shows the manifest fetch itself is a cost.
- **Binary delta downloads (bsdiff between jar versions).** Large effort, marginal gain over sha1-addressed dedup via `smrt_cache`. Future, not now.

## Out of scope / future

- Unifying the legacy SC per-launch implicit update with this versioned model. The SC path stays as-is; this ADR is pack-centric mirror only.
- Per-build changelogs / release notes served by the mirror (a `notes_md` on the summary or a per-version field) so the update prompt can say what changed. Additive and worth doing once authoring exists.
- Background prefetch of a pending update, update-on-quit, and scheduled update windows.
- Snapshotting worlds -- size versus safety tradeoff is unresolved (see open questions).

## Data model changes

- Populate `PackInstance.installedManifest` (exists, always null today) at install and after each apply.
- Add the `SmrtPackManifest -> FileManifest` converter (mods + assets -> sha1 tree).
- Optionally add `fingerprint: String?` to `SmrtPackManifest`.
- Add `slug` to the wire `ModEntry` and `DeclaredMod` on the mirror.
- Persist snapshot metadata (from/to version, timestamp) per instance.

## Tests required before shipping

- Reconcile parity: mods and assets are treated identically -- a dropped asset is deleted just like a dropped mod, a user-edited dropped file is kept.
- Optional survival: an optional mod toggled off, then the pack bumped, stays off after update; verified for both a Modrinth optional (project_id key) and a `smrt_cache` optional (slug key).
- User-added safety: a user-dropped jar and a user-edited config survive an update untouched; a pack change to a protected path lands as `.new`.
- Compatibility grading: `Same`/`LoaderBump` auto-apply; `McBump`/`LoaderSwap`/`Unknown` snapshot and gate.
- Rollback: after a forward update, rollback restores byte-identical prior state and re-pins the prior version.
- Crash-mid-apply: a killed apply resumes or rolls back; the instance never ends up half-updated.
- Version switch: installing latest, then switching to an older retained build, reconciles to exactly that build's content.

## Open questions

- **World snapshotting.** Worlds dominate instance size and are never in a manifest, so they are safe from deletion, but an amber update that corrupts a world has no undo unless the world is snapshotted. Decide between excluding worlds (fast, no undo) and copy-on-write inclusion where the filesystem allows.
- **First-update `Unknown`.** Instances installed before this feature (or flipped from SC) have no baseline. Forced full re-sync is the fallback; confirm that reconstructing a baseline from the current on-disk state (hash what is there, treat it as the installed build) is preferable to a blind re-download.
- **Amber default.** Ship amber as Ask, or as Snapshot-then-apply? Ask is safer for a shared user base; Snapshot-then-apply is smoother. Leaning Ask with the smoother mode opt-in.
- **Snapshot retention default.** How many builds back to keep before disk pressure matters.
