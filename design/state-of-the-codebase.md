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

The gap narrows everywhere a machine is pointed at it, and nowhere else. Motion got a scanner and has seventy-three call sites and no bypasses. Spacing got a rewrite and no scanner, and has zero call sites in the application module -- the rewrite fixed the rung list, which was never what was wrong. The test matrix was hand-kept and had three modules missing from it; it now has a scanner and has none.

The lesson is not "write more scanners". It is that adoption is a property of enforcement, not of documentation, and the pairs above are the A/B test. A finding in this document is worth a scanner exactly when it can be stated mechanically; the ones below that cannot be are the ones that keep coming back.

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
| Two scanners covering five modules | four scanners taking their roots from `settings.gradle.kts`, so a module is covered wherever it is nested |
| CI ran three suites, ~1065 of 1780 tests | the matrix names every module suite: 23 of them, 2519 tests |
| Nine render tests, one asserting anything drawn | seventeen render classes, thirty-nine tests, sixteen classes reading pixels |
| Three module suites absent from the matrix, 42 tests never run | in the matrix, and `check-ci-suites.py` fails the build if that stops being true |
| Two of the three scanners red on `dev` | all four green |
| 27 of 59 widgets declared an instance parameter the kernel forced on them and could not use | the kernel accepts `fun Name()` too, and a props class on a declaration without the instance is now a build error |
| `saveToFile`, the About and theme-picker context callbacks, `commit`'s version parameter, the dead divider branch in `writeLine` | removed |
| The console divider never named the pack | it carries the label the launch event was already handing it |
| The wardrobe could be entered by automation and not left | `wardrobe.back`, bound like the other three surfaces |
| `SlotRenderer`'s two flow branches, `LayoutGraph`'s four instance transforms, nine identical background sliders | one implementation each |

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

**The motion scale is the counter-example.** Eight roles, 73 call sites outside its own file, and no site bypassing it. It is the only token layer in the tree with a scanner behind it, and the one bypass it did carry -- a literal `tween` in the description renderer -- was found by that scanner rather than by reading. Cause and effect are not provable from one pair, but the pair is worth keeping in view whenever the next primitive is proposed.

**Foreign-launcher import is built and unreachable.** A root locator spanning native, Flatpak and Snap layouts, four discovery sources (Minecraft Launcher, Modrinth App, Prism, FTB), and an importer that copies an instance and deduplicates its runtime into the shared roots. `LauncherImportService` and `ForeignInstanceImporter` appear only in the DI module. Nothing injects them, no screen calls them, and no localised strings exist for the flow. Unchanged since the last measurement; the pack importer next to it (`PackImportService`) is wired to the Library screen and works.

```sh
grep -rn 'LauncherImportService\|ForeignInstanceImporter' --include=*.kt --exclude-dir=build . | grep -v '/imports/'
```

## Dead

Declared and referenced nowhere else.

| What | Where |
|---|---|
| `ActiveSessionsSection` | `notifications/render/SessionChip.kt` |
| 114 localisation keys | across the interface and three locale files |

Both survive for the same reason: neither is a mistake to delete. The section is a working panel with no host, and the keys are features named in three languages and then not finished. Removing either throws away the work; the entry stays until someone decides which.

`ActiveSessionsSection` deserves its own note, still. It is a ready composable listing running games with uptime, an open-console button and an abort button, and it is mounted nowhere. The registry behind it is live -- the launch driver registers every session and the shell reads it for the tray tooltip -- and the title string exists in all three locales. Only the panel is missing. Its abort no longer has the old defect: terminate escalates and kills the process tree, so mounting it would ship a Stop button that works.

```sh
grep -rn 'ActiveSessionsSection' --include=*.kt --exclude-dir=build .
```

## Plumbing that terminates

A value that is produced, threaded through two or three layers, and dropped on the last one. Distinct from dead code: every link but the last is live, so nothing looks unused from either end.

- **The top-up URL is written twice.** The button in the profile account widget and its puppet mirror in the profile surface each carry a literal `smartycraft.ru/cabinet`, while `ServerProtocolConfig.baseUrl` exists precisely so a mirror operator can point the launcher elsewhere. The two agree on the scheme again, which is the second time they have had to be brought back into step; nothing keeps them there.

