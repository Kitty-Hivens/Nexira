# Contributing to Nexira

PRs and forks are welcome. Code is GPL-3.0 — take it and do whatever.

---

## ── Branch model ──

```
dev  →  (PR + Qodana CI)  →  stable
```

All changes go through PRs against `dev`. Direct pushes to `stable` are not allowed.  
Releases are tagged from `stable` via `build_release.yml`.

## ── Changelog ──

Two files, two audiences, and they are not two depths of the same text.

`CHANGELOG.md` is the engineering log. Name the classes, the files and the
mechanism, and the reason behind the change. A contributor reads it, and
nobody else has to.

`CHANGELOG_EN.md` is what the release means for the person using the launcher,
written in their terms: what changed on screen, what stopped going wrong, what
the launcher now refuses to do. `CHANGELOG_RU.md` and `CHANGELOG_DE.md` are
its translations. An entry can matter in one file and be invisible in the
other, in either direction. Keep the version headers and dates identical so an
entry can be matched across.

CI reads `CHANGELOG_EN.md` for the release page's "What's New" and freezes a
copy into `release-manifest.json`. The launcher prefers the reader's own
language read live off `stable`, so a note fixed after a release still reaches
them. `CHANGELOG.md` becomes the release page's "What's Changed". A release
with no player notes falls back to showing the engineering log in the update
dialog.

## ── Commit style ──

Scoped, minimal commits. No squash-merging noise.

```
fix(tray): catch Throwable instead of Exception in TrayManager.init
feat(update): add isCritical flag detection from release title
chore(deps): pin JNA to 5.18.1 on Windows
```

## ── Code quality ──

Every PR is scanned by [Qodana](https://www.jetbrains.com/qodana/) (`qodana_code_quality.yml`).  
The scan runs in full mode (`pr-mode: false`) — all files, not just changed ones.

## ── Testing ──

```bash
./gradlew :client-core:test :client-launcher:test
```

Tests live in:
- `client-core/src/test/` — `AuthServiceTest`, `ServerRepositoryTest`
- `client-launcher/src/test/` — `UpdateServiceTest`, `GameCommandBuilderTest`

Shared test fixtures are in `client-core/src/testFixtures/` via `java-test-fixtures`.

After cloning, install the pre-push hook so failing tests block your push
before they hit CI:

```bash
./scripts/install-hooks.sh
```

Symlinks `hooks/pre-push` into `.git/hooks/pre-push`; future updates to the
hook script reach you automatically. Bypass with `git push --no-verify` if
you really need to push WIP, but please don't make a habit of it.

## ── Module structure ──

| Module            | Purpose                                        |
|-------------------|------------------------------------------------|
| `client-config`   | Constants, `AppConfig`, `BuildConfig`          |
| `client-core`     | Domain models, API services, interfaces        |
| `client-launcher` | DI wiring, file download, update, launch logic |
| `client-ui`       | Compose Multiplatform desktop UI               |

## ── Stack ──

Kotlin · Compose Multiplatform · Ktor · Koin · Skiko · libtray · Logback

## ── Platform notes ──

- **Windows**: native tray icon goes through `libtray` (in-house pure-Panama replacement for dorkbox/SystemTray, github.com/Kitty-Hivens/libtray). The previous JNA-pin requirement (`5.18.1` in client-ui, `6.1.6` globally via `resolutionStrategy`) was a dorkbox 4.4 hardcoded version check; both pins have been dropped along with the dorkbox dependency.
- **Linux**: AppImage is assembled manually in CI (`build_release.yml`), not via Compose packaging.
- **macOS**: DMG via `:client-ui:packageReleaseDmg`.

---

## ── License ──

By contributing, you agree that your work will be licensed under [GPL-3.0-or-later](LICENSE).
