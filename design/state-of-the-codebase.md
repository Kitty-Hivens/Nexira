# State of the codebase

Every number here was measured, not estimated, and every claim carries the command that produced it. If a command now returns something else, this document is wrong and the command wins.

Scope: the launcher repository at the time of writing. 855 production source files, ~121k lines, twenty-three Gradle modules.

```sh
find . -name '*.kt' -o -name '*.java' | grep -v /build/ | grep -v /test | xargs wc -l | tail -1
grep -c '^include' settings.gradle.kts
```

## The pattern

The recurring shape is not bad code. It is good code that was never connected to anything.

A primitive gets designed carefully, documented well, and then adopted by nobody. A guard gets written and wired to a task nothing runs. A feature gets built end to end and given no entry point. Each instance looks like an oversight; together they are a systemic gap, because the project has no mechanism that notices when something built stops being used, or was never used at all.

The previous measurement said exactly that and then something happened worth recording: the gap narrowed everywhere a machine was pointed at it, and nowhere else. Motion got a scanner and has seventy-two call sites and one bypass. Spacing got a rewrite and no scanner, and has zero call sites in the application module. Continuous integration got every module's test task spelled out and now runs 98% of the suite. The design system got no such list and its own module's tests are the ones still not running.

The lesson is not "write more scanners". It is that adoption is a property of enforcement, not of documentation, and this codebase now has the A/B test to prove it.

## Closed since the last measurement

Recorded because a census that only grows teaches nothing about which interventions worked.

| Was | Now |
|---|---|
| `Spacing` had zero call sites | 41, all in `nx-ui`; the scale itself was rewritten from seven rungs to eleven after the first version proved unusable |
| `Flexible` had no consumers outside `nx-ui` | 19 call sites across 10 `client-ui` files |
| `SettingsData.saveCredentials` dead | read and written by the login panel |
| `SettingsData.savedFileManifest` dead | field removed |
| `SettingsData.memoryMB` unreachable | field removed; heap is per-instance |
| `InstanceRuntime.autoConnectServerId` dead | field removed |
| `ShellRegionProps.glassAlphaPct` dead | read by the shell and by the editor's prop panel |
| `preferredFaceProvider` never written | written by the profile face picker and cleared on sign-out |
| `NxPanel`, `NxHorizontalScrollbar`, `NoOpIndication` unreferenced | deleted |
| `AssetRowPanel`, `DisintegrateBox`, `RoleGroupSection` unreferenced | deleted |
| `verifyRuntimeModules` wired to a `check` nothing ran | run explicitly in the pull-request test workflow |
| Two scanners covering five modules | three scanners deriving their roots from the module list, so a new module is covered the day it lands |
| CI ran three suites, ~1065 of 1780 tests | CI runs twenty module test tasks, 2474 of 2516 |
| Nine render tests, one asserting anything drawn | seventeen render classes, thirty-nine tests, sixteen classes reading pixels |

## Built, not adopted

**The spacing scale has zero call sites in the application.** `nx-ui/theme/Spacing.kt` was rewritten after the first attempt failed: the old seven-rung ladder was derived from an ideal rather than from this codebase and left out four of its most common values, so it had no readers at all. The new eleven-rung version fixes exactly that -- and has 41 readers, every one of them inside `nx-ui` itself. `client-ui` has none. Against it stand 1660 dp literals across 94 distinct values.

The scale is now correct and still unused where it matters. What changed between the two attempts was the rung list; what did not change was that nothing checks.

```sh
grep -rn 'Spacing\.' --include=*.kt --exclude-dir=build client-ui/src | wc -l   # 0
grep -rn 'Spacing\.' --include=*.kt --exclude-dir=build nx-ui/src   | wc -l     # 41
grep -rhoE '\b[0-9]+(\.[0-9]+)?\.dp\b' --include=*.kt --exclude-dir=build client-ui/src nx-ui/src | sort | uniq -c | sort -rn | head
```