The puppet layer generalises that: every automated click re-implements the body of the control it drives, so a control and its mirror are two copies of one decision with no compiler relation between them. It is the one duplication in the tree that cannot be factored away, because the point of the mirror is to reach the action without the widget.

Two entries left this section by being connected rather than deleted -- the pack label the console divider now prints, and the wardrobe's back -- which is the distinction worth keeping: an unread value is usually a missing wire, not a dead one. What made both hard to see is that every link except the last was live.

## Unreachable

Built, correct, and impossible to get to from the UI.

- **The whole layout editor.** 4830 lines, the widget palette, the prop panel, presets, drag and drop. Entered only by pressing Ctrl+E. `requestEditToggle()` has exactly one production caller, the chord binding in the shell. No button, no menu item, nothing in the UI mentions the chord. Twelve of 59 widget kinds -- clock, music player, video, notes, checklist, quick launch, progress, launch button, mini playback and both containers -- exist only in that palette and never appear on a fresh install.
- **`accentOverride`.** Read by the palette, which re-seeds the primary accent from it and then derives the tonal expansion from the result, and written nowhere: the six writers of the record all set a nav-rail field or the blur switch. The only way to set it is editing `customization.json` by hand. Grep the name alone and the clock widget answers, which takes an unrelated `accentOverride` for its second hand. `glassIntensity` and `densityScale` sat in this entry until each was deleted rather than wired: the first named a colour thirty screens now name themselves, the second multiplied a density the compositor already sets.
- **`hardwareDecode`** on the background settings. Read by the video painter, never written.

Two fields are unreachable by design and documented as such: `nightlyChannel` (config-only opt-in) and `disabledModules` (written only from the recovery surface).

```sh
grep -rn 'requestEditToggle' --include=*.kt --exclude-dir=build .
grep -rn 'customization\.copy(' --include=*.kt --exclude-dir=build . | grep -v /test
grep -rn 'hardwareDecode *=' --include=*.kt --exclude-dir=build . | grep -vE "val |: Boolean"
```

## What is enforced, and what is not

Four custom scanners run on pull requests: one fails on a user-facing string hardcoded outside the localisation layer, one on process metadata and other Style-D comment anti-patterns, one on a duration literal written past the motion scale, and one on a module whose tests the CI matrix does not name. All four take their module list from `settings.gradle.kts`, which matters more than it sounds: the three older ones derived their roots by listing directories one level below the repository root, and that silently skipped `experimental/client-boot` and `examples/widget-pixelplayer` -- fourteen files that read as covered and were not.

All four are green.

```sh
python3 scripts/check-i18n.py      --strict   # 0 hits across 723 files
python3 scripts/check-comments.py  --strict   # 0 hits across 1095 files
python3 scripts/check-motion.py    --strict   # no call site steps around the scale
python3 scripts/check-ci-suites.py --strict   # 23 module suites, 2519 tests, all in the matrix
```

Green is not the same as blocking. `comment-lint` runs on pull requests and on pushes to `stable`, not on pushes to `dev`, so a violation committed straight to `dev` surfaces as a red check on the next pull request rather than at the commit that made it. That is a deliberate trade and worth knowing when reading a clean `dev`.

What the infrastructure still does not cover:

- **Form.** Nothing checks that a new surface uses the floored primitive, that a spacing value is on the scale, that a corner radius goes through a style token, or that a new badge is not a fourth implementation. The motion scanner is the proof that this class of rule is enforceable; spacing is the same rule with a different vocabulary and no scanner.
- **Adoption.** No check notices that a primitive has zero readers. Every entry in "Built, not adopted" above would have been caught at the commit that failed to use it. The suite scanner is the narrow case of this rule -- a test nobody runs -- and the general one is the same query asked of a symbol.

Test coverage is no longer the gap. The pull-request matrix names every module suite: 23 of them, 2519 tests, on all three operating systems, with the suite scanner failing the build if a module's tests ever stop being named. The three that used to be missing were the widget loader -- the runtime half of the validator the KSP processor enforces at compile time -- the parked boot module, and the worked example, which is the only thing in the tree that compiles against the widget ABI from outside and therefore the only test that notices an ABI change from a consumer's side.

```sh
python3 scripts/check-ci-suites.py
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
