---
title: ADR 0003 -- Launcher delta self-update
description: Move launcher self-update off the whole-artifact swap to a managed extracted install directory updated per-file, with a binary patch for the app jar. The shipped AppImage / installer / dmg becomes a first-run bootstrap; every subsequent update downloads only changed files (mainly a small patch of nexira.jar) into the managed layout and restarts. Reuses the pack update machinery (FileManifest, UpdateReconciler, ApplyJournal/ApplyRecovery); the runtime image is never re-downloaded within a Liberica line.
---

## Status

Proposed -- 2026-07-22. Supersedes nothing; replaces the mechanism behind the existing `UpdateService` + platform `IUpdateApplicator`s. The whole-artifact path stays as the fallback until the delta path reaches parity.

## Context

Established by reading the code:

- Every launcher update downloads a full per-OS artifact from GitHub Releases and replaces the whole thing. Windows runs the Inno installer (`WindowsUpdateApplicator`), Linux swaps the single `.AppImage` file (`LinuxUpdateApplicator`: backup -> copy -> chmod -> relaunch -> rollback-on-death), macOS swaps the `.app`. SHA-256 verified against a CI-published `release-manifest.json`; no delta, no signature. (`UpdateService.kt`, `*UpdateApplicator.kt`.)
- The distributable is a bundled jlink Liberica JDK 26 runtime image (the largest component) + the app uber jar `nexira.jar` (~100 MB of class data incl. Skiko) + host-only natives + agent jars. So a typical update re-downloads ~80-100 MB even though the runtime and natives are byte-identical between versions and only some classes in `nexira.jar` changed.
- The user shrinks the AppImage (`zstd -22`, 1 MB squashfs blocks) precisely because full updates are large and slow. That is a workaround for the absence of delta, and it trades against delta granularity (see "What we deliberately do not build").
- The machinery for a per-file delta already exists, written for game packs and unit-tested with zero launcher callers: `FileManifest` (`path -> FileData{md5,sha1,size}`, with `flatten`/`fileManifestOf`), `UpdateReconciler.reconcile(baseline, target, current) -> UpdatePlan(toAdd,toUpdate,toDelete,conflicts,skippedProtected)`, and `ApplyJournal`/`ApplyRecovery` for crash-safe apply. The launcher's own `ReleaseManifest` already carries per-asset sha256.
- Self-location is solved: `$APPIMAGE` (real on-disk path, not the transient FUSE mount), `jpackage.app-path`, `/proc/self/exe` (`AppRelauncher`, `AppImageRuntime`, `LinuxUpdateApplicator.resolveExecutable`).

The launcher install layout has no user-editable files, so the update is a plain two-way diff (remote manifest vs recorded local manifest), not the pack's three-way conflict reconcile.

## Decision

**The launcher runs from a managed, writable install directory. The shipped AppImage / installer / dmg is a first-run bootstrap that populates that directory once; every subsequent update fetches a per-file manifest for the target version, downloads only the changed files -- a small binary patch for `nexira.jar`, whole files for anything else, nothing for the unchanged runtime -- stages them, applies atomically through a journal, and restarts. Change detection reuses `UpdateReconciler`; integrity is gated by sha256; the apply reuses the pack `ApplyJournal`/`ApplyRecovery` pattern.**

This keeps the size work useful where it belongs -- the one-time bootstrap download -- and makes the recurring update cost the changed bytes, typically sub-MB. It also unifies the three platforms behind one "patch the managed layout" applicator instead of three artifact-swap flows.

## Managed layout

