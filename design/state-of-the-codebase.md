# State of the codebase

Every number here was measured, not estimated, and every claim carries the command that produced it. If a command now returns something else, this document is wrong and the command wins.

Scope: the launcher repository at the time of writing. 717 production source files, ~98k lines, twenty Gradle modules.

```sh
find . -name '*.kt' -o -name '*.java' | grep -v /build/ | grep -v /test | xargs wc -l | tail -1
```

## The pattern

The recurring shape is not bad code. It is good code that was never connected to anything.

A primitive gets designed carefully, documented well, and then adopted by nobody. A guard gets written and wired to a task nothing runs. A feature gets built end to end and given no entry point. Each instance looks like an oversight; together they are a systemic gap, because the project has no mechanism that notices when something built stops being used, or was never used at all.

The sections below are the census.

## Built, not adopted

**The spacing scale has zero call sites.** `nx-ui/theme/Spacing.kt` defines a seven-rung scale with a KDoc explaining that call sites should pull from it. Nothing calls it. Meanwhile the codebase uses 1635 dp literals across 86 distinct values, and the fourth and fifth most common values (`6.dp`, 271 uses combined) are rungs the scale does not have.

```sh
grep -rn 'Spacing\.' --include=*.kt --exclude-dir=build client-ui/src nx-ui/src | wc -l
grep -rhoE '\b[0-9]+(\.[0-9]+)?\.dp\b' --include=*.kt --exclude-dir=build client-ui/src nx-ui/src | sort | uniq -c | sort -rn | head
```