**The legibility floor is adopted by 28 of the 50 files that draw a surface.** `FrostSurface` separates `Body` (an opaque tonal floor, independent of the user's glass knob) from `Fill` (a glass coat that thins to nothing). `NxSurface` and `NxCard` wrap it. 28 files use one of them; 26 files still call `glassSurfaceAlpha` across 46 sites, which has no floor. Four files use both, which is the visible seam: a screen whose card is floored and whose panel behind it is not.

This is the mechanical cause of the surface inconsistency between screens. It is also the one metric here that moved in the right direction on its own -- 8 files to 28, 56 sites to 46 -- because the migration is being done by hand, one screen at a time.

```sh
grep -rln 'NxSurface(\|NxCard(' --include=*.kt --exclude-dir=build client-ui/src | wc -l
grep -rn 'glassSurfaceAlpha(' --include=*.kt --exclude-dir=build client-ui/src nx-ui/src | grep -v 'fun glassSurfaceAlpha' | wc -l
```

**The style axis is still mostly inert.** Ten tokens exist. Consumers: `cardCorner` 37, `buttonCorner` 13, `panelCorner` 9, `panelElevation` 5, `softGlowEnabled` 5, `animationMultiplier` 3, `cardSurface` 3, `switchStyle` 2, `badgeStyle` 2, `cardBorder` 1.

`cardSurface` -- the token that decides glass versus flat, the most visually consequential difference between the two shipped styles -- reports three hits, of which two are comments. Its one real consumer is still the bridge that hands the value to the April Fools engine.

The result is unchanged and now measured twice: rendering one screen under both styles differs by 0.0867% of pixels, and the whole middle third of the frame is byte-identical. Two "visual variants" are, in practice, a corner-radius toggle.

```sh
./gradlew :client-ui:desktopTest --tests '*VersionPickerWindowRenderTest'
python3 -c "from PIL import Image; import numpy as np; a=np.asarray(Image.open('client-ui/build/render/version-picker-fhd.png').convert('RGB')).astype(int); b=np.asarray(Image.open('client-ui/build/render/version-picker-fhd-brut.png').convert('RGB')).astype(int); m=(abs(a-b).sum(2)>8); print(f'{100*m.mean():.4f}%')"
```

**The motion scale is the counter-example.** Eight roles, 72 call sites outside its own file, and exactly one site bypassing it. It is the only token layer in the tree with a scanner behind it. Cause and effect are not provable from one pair, but the pair is worth keeping in view whenever the next primitive is proposed.

**Foreign-launcher import is built and unreachable.** A root locator spanning native, Flatpak and Snap layouts, four discovery sources (Minecraft Launcher, Modrinth App, Prism, FTB), and an importer that copies an instance and deduplicates its runtime into the shared roots. `LauncherImportService` and `ForeignInstanceImporter` appear only in the DI module. Nothing injects them, no screen calls them, and no localised strings exist for the flow. Unchanged since the last measurement; the pack importer next to it (`PackImportService`) is wired to the Library screen and works.

```sh
grep -rn 'LauncherImportService\|ForeignInstanceImporter' --include=*.kt --exclude-dir=build . | grep -v '/imports/'
```

## Dead

Declared and referenced nowhere else.

| What | Where |
|---|---|
| `ActiveSessionsSection` | `notifications/render/SessionChip.kt` |
| `GameConsoleService.saveToFile` | superseded by `exportEntries`, which the console window calls instead |
| `AboutContext.triggerUpdateCheck` | the surface builds the lambda, puts it in the context, and calls its own copy |
| `ThemePickerContext.onApply` | same shape; the context's own KDoc records that the apply button stayed in surface chrome |
| `LayoutApplier.commit(newVersion)` | the parameter; `commitFrom` reads the version back out of the marker |
| `GameConsoleService.writeLine`'s divider branch | an `if` with a comment and no body |
| 114 localisation keys | across the interface and three locale files |

`ActiveSessionsSection` deserves its own note, still. It is a ready composable listing running games with uptime, an open-console button and an abort button, and it is mounted nowhere. The registry behind it is live -- the launch driver registers every session and the shell reads it for the tray tooltip -- and the title string exists in all three locales. Only the panel is missing. Its abort no longer has the old defect: terminate escalates and kills the process tree, so mounting it would ship a Stop button that works.

```sh
grep -rn 'ActiveSessionsSection' --include=*.kt --exclude-dir=build .
```

## Plumbing that terminates

A value that is produced, threaded through two or three layers, and dropped on the last one. Distinct from dead code: every link but the last is live, so nothing looks unused from either end.

- **The console never learns the pack's name.** `LauncherController` emits `SessionStarted(targetId, targetLabel)`, the UI collector passes both to `GameConsoleService.startSession(packId, packLabel)`, and the service drops the label. The session divider reads `--------- Session started HH:MM:SS ---------` with no pack in it. The command-line front end, which reads the same event, prints the label.
- **The wardrobe cannot be left by automation.** `AppLayout` passes `onBack` to `WardrobeSurface`, which ignores it. About, the theme picker and the profile all bind theirs to a puppet click; the wardrobe has no `wardrobe.back`.
- **The top-up URL is written twice.** The button in the profile account widget and its puppet mirror in the profile surface each carry a literal `smartycraft.ru/cabinet`, while `ServerProtocolConfig.baseUrl` exists precisely so a mirror operator can point the launcher elsewhere. Two literals, one configurable value, and nothing keeps the pair in step.

The puppet layer generalises that last one: every automated click re-implements the body of the control it drives, so a control and its mirror are two copies of one decision with no compiler relation between them.

## Duplicated by construction

Not style duplication -- shapes the code cannot factor without a deliberate abstraction, so they get copied instead.

- **`SlotRenderer`'s flow branches.** The `Row` orientation and the `else ->` `Column` fallback are identical line for line except for the composable and the arrangement axis, about twenty-two lines each. `Modifier.weight` is scope-typed, which is what blocked the obvious extraction; passing the weight modifier in as a lambda unblocks it.
- **`LayoutGraph`'s instance transforms.** `updateWidgetProps`, `updateWidgetChrome`, `setWidgetWeight` and `setCanvasPlacement` are four copies of "find the instance, return unchanged if it already matches, otherwise rebuild the list".
- **`BgPositionXWidget` and `BgPositionYWidget`.** Eleven identical lines with one axis swapped, in a package of nineteen files that are each a single slider bound to one background field.

## Unreachable

Built, correct, and impossible to get to from the UI.

- **The whole layout editor.** 4830 lines, the widget palette, the prop panel, presets, drag and drop. Entered only by pressing Ctrl+E. `requestEditToggle()` has exactly one production caller, the chord binding in the shell. No button, no menu item, nothing in the UI mentions the chord. Twelve of 59 widget kinds -- clock, music player, video, notes, checklist, quick launch, progress, launch button, mini playback and both containers -- exist only in that palette and never appear on a fresh install.
- **`glassIntensity` and `densityScale`.** Both are read (`glassIntensity` by the surface layers, `densityScale` as a global `Density` override at the shell root) and neither is written anywhere. The only way to change them is editing `customization.json` by hand.
- **`hardwareDecode`** on the background settings. Read by the video painter, never written.

Two fields are unreachable by design and documented as such: `nightlyChannel` (config-only opt-in) and `disabledModules` (written only from the recovery surface).

```sh
grep -rn 'requestEditToggle' --include=*.kt --exclude-dir=build .
for k in glassIntensity densityScale hardwareDecode; do grep -rn "$k *=" --include=*.kt --exclude-dir=build . | grep -vE "val $k|\* |// "; done
```

## What is enforced, and what is not

The enforcement infrastructure grew and it works. Three custom scanners run on pull requests: one fails on a user-facing string hardcoded outside the localisation layer, one on process metadata and other Style-D comment anti-patterns, one on a duration literal written past the motion scale. All three now derive their scan roots from the module layout rather than a hand-kept list, so `nx-ui` and the widget modules -- previously covered by nothing -- are covered.

Two of the three are red on `dev` right now.

```sh
python3 scripts/check-i18n.py     --strict   # 0 hits across 716 files
python3 scripts/check-comments.py --strict   # 4 hits across 1081 files, exit 1
python3 scripts/check-motion.py   --strict   # 1 hit, exit 1
```

The comment scanner's four hits are two version-tied references and two parenthesized issue refs, all in comments about the legacy server path's retirement. The motion scanner's one hit is a literal `tween()` duration in the description renderer. Neither gate blocks the branch today -- `comment-lint` runs on pull requests and on pushes to `stable`, not on pushes to `dev` -- so both will surface as a red check on the next pull request rather than at the commit that introduced them.

What the infrastructure still does not cover:

- **Form.** Nothing checks that a new surface uses the floored primitive, that a spacing value is on the scale, that a corner radius goes through a style token, or that a new badge is not a fourth implementation. The motion scanner is the proof that this class of rule is enforceable; spacing is the same rule with a different vocabulary.
- **Adoption.** No check notices that a primitive has zero readers. Every entry in "Built, not adopted" above would have been caught at the commit that failed to use it.

Test coverage improved sharply. 2516 test methods exist; the pull-request matrix runs twenty module test tasks covering 2474 of them on all three operating systems. The 42 that never run are `:widget-loader:test` (12), `:experimental:client-boot:test` (9) and `:examples:widget-pixelplayer:test` (21). The workflow comment above that task list claims every module with tests is listed, which is now three modules out of date -- the widget loader is the one that matters, since it is the runtime half of the same validator the KSP processor enforces at compile time.

```sh
grep -rh '@Test' --include=*.kt --include=*.java */src */*/src | wc -l
grep -n 'gradlew' .github/workflows/tests.yml
```

The visual layer is no longer the sharpest case. Seventeen render classes hold thirty-nine tests, and sixteen of the seventeen read pixels back rather than asserting a non-empty PNG: the boot overlay, the play button, the progress bar, the menu opacity, the activity pill, the gallery, the console canvas and the version picker all assert colour or geometry at named coordinates. The one that does not is the HTML renderer, which asserts on its parsed model instead.

## Half-built features, found through their own strings

Eleven percent of localisation keys are declared and never read: 114 of 1036, across a localisation module of 5704 lines. Because a key only exists once someone has written a feature far enough to name its labels in three languages, that list is a better census of unfinished work than the tracker.

- **The sectioned content tab.** Thirty-three `contentTab*` keys name sections (mods, optional, resource packs, shader packs, configs, libraries, other assets, role), per-item metadata (size, url, missing count, optional flag) and the dependency resolver's three failure messages. The shipped tab is a flat filtered list. This is the single largest unfinished feature visible in the strings, and it is the one the dependency-graph work already has an engine for.
- **Verify and repair.** `packSettingsMissing` survives from that cluster. The machinery to do it already exists: computing a plan against a pinned manifest and applying it under the instance lock is what an update already does.
- **The editor's entry point.** `editorFabEdit` and `editorFabDone` are the labels for a floating button that would enter and leave edit mode. The button does not exist, which is why the editor is chord-only.
- **The right rail's affordance.** `railCollapse` and `railExpand` exist while the rail is collapsed and expanded by an undiscoverable swipe.
- **The worlds tab's dimensions.** `worldsTabDimOverworld`, `worldsTabDimNether`, `worldsTabDimEnd`, `worldsTabDimOther`, `worldsTabLastPlayed`.
- **Profile categories and security.** `profileCategoryAccount`, `profileCategorySignIn`, `profileCategorySecurity`, `profileSecurityHint`, `profileForgetSavedSignIn`.
- **The diagnostic report flow.** `reportBundleAttach`, `reportBundleCreated`, `reportBundleHint`, `reportCrashHint`, `reportDescribeHeading`, `reportLanguageNudge`.
- **Background tints and sections.** Five named tints plus four section headings, from a background settings screen that was reorganised without them.
- Plus pagination, the about screen's manual update check (deliberately removed, strings left behind), and several notification reasons.

```sh
# lists every declared key with no reader
python3 - <<'EOF'
import re, pathlib
api = pathlib.Path("client-i18n/src/desktopMain/kotlin/hivens/ui/i18n/AppStrings.kt").read_text()
keys = {m.group(1) for m in re.finditer(r'^\s*(?:val|fun)\s+([a-z][A-Za-z0-9_]*)', api, re.M)}
buf = [f.read_text(errors="replace") for f in pathlib.Path(".").rglob("*.kt")
       if "/build/" not in str(f) and "/i18n/" not in str(f)]
b = "\n".join(buf)
print("\n".join(sorted(k for k in keys if not re.search(r'\.' + k + r'\b', b))))
EOF
```

## Tracker accuracy

Forty-four issues are open. Six carry a checklist, and two of those are the shape the previous measurement named: an epic that holds work directly rather than only links. #378 stands at 33 of 50 boxes and #422 at 5 of 22, so both read as barely started to anyone who does not open them.

The structural fix is unchanged: an issue holds one thing with a condition for closing it, an epic holds only links, and a checklist is allowed only where the items are the same work repeated over different data.

One authoring rule follows from the drift itself. Almost everything that went stale went stale by coordinate: files moved, line numbers shifted, a type changed shape. Issues that name a symbol survive refactoring and are verifiable with one grep; issues that name `file:line` rot on the next edit above them. This document is written to the same rule, which is why the module carve-outs that moved the localisation layer out of `client-ui` invalidated one of its commands and none of its findings.

```sh
gh issue list --limit 300 --state open --json number,title,body | python3 -c "
import json,sys,re
d=json.load(sys.stdin)
print('open:',len(d))
for i in d:
    b=i['body'] or ''
    done=len(re.findall(r'^\s*[-*]\s*\[[xX]\]',b,re.M)); todo=len(re.findall(r'^\s*[-*]\s*\[ \]',b,re.M))
    if done+todo: print(f\"  #{i['number']} {done}/{done+todo} {i['title'][:60]}\")"
```