A per-user writable directory (under the launcher's existing data root via `PlatformPaths`):

```
<data>/app/
  runtime/            jlink Liberica JRE (changes only on a Liberica bump)
  lib/nexira.jar      the app uber jar (STORED, reproducible order)
  natives/            host-only skinema/FFmpeg + JNA dispatchers
  agents/             profiler-agent.jar, authlib-agent.jar
  launch              the exec stub (java -jar ...) with the mirrored JVM flags
  version             plain semver marker
  manifest.json       FileManifest of what is currently installed (the diff baseline)
```

`manifest.json` is authoritative for "what is installed" so the diff is manifest-vs-manifest (no re-hashing hundreds of runtime files on every check). A separate integrity scan can re-hash on demand (recovery).

## Bootstrap (first run)

The shipped artifact keeps its current form (AppImage / Inno / dmg) but its entry point becomes a bootstrap:

1. If `<data>/app/version` is absent or older than the bundled version, extract the bundled layout into `<data>/app/` (AppImage: `--appimage-extract` then copy; jpackage: copy `runtime/` + `app/`), write `manifest.json` + `version`.
2. Exec the managed `<data>/app/launch`.

After the first run the AppImage file is only ever a re-bootstrap safety net; it is never swapped again. On Linux the managed `launch` stub carries the JVM flags currently in the AppRun (ADR-adjacent: `a2c2971f` mirrored them), so the tuned flags survive into the managed run.

## Update pipeline

1. **Check** (existing): resolve latest in channel, fetch `release-manifest.json`.
2. **Fetch target file-manifest**: a new `files.json` asset per release -- a `FileManifest` over the app-image layout (sha1 for change detection, reused by `UpdateReconciler`; sha256 per file for the integrity gate). Optionally a `patches/` set: for a changed file, a `<path>.<fromSha>.patch` (binary delta from the immediately prior release).
3. **Diff**: `UpdateReconciler.reconcile(baseline = local manifest, target = remote manifest, current = local manifest)` -> `toAdd/toUpdate/toDelete`. No conflicts (install is not user-edited). Layer patch-selection: for each add/update, use a patch iff one exists whose `fromSha` matches the local file; else download the whole file.
4. **Stage**: download files / patches into `<data>/app/staging/`, apply patches (`bspatch(localFile, patch) -> stagedFile`), verify every staged file against its manifest sha256. A failed verify aborts the whole update (fail-closed) -- nothing touches the live layout.
5. **Apply**: journal the pending swap, atomically move staged files over the live ones + delete `toDelete`, update `manifest.json` + `version`, mark the journal complete. A crash mid-apply is recovered on next boot from the journal (reuse the `ApplyRecovery` pattern).
6. **Restart** into the managed `launch`.

Rollback: keep the prior `nexira.jar` (and any replaced file) until the new process proves it starts (the current 2 s liveness check generalizes); on failure, restore from the journal and relaunch the old.

## Patch technology

The client applies patches; CI produces them. Binary delta of a STORED, reproducible-order jar between two releases is small (tracks changed class bytes, not shifting DEFLATE streams). Candidate client libraries, decided at Phase 2:

- **bsdiff / bspatch** (`jbsdiff`, built on the already-present `commons-compress`) -- the classic software-update delta; client needs only `bspatch`.
- **zstd `--patch-from`** (`zstd-jni`, old file as the long-distance dictionary) -- also viable.

Default lean: bspatch (light, proven, client-only dependency). Fallback to a full-file download when no applicable patch exists (user more than one release behind, or a patch fails to apply / verify).

## Server / CI changes

Per release, CI additionally publishes:
- `files.json` -- the `FileManifest` of the app-image layout with sha1 + sha256 + size.
- `patches/<path>.<fromSha>.patch` -- binary deltas from the previous release for the files that changed (at minimum `nexira.jar`).

The build already stores `nexira.jar` uncompressed with reproducible file order, which is the precondition for small deltas. Whole-image `SOURCE_DATE_EPOCH` normalization is not required for this scheme (we delta files, not the squashfs).

## Phasing

Build proper-sized, testable-first, live applicator last -- the old whole-artifact path stays as fallback until parity, so a mid-build state cannot brick installs.

1. **Pure core (this phase):** `InstallLayout` (managed paths), the launcher file-manifest DTO, and `LauncherUpdatePlan` = reconcile changed-set + patch-selection. Pure, unit-tested. No IO, no live install touched.
2. **Fetch + stage + verify:** download files/patches into staging, apply `bspatch`, verify sha256. Add the patch dependency. Tested against local fixtures / a fake server.
3. **Live applicator + bootstrap:** the managed-dir applicator (journal + atomic swap + rollback) and the bootstrap-extract + `launch` stub, behind a flag, with the whole-artifact applicator as fallback.
4. **CI publish + cutover:** CI emits `files.json` + `patches/`; flip the default; retire the artifact-swap path once soaked.

## What we deliberately do not build

- **zsync / AppImageUpdate on the whole AppImage.** It block-deltas the single squashfs file, but our `-b=1M` blocks make deltas coarse and it forces a size-vs-delta trade on the same artifact. The managed-dir approach sidesteps the squashfs entirely and keeps the small-bootstrap optimization intact.
- **Hot-update without restart.** The JVM cannot hot-swap new classes / a new Compose tree; the launcher restarts. Startup speed is a separate lever (AOT cache / CRaC), out of scope here.
- **A server-side diff endpoint.** The diff is manifest-vs-manifest on the client; the server only publishes manifests + patches.

## Risks

- **Bricked install.** Mitigated by: staging + full sha256 verify before touching the live layout (fail-closed), journalled atomic apply with boot recovery, keep-old-until-proven rollback, and the shipped bootstrap artifact as a last-resort re-provision.
- **Managed dir vs read-only install.** On macOS a `/Applications` `.app` is not user-writable; the managed dir lives under the per-user data root, not inside the `.app`. Windows/Linux similarly use the per-user data root.
- **Patch chain gaps.** A user several releases behind may have no single applicable patch; the plan falls back to whole-file download for those files. CI need only publish previous-release patches, not an N^2 matrix.