**The legibility floor is adopted by eight files.** `FrostSurface` separates `Body` (an opaque tonal floor, independent of the user's glass knob) from `Fill` (a glass coat that thins to nothing). `NxSurface` wraps it. Eight files use `NxSurface`; 56 call sites still use the older `glassSurfaceAlpha`, which has no floor. This is the mechanical cause of the surface inconsistency between screens: the Library and the settings windows sit on the floored primitive, the catalogue and pack-detail screens do not.

```sh
grep -rln 'NxSurface(' --include=*.kt --exclude-dir=build client-ui/src | wc -l
grep -rn 'glassSurfaceAlpha(' --include=*.kt --exclude-dir=build client-ui/src nx-ui/src | grep -v 'fun glassSurfaceAlpha' | wc -l
```

**The style axis is mostly inert.** Ten tokens exist. Consumers: `cardCorner` 32, `buttonCorner` 12, `panelCorner` 6, `softGlowEnabled` 5, `animationMultiplier` 4, `panelElevation` 4, `switchStyle` 2, `badgeStyle` 1, `cardBorder` 1, `cardSurface` 1. The single consumer of `cardSurface` -- the token that decides glass versus flat, the most visually consequential difference between the two shipped styles -- is the April Fools engine.

The result is measurable: rendering one screen under both styles differs by 0.087% of pixels, and the whole middle of the frame is byte-identical. Two "visual variants" are, in practice, a corner-radius toggle.

```sh
./gradlew :client-ui:desktopTest --tests '*VersionPickerWindowRenderTest'
python3 -c "from PIL import Image; import numpy as np; a=np.asarray(Image.open('client-ui/build/render/version-picker-fhd.png').convert('RGB')).astype(int); b=np.asarray(Image.open('client-ui/build/render/version-picker-fhd-brut.png').convert('RGB')).astype(int); m=(abs(a-b).sum(2)>8); print(f'{100*m.mean():.4f}%')"
```

**The Flexible platform has no consumers.** `FlexibleHost`, `FlexibleTarget`, `FlexibleHostProvider`, `LocalFlexible`, `LocalFlexibleSignals` are internally coherent and referenced only within `nx-ui`.

**Foreign-launcher import is built and unreachable.** 679 lines of production code and 311 lines of tests: a root locator spanning native, Flatpak and Snap layouts, four discovery sources (Minecraft Launcher, Modrinth App, Prism, FTB), and an importer that copies an instance and deduplicates its runtime into the shared roots. `LauncherImportService` and `ForeignInstanceImporter` appear only in the DI module. Nothing injects them, no screen calls them, and no localised strings exist for the flow.

```sh
grep -rn 'LauncherImportService\|ForeignInstanceImporter' --include=*.kt --exclude-dir=build . | grep -v '/imports/'
```

## Dead

Declared and referenced nowhere else.

| What | Where |
|---|---|
| `SettingsData.saveCredentials` | a persisted setting whose name promises not to persist credentials; read by nothing |
| `SettingsData.savedFileManifest` | a whole manifest structure carried in `settings.json` for nobody |
| `InstanceRuntime.autoConnectServerId` | "auto-connect to this server on launch", never written, never read |
| `ShellRegionProps.glassAlphaPct` | one unused field out of 28 props classes |
| `NxPanel`, `NxHorizontalScrollbar`, `NoOpIndication` | `nx-ui` primitives with no references at all |
| `AssetRowPanel` | superseded by the generic content row; the manifest-driven variant was left behind |
| `DisintegrateBox`, `RoleGroupSection` | composables with no callers |
| `ActiveSessionsSection` | see below |
| 98 localisation keys | ~392 lines across the interface and three locale files |

`ActiveSessionsSection` deserves its own note: it is a ready composable listing running games with uptime, an open-console button and an abort button. It is mounted nowhere. Its abort calls a terminate that is documented as SIGTERM, which Minecraft ignores. Mounting it today would ship a Stop button that silently does nothing.

```sh
grep -rn 'ActiveSessionsSection' --include=*.kt --exclude-dir=build .
```

## Unreachable

Built, correct, and impossible to get to from the UI.

- **The whole layout editor.** 4489 lines, the widget palette, the prop panel, presets, drag and drop. Entered only by pressing Ctrl+E. `requestEditToggle()` has exactly one production caller. No button, no menu item, nothing in the UI mentions the chord. Eleven of 57 widget kinds -- clock, music player, video, notes, checklist, quick launch, progress, launch button, mini playback, and both containers -- exist only in that palette and never appear on a fresh install.
- **`glassIntensity` and `densityScale`.** Both are read (`glassIntensity` by the surface layers, `densityScale` as a global `Density` override at the shell root) and neither is written anywhere. The only way to change them is editing `customization.json` by hand.
- **`SettingsData.memoryMB`.** The global fallback heap. The RAM selector writes per-instance values instead; this field appears in the UI module only inside a test.
- **`preferredFaceProvider`.** Which signed-in account fronts the shell. Read once, written never, so it is permanently null and the fallback always wins.
- **`hardwareDecode`** on the background settings. Read, never written.

Two fields are unreachable by design and documented as such: `nightlyChannel` (config-only opt-in) and `disabledModules` (written only from the recovery surface).

## What is enforced, and what is not

The project does have enforcement infrastructure, and it works. Two custom scanners run strictly on every pull request: one fails the build on a user-facing string hardcoded outside the localisation layer (baseline zero across 287 files), the other on process metadata in comments. This is a proven slot; a third scanner would be a hundred and fifty lines of Python in an existing workflow.

What that infrastructure does not cover:

- **Form.** Nothing checks that a new surface uses the floored primitive, that a spacing value is on the scale, that a corner radius goes through a style token, or that a new badge is not a fourth implementation.
- **Two modules.** The comment scanner covers `client-config`, `client-core`, `client-launcher`, `client-ui` and `buildSrc`. Neither scanner covers `nx-ui` or the widget modules. The design system is scanned by nothing.

Test coverage has the same shape. Roughly 1780 test methods exist; continuous integration on a pull request runs three suites and about 1065 of them. The 714 that never run include every render test, the entire design system, the widget model and kernel, and all three auth modules.

```sh
grep -rh '@Test' --include=*.kt --include=*.java */src | wc -l
grep -n 'gradlew' .github/workflows/tests.yml
```

The visual layer is the sharpest case. Nine render tests exist; most assert only that a non-empty PNG was produced. Before recent work, exactly one test in the whole 52k-line UI module asserted anything about what was drawn, and it covers the boot overlay.

There is also a build guard that never fires. `verifyRuntimeModules` fails the build if the trimmed jlink module set omits `jdk.management`, whose absence makes the packaged build silently mis-size the heap. It is wired to `check`. No workflow runs `check` for that module.

```sh
grep -rn 'gradlew' .github/workflows/*.yml | grep -oE 'gradlew[^"'"'"']*' | sort -u
```

## Half-built features, found through their own strings

Ten percent of localisation keys are declared and never read. Because a key only exists once someone has written a feature far enough to name its labels in three languages, that list is a better census of unfinished work than the tracker.

- **Verify and repair.** `packSettingsRepair`, `packSettingsRepairAction`, `packSettingsRepairDesc`, `packSettingsRepairDone`, `packSettingsMissing` are written in English, Russian and German. The copy reads "Verify and repair files" / "Re-sync the pack against the mirror". There is no repair function in the engine and no button in the data section. The machinery to do it already exists: computing a plan against a pinned manifest and applying it under the instance lock is what an update already does.
- **The editor's entry point.** `editorFabEdit` and `editorFabDone` are the labels for a floating button that would enter and leave edit mode. The button does not exist, which is why the editor is chord-only.
- **The right rail's affordance.** `railCollapse` and `railExpand` exist while the rail is collapsed and expanded by an undiscoverable swipe.
- **The dependency resolver's messages.** `contentTabResolverMissing`, `contentTabResolverCycles`, `contentTabResolverIssuesTitle`.
- **A sectioned content tab.** Nine keys naming sections (mods, optional, resource packs, shader packs, configs, libraries, other assets, role) pair with the two dead composables above. The shipped tab is a flat filtered list.
- **Pagination.** `paginationNext` and `paginationPrev` pair with the unused `Paginator`.
- Plus profile categories, worlds-tab dimension labels, and several feedback strings.

```sh
# lists every declared key with no reader
python3 - <<'EOF'
import re,pathlib
api=pathlib.Path("client-ui/src/desktopMain/kotlin/hivens/ui/i18n/AppStrings.kt").read_text()
keys={m.group(1) for m in re.finditer(r'^\s*(?:val|fun)\s+([a-z][A-Za-z0-9_]*)', api, re.M)}
b="\n".join(p.read_text(errors="replace") for p in pathlib.Path("client-ui/src").rglob("*.kt") if "/i18n/" not in str(p))
print("\n".join(sorted(k for k in keys if not re.search(r'\.'+k+r'\b', b))))
EOF
```

## Tracker accuracy

Eleven open issues carry a claim that no longer matches the code. Details belong in the issues themselves; the summary is that the drift is concentrated in two shapes.

Bundle issues: several issues open with "a cluster of" or "a bundle of" and then list three or four unrelated defects. They cannot be closed, because fixing one leaves the issue open with the rest, and nobody can tell from the outside what is done. Two such issues are now more than half fixed and still read as untouched.

Epic issues that hold work directly: two epics carry long unchecked checklists whose items point at issues that are already closed. A reader sees a dozen open items where most are a closed task with a small remainder.

The structural fix is the same in both cases: an issue holds one thing with a condition for closing it, an epic holds only links, and a checklist is allowed only where the items are the same work repeated over different data.

One authoring rule follows from the drift itself. Almost everything that went stale went stale by coordinate: files moved, line numbers shifted, a type changed shape. Issues that name a symbol survive refactoring and are verifiable with one grep; issues that name `file:line` rot on the next edit above them.
