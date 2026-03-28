# Contributing to Aura Launcher

PRs and forks are welcome. Code is GPL-3.0 — take it and do whatever.

---

## Branch model

```
dev  →  (PR + Qodana CI)  →  stable
```

All changes go through PRs against `dev`. Direct pushes to `stable` are not allowed.  
Releases are tagged from `stable` via `build_release.yml`.

## Commit style

Scoped, minimal commits. No squash-merging noise.

```
fix(tray): catch Throwable instead of Exception in TrayManager.init
feat(update): add isCritical flag detection from release title
chore(deps): pin JNA to 5.18.1 on Windows
```

## Code quality

Every PR is scanned by [Qodana](https://www.jetbrains.com/qodana/) (`qodana_code_quality.yml`).  
The scan runs in full mode (`pr-mode: false`) — all files, not just changed ones.

## Testing

```bash
./gradlew :client-core:test :client-launcher:test
```

Tests live in:
- `client-core/src/test/` — `AuthServiceTest`, `ServerRepositoryTest`
- `client-launcher/src/test/` — `UpdateServiceTest`, `GameCommandBuilderTest`

Shared test fixtures are in `client-core/src/testFixtures/` via `java-test-fixtures`.

## Module structure

| Module            | Purpose                                        |
|-------------------|------------------------------------------------|
| `client-config`   | Constants, `AppConfig`, `BuildConfig`          |
| `client-core`     | Domain models, API services, interfaces        |
| `client-launcher` | DI wiring, file download, update, launch logic |
| `client-ui`       | Compose Multiplatform desktop UI               |

## Stack

Kotlin · Compose Multiplatform · Ktor · Koin · Skiko · dorkbox/SystemTray · Logback

## Platform notes

- **Windows**: JNA must stay pinned to `5.18.1` in `client-ui` and forced to `6.1.6` globally via `resolutionStrategy` — dorkbox/SystemTray 4.4 has a hardcoded version check.
- **Linux**: AppImage is assembled manually in CI (`build_release.yml`), not via Compose packaging.
- **macOS**: DMG via `:client-ui:packageReleaseDmg`.

---

## License

By contributing, you agree that your work will be licensed under [GPL-3.0](LICENSE).
