# What's New

What each Nexira release means for the person using it: what changed on
screen, what stopped going wrong, what the launcher now refuses to do.
Written for players, in the terms a player would use.

The engineering log is a separate document. [CHANGELOG.md](./CHANGELOG.md)
names classes, files and mechanisms and is where a contributor looks. Nothing
here should require reading it. Same versions and dates in both, so an entry
can be matched across.

`CHANGELOG_RU.md` and `CHANGELOG_DE.md` are this file in other languages. The
launcher reads whichever matches the interface language and shows it in the
update dialog, so a section here is the release note a player actually sees.
One physical line per paragraph, no manual wrapping: these notes are rendered
verbatim and a hand-wrapped line becomes a staircase of breaks.

## [Unreleased]

## [2.4.0] - 2026-09-05

2.4.0 is what the previews and betas of this line were building toward. Most of it is the surface layer: what a panel is made of, whether the number you set reaches the pixel, and whether the launcher gets out of the way once the game is running. The rest is a list of things that quietly did not work, several of which could cost you an account, a session, or something you had typed.

### Highlights
- **Your layout resets once, on the first start.** A panel is described in numbers you can set now, rather than in preset names that each moved three values at once. There is no honest way to read the old descriptions as the new ones, so the arrangement goes back to the one the launcher ships with. Your old file is left on disk, untouched.
- **The appearance controls do what they say.** The opacity slider moved nothing at all before: every panel drew at one fixed value on a dark theme and refused to be see-through at all on a light one. Blur now blurs what is actually behind a panel instead of a copy of the wallpaper, a panel stops painting a hard square behind its own rounded corners, and it can be a squircle, a star or a polygon.
- **A two-factor account stops being thrown out of the game.** Opening the launcher signed you in again, and SmartyCraft kills the previous session on every sign-in, so the game you were already playing dropped you with a verification error moments later and nothing on screen connected the two.
- **The launcher gets out of the way whichever way you started the game.** Hiding after launch only worked from the classic dashboard. A pack started from the Library, from its own page, after a two-factor prompt or after an offline retry left the window standing in front of the game.
- **The window opens at full size, immediately.** It used to open small and be resized after the first frame was already drawn, which showed about two seconds of white around one correctly drawn corner.
- **Installing an update stops looking like a freeze.** The launcher sat on screen, no longer drawing, for the whole swap, and on Linux wrote two full copies of itself to do it. The window goes down first now, and the install is a rename.
- **Update notes arrive in your language.** Every label in the update dialog was translated except the one thing you open it for. The Russian and German notes existed and were read by nothing at all. They are their own document now, written for you rather than cut out of the developer log, and a note can be corrected without cutting another release.
- **News goes past three items, and shows up before you sign in.** The rail read from a payload that carries three and always has, so asking for twenty gave you three; it reads the site's archive now, a page at a time as you scroll. Neither the news nor the server list needs an account, but both were empty until you signed in, because the only place to accept the server's certificate was inside the login form.
- **Picking an older build installs that build.** The version picker recorded the build you chose and downloaded the newest one, so what was on disk was never what the launcher thought it had.
- **Nothing quietly takes your things.** Reset customization on the recovery screen deleted the notes and checklists you had typed into widgets, and asked nothing first. A launcher started while the system keyring was locked erased your saved account for good. A mod whose download failed reported itself installed.
- **Your session stays out of the logs.** A response the launcher could not read was written to the log with your uid and session token in it, and from there into any diagnostic bundle you sent for support.

## [2.4.0-beta5] - 2026-08-05

2.4.0-beta5 is a [critical] release. It fixes checks that were not running and repairs that could not happen. The guard meant to protect a mod's settings was matching the mod's own jar, so the most commonly installed mods were never updated, never retired when a pack moved on, and never repaired when their archive was damaged. A modern-loader pack picked which version file to read by directory order. The launcher hash could be poisoned by a single error page and stayed poisoned across restarts. The JVM-argument builder could compose a set the JVM refuses to start with. Stop gave up after a signal a wedged game ignores.

### Highlights
- **Guarded mods update again.** JEI, JourneyMap, VoxelMap and Xaero's maps were treated as untouchable config, so a pack update never replaced them and a damaged jar was never repaired. They are ordinary pack content again; the settings directories they exist to protect still are protected.
- **Modern-loader packs launch reliably.** Forge and NeoForge packs picked one of two version files by whatever order the filesystem returned, and picking the wrong one failed the launch with a message about a library nobody asked for.
- **The JVM-argument builder produces a set that starts.** Switching the collector and applying twice could compose arguments the JVM rejects outright, and the launch surfaced only an exit code.
- **Stop stops.** It sent one polite signal and gave up; a game that ignores it now gets a forced kill, and its child processes with it.
- **Nothing loses your things.** An interrupted write could erase the whole skin library, and a preset named in Cyrillic overwrote the one saved before it.

## [2.4.0-beta4] - 2026-08-03

2.4.0-beta4 closes the ways code could reach a launch that the pack never named. An installed pack is now held to its own bytes rather than its own filenames, the check is asked again with the game about to start, and the launcher stops carrying into the process anything it was handed on the side: its environment, the arguments a pack's settings supply, the native libraries in the instance, and the interpreter it was told to run. The SmartyCraft server list is deprecated with this release.

### Highlights
- **A pack launches as the pack, by content.** Installed mods are checked against the bytes the pack declared, not against their filenames, so a file swapped under a name the pack uses no longer passes.
- **The check happens again as the game starts.** It used to run before the sign-in, minutes before the process existed; a pack changed in between is now caught.
- **Nothing rides in on the side.** Settings that point at outside code, variables inherited from the desktop session, and a runtime that is not a real program are all refused on a launch that signs into a server.
- **A modified pack says so.** The launch stops with a message instead of starting a game that cannot join.
- **The SmartyCraft server list is on its way out.** It is deprecated in this release and goes away in 2.5.0; packs replace it.

## [2.4.0-beta3] - 2026-08-02

2.4.0-beta3 makes two-factor accounts playable and closes the ways a mod could ride into a pack uninvited. Signing in with a second factor now works end to end: the code is asked once when you press Play, and the game starts on a session minted for that launch. On the content side, a pack is held to its own file list before every spawn, files that refuse to be deleted are treated as the obstruction they are, and the sweep touches only what a loader would execute -- caches, configs and the launcher's own bookkeeping are left alone.

### Highlights
- **Two-factor accounts can play.** Sign-in with a code works, and it is asked once per launch instead of over and over. Every background re-login that used to invalidate your session behind your back is gone.
- **A pack launches as the pack.** Jars added to an installed pack are removed before the game starts, and a file that cannot be removed means the launch goes ahead without a sign-in rather than pretending everything is fine.
- **Only mods are swept.** Mod caches, configs and leftovers are no longer deleted along with them -- previously a launch could wipe a loader's remapped-jar cache and cost you a full rebuild.
- **Packs install again where a platform library is not shipped.** A pack whose loader lists a macOS-only library no longer fails to install on Windows and Linux.

## [2.4.0-beta2] - 2026-08-02

2.4.0-beta2 closes the gap between what a pack says it is and what actually launches. An instance is held to the list of files the pack consists of before every spawn, not only when it syncs, so a jar added by hand between two syncs no longer rides along. A launch also carries only a session it earned: offline, an unverified instance, and a refresh that could not go through all start the game with the token stripped. Alongside that, a failed pre-spawn refresh finally says so instead of surfacing as Minecraft's own "Failed to verify username", and the packaged runtime stops reporting a class-data archive mismatch at error level on every launch.

### Highlights
- **A pack launches as the pack.** Files added to an installed pack's `mods/` are removed before the game starts, and the launcher names what it removed. Only what the pack itself declares loads.
- **Your token stays out of launches that did not earn it.** An offline launch, an instance the launcher could not vouch for, and a launch that could not reach the login server all start the game without a session token.
- **The launcher tells you when your session is stale.** Instead of the game refusing your server join with "Failed to verify username", the launcher says up front that it could not refresh the session and what to do about it.
- **A quieter, lighter start.** The packaged runtime no longer prints class-data archive errors at every launch, and a stale archive after an update no longer silently disables class sharing.

## [2.4.0-beta] - 2026-07-30

2.4.0-beta continues the preview and rebuilds what the launcher does with the network. Every file it pulls onto disk -- a runtime, a JDK, a pack, a mod, a loader installer, its own update -- moves onto one transfer engine that retries, resumes from where the connection broke and falls back to a mirror, and a verified file keeps a block map so a damaged pack is repaired by its damaged blocks instead of fetched again. A pack's builds get their own screen with a per-build changelog, switching and rollback, and pack updates announce themselves in the notification center. Nightly builds and a Pre-releases toggle replace the update-manager window. A hardening pass keeps session tokens out of logs and diagnostic bundles, scopes a certificate bypass to the host it was granted for, and bounds every path and archive a server document gets to choose. ProGuard is gone and the Linux AppImage drops to ~74 MB.

### Highlights
- **This is a beta.** It carries everything since the 2.4.0 preview -- please keep reporting anything broken on the issue tracker.
- **Downloads that survive a bad connection.** Every download in the launcher now retries and picks up where it stopped instead of starting over, splits big files into pieces that are fetched in parallel, and falls back to another mirror -- so a reset in the middle of a 200 MB runtime or a 300 MB resource pack costs seconds, not the whole transfer.
- **Repair a pack instead of re-downloading it.** Pack settings gains Verify and repair: it checks the installed files against the build the pack is pinned to and fetches back only the damaged parts of the damaged files. Your own jars, your disabled optional mods and your edited configs are left alone.
- **Every build of a pack, with its changelog.** A versions screen lists the mirror's retained builds and shows what each one adds, updates and removes, so you can switch to a specific build or roll back with the change in front of you.
- **Updates tell you about themselves.** A new pack build raises a notification and the Library card carries a clickable update pill, instead of the update happening silently or not at all.
- **SmartyCraft servers on modern Minecraft.** Joining an SC-bound pack's server on a modern version works, and other players' skins render instead of falling back to the default.
- **A lighter launcher.** The Linux AppImage drops from ~95.6 MB to ~74 MB, a custom wallpaper is cached at your display's size instead of being re-decoded at full resolution every start, and the Content tab stops re-cracking every jar on each open.
- **Your session token stays out of the logs.** The token no longer reaches `game.log`, the crash report and the diagnostic bundle are redacted where they are written, and the credential file is created readable only by you.

## [2.4.0-preview] - 2026-07-14

2.4.0 opens the launcher onto the wider modding world. A new Browse tab searches and installs Modrinth modpacks, imports a `.mrpack` / a CurseForge zip / a foreign launcher's instance, or builds a pack from scratch; a Wardrobe manages your skins and capes over a reworked 3D character stack; the launcher can follow your desktop's colour scheme; and a boot screen plus a recovery mode carry a start that goes wrong. Underneath, the whole interface moves onto a single `:nx-ui` design system, the launch engine splits into headless modules with a native CLI, and the build moves to Java 26. Microsoft / multi-account infrastructure lands but stays gated off pending a later release.

### Highlights
- **This is a preview**. 2.4.0 is a large, fast-moving release shipped early as a preview -- expect rough edges, and please report anything broken on the issue tracker.
- **Browse and install modpacks**. A new Browse tab searches Modrinth's modpacks, renders their descriptions inside the launcher, and installs one in a click -- and you can import a `.mrpack` or a CurseForge zip, or start an empty pack from scratch.
- **A wardrobe for your skins**. A new Wardrobe keeps your skins as small 3D figures, applies one to SmartyCraft, picks a cape, or starts from the game's default set -- your character's look in one place.
- **The launcher follows your desktop**. It can track your system's light / dark scheme on its own and tune its theme to your wallpaper's brightness, with a new appearance studio gathering the background and look controls.
- **A boot screen and a recovery mode**. A quick boot screen shows while the launcher starts; if something goes wrong, hold Shift (or pass `--recovery`) to disable a misbehaving part or reset it -- no reinstall.
- **One consistent interface**. The whole UI moved onto a single design system -- surfaces, buttons, menus and settings sections share the same shapes, spacing and icons, and stay legible with or without a wallpaper.
- **Packs show what they're doing**. A pack card now carries a live launch state (preparing / downloading / running), and a partial import says which mods still need a manual download instead of looking like an empty pack.

## [2.3.4] - 2026-06-15

The customization release, consolidated. 2.3.4 makes the entire interface editable -- and editable surfaces now carry their own settings -- rebuilds the Profile around a live 3D render of your skin, ships an in-app update manager with channels and rollback, gives notifications a persistent home both in-app and on the desktop, teaches the UI to fit narrow windows, and bundles the launcher's own type. Underneath: SmartyCraft modpacks join their servers without shipping anything of SmartyCraft's, an installed pack relaunches offline, memory sizes itself, and a deep robustness and refactor pass keeps a corrupt file or a stray widget from taking the launcher down. It rolls up the 2.3.4-beta .. beta5 line and everything since.

### Highlights
- **Make the launcher yours**. Press Ctrl+E to edit. Drag, resize, restyle, and free-place every widget across the home, library, side rails, and the app shell itself, with per-widget glass backing and save / load / export of layout presets. Surfaces now carry their own settings too -- the left rail's selection style lives in its editor panel, not a global menu.
- **Your skin in 3D**. The Profile leads with a live, rotatable 3D render of your skin, drawn from scratch with no extra dependency, and sign-in lives inside the Profile, reachable while logged out.
- **An update manager with channels**. The "i" by the version opens a manager: pick Release / Beta / Alpha (plus Dev / Git source builds), update or roll back to a recent version, and install a desktop shortcut. The About screen also checks on its own every few minutes.
- **Notifications you can keep**. A placeable message-history widget groups repeats, swipes to dismiss, and mutes on do-not-disturb -- and, new this release, the launcher posts a real desktop notification when it slips into the tray so it does not read as a crash.
- **The launcher fits narrow windows**. Rails collapse by a swipe, the server list pages into pills, and the About screen stacks its columns instead of clipping.
- **SmartyCraft modpacks join their servers**. A pack from the mirror that targets a SmartyCraft server connects and joins, and other players' custom skins load -- without shipping anything of SmartyCraft's.
- **Offline relaunch**. An already-installed pack starts with the network off; a warm relaunch makes no network requests at all.
- **Adaptive memory**. A non-pinned instance sizes its heap from your real RAM and refines it over a few sessions; pin a value to opt one out.
- **The launcher's own type**. Google Sans Flex and JetBrains Mono ship inside the app, so the interface looks the same on every machine instead of borrowing the host's fonts.
- **A launcher that does not fall over**. A corrupt world or server file, a widget whose kind left the registry, or a crashed surface no longer takes the whole launcher down.

## [2.3.4-beta5] - 2026-06-09

A profile-and-updates release. The Profile is rebuilt around a live 3D
render of your skin, with sign-in moved inside it and reachable while
logged out. A new in-app update manager adds release channels, rollback,
a desktop-shortcut install, and -- for developers -- building the launcher
from source. Underneath: the launcher no longer dies on a corrupt world
file, auth is carved into its own modules, and the AppImage gains a
cross-distro release gate.

### Highlights
- **Your skin in 3D**. The Profile's account tab leads with a live, rotatable 3D render of your skin, drawn from scratch with no extra dependency.
- **Sign in from the Profile**. The login form lives in the Profile and is reachable while logged out; the cramped right-rail login is gone.
- **An update manager with channels**. The "i" by the version opens a manager: pick a channel (Release / Beta / Alpha, plus Dev / Git source builds), update or roll back to a recent version, and install a desktop shortcut.
- **Background update checks**. The About screen checks for updates on its own every few minutes and tints the running version by its channel.
- **A corrupt world or server file no longer crashes the launcher**. A malformed NBT length used to take the whole launcher down on scan.

## [2.3.4-beta4] - 2026-06-07

The SmartyCraft-pack release. A modpack that targets a SmartyCraft
server now connects and joins -- other players' skins included --
without shipping anything of SmartyCraft's. Alongside that: offline
relaunch of an installed pack, dependency-aware optional mods, a console
that no longer freezes under a log flood, and adaptive-memory fixes.

### Highlights
- **SmartyCraft modpacks join their servers**. A pack from the mirror that
  targets a SmartyCraft server now connects and joins, and other players'
  custom skins load.
- **Offline relaunch**. An already-installed pack starts with the network off;
  a warm relaunch makes no network requests at all.
- **Optional mods follow their dependencies**. Enabling an optional mod also
  enables the shared libraries it needs, and switching one mod in an
  interchangeable group (e.g. a recipe viewer) swaps the other out.
- **A console that keeps up**. A heavy mod-load log flood no longer freezes or
  crashes the launcher window.
- **Adaptive memory reads your real RAM**. The installed build now detects the
  host's RAM correctly, and adaptive sizing works under ZGC and Shenandoah.

## [2.3.4-beta3] - 2026-06-04

Matures the adaptive memory from 2.3.4-beta2 into a three-tier model
(Fixed / Automatic / Adaptive) and cleans up the release notes.

### Highlights
- **Automatic memory baseline**. A non-pinned instance now sizes its heap from
  your machine's RAM (a sane share, capped) instead of a fixed default, so it
  stops over-allocating on a small machine. The adaptive sizer refines this
  baseline over a few sessions.
- **Adaptive governs every instance**. The global Adaptive memory toggle now
  applies to every instance, not just freshly-created ones. Pin a specific RAM
  value to opt one out; turn the toggle off to keep the automatic baseline
  without learning.
- **See and set the mode**. The RAM selector shows an "Auto" chip with the heap
  it currently resolves to, and pack instances get their own Settings tab for RAM.

## [2.3.4-beta2] - 2026-06-03

Adds the experimental adaptive memory sizer on top of 2.3.4-beta.

### Highlights
- **Adaptive memory (experimental)**. New instances measure their real heap use
  while you play and right-size `-Xmx` over the next few launches, so a pack runs
  smoother without hand-tuning RAM. On by default under the experimental settings;
  pick a specific RAM value to opt that instance out.

## [2.3.4-beta] - 2026-06-03

The customization release. The launcher's whole interface becomes
editable: an in-app edit mode lets you rearrange, resize, and restyle
every widget -- including the app shell itself -- and save the result as
a preset. The UI also learns to recover: a crash reloads the shell
instead of leaving a dead window. Alongside that: pack browsing and
install from the mirror, a reworked console, multi-loader runtime
support, and Russian / English / German across the interface.

### Highlights
- **Make the launcher yours**. Press Ctrl+E to edit. Drag, resize, and
  rearrange every widget across the home, library, side rails, and the
  app shell itself. A free-placement Canvas mode, per-widget glass
  backing (corner / padding / opacity), and save / load / export of
  layout presets.
- **A UI that recovers instead of dying**. If the interface crashes, the
  launcher reloads its shell on the fly; a repeated crash falls back to a
  minimal quit-only safe screen rather than a frozen window.
- **Browse and install packs from the mirror**. A catalogue page with a
  pack detail view (Content / Files / Worlds), optional-mod toggles, and
  dependency-aware grouping.
- **A calmer console**. Quiet by default, themed to the active palette,
  and available as a file-backed Logs tab on each pack.
- **Smarty servers, without the spyware**. A Settings -> Smarty section
  swaps SmartyCraft's proprietary Smarty mod for an open-source helper:
  same network compatibility, none of the client-side surveillance. If no
  open replacement exists for a server's game version, the launch is
  blocked rather than quietly running the original mod.

## [2.3.3] - 2026-05-25

Visual customization release. Custom background gains real
animated-format support (GIF / APNG / animated WebP) with
playback controls. A new experimental Customization screen
exposes density, glass intensity, accent override, and a full
per-role color override matrix on top of the active theme.

### Highlights
- **Animated wallpapers**. Pick a GIF, APNG, or animated WebP as
  your custom background and it actually animates. Frame 0 shows
  immediately on cold load instead of grey while the remaining
  frames decode. New Animation speed slider (0.25 - 4x, live
  during playback) and Loop mode picker (Use codec / Loop forever
  / Play once -- the last one freezes on the last frame for
  intro-and-settle patterns).
- **Customization (experimental)**. New Settings entry exposes
  density scale (0.85 - 1.15x, all `.dp` values), glass density
  (0 - 100%, every glass surface in the launcher), accent color
  override (free hex), and a full 7-role color override matrix
  behind an experimental toggle.
- **Glass density reaches every glass surface**. Sidebar, right
  panel, dividers, every card and tile across every screen
  respect the slider, not just the few screens it was first
  wired into.
- **GlassCard finally honours palette.glassAlpha**. The Glass
  branch had been hardcoding `alpha = 0.7f` and ignoring the
  palette's own field (0.60 dark, 0.65 light). Now reads the
  palette correctly.

## [2.3.2] - 2026-05-24

Same-day patch on 2.3.1. Three user-facing bugs surfaced within
hours of the 2.3.1 release; this rolls them up.

### Highlights
- **Java 21 downloads work again** for users on CloudFlare WARP and
  similar VPNs. The launcher now adds a real-browser User-Agent on
  the JDK fetch and falls back to Adoptium / GitHub releases when
  BellSoft's CloudFlare CDN refuses the request. Affects launching
  Create and any other 1.21.x pack.
- **"Move data directory" button works** again on every platform.
  The release-build shrinker was culling FileKit internals reached
  only from that one call site; a `keep` rule restores the affected
  overload and a localized error line now surfaces on the rare
  picker failure instead of a silent dead button.
- **April Fools debug panel auto-scrolls into view** when unlocked
  by the 5-tap Diagnostics title gesture. Previously the panel did
  open but rendered below the visible scroll area on most window
  sizes, which read as "the click did nothing but jiggle the list".

## [2.3.1] - 2026-05-24

Maintenance release focused on the Windows 11 installer bug that
broke OneDrive users, plus a sweep of UI polish on the Style
variant infrastructure that landed in 2.3.0's wake.

### Highlights
- **Windows 11 installer no longer breaks under OneDrive.** New
  installs land in `%LocalAppData%\Nexira\Programs\` instead of the
  OneDrive-synced Roaming tree, so `jvm.dll` stays materialised on
  disk and the "Invalid Image" crash no longer triggers. Existing
  Roaming installs are uninstalled silently when you run this
  installer over them.
- **Style variant infrastructure.** Settings now offers Celestia
  (rounded, glassy, motion-rich) and Brut (sharp, flat, no glow,
  no motion) as live-switchable UI looks. No restart required.
- **Two-column Settings and Profile.** Vertical category navigation
  on the left, selected section's form on the right. Form state
  persists when you switch categories.
- **Portable build ships a README** inside the `Nexira\` folder
  explaining that `Nexira.exe`, `app\`, and `runtime\` must stay
  together. Bilingual (EN / RU). Heads off the very common "copy
  just the EXE to Desktop" mistake.

## [2.3.0] - 2026-05-20

Rebrand release. The launcher is now called **Nexira**; the underlying
service it targets (SMARTYcraft) is unchanged, and so is the wire
protocol, the auth flow, and the file-sync semantics. Existing Aura
data is preserved through a mandatory migration UI on first Nexira
launch.

### Highlights
- **Aura is now Nexira.** Window title, executable, install dir, data
  dir, AppStream id, .desktop entry, GitHub repo and docs URL all
  change. SmartyCraft compatibility is byte-identical to 2.2.16.
- **Mandatory data migration on first launch.** When Nexira detects an
  Aura-era data directory it shows a full-screen modal with size /
  file count and a single "Migrate now" button. The copy runs with a
  determinate progress bar; on completion the launcher asks for a
  restart. The old Aura folder is left in place as a backup -- delete
  manually once you've confirmed Nexira loads your settings.
- **2FA accounts now cleanly rejected.** SMARTYcraft's two-factor
  flow is not part of the documented protocol and was never working
  reliably here. Nexira surfaces the limitation up-front instead of
  failing at game launch.

## [2.2.16] - 2026-05-19

Bug-fix + size-cut release. The headline is a UI-freeze fix that bit
every user on every "Play" click — the tray library was making
blocking D-Bus calls on the EDT during launch state transitions,
holding up the whole window for seconds at a time. Alongside that,
the distribution size drops noticeably (~10% on AppImage / DMG,
similar on the Windows installer) thanks to a custom jlink + jpackage
pipeline that finally lands the flags (`--vm=server`, `--strip-debug`,
`--include-locales=en,ru,de`) that Compose Desktop's built-in
`nativeDistributions` block never exposed. Internal: the build
toolchain swaps from JetBrains Runtime to BellSoft Liberica, and the
packaging infrastructure moves into a `buildSrc/` convention plugin
so the AppImage shell script and the Windows / macOS jpackage path
share one configuration source.

### Highlights
- **No more freeze on Play click** — clicking "Play" used to lock the
  launcher window for several seconds while the system-tray library
  made blocking D-Bus calls on the UI thread. Tray status updates are
  now off the EDT entirely; the window stays responsive through the
  whole launch flow. Affects every Linux user under
  KDE / Hyprland / GNOME / Cinnamon.
- **Smaller download across every platform.** The custom jlink runtime
  drops the unused HotSpot VM variants (client + minimal, ~22 MB),
  trims three unused JDK modules (`java.sql`, `java.naming`,
  `java.net.http`), restricts locale data to en/ru/de, and strips
  debug info. Compared to 2.2.15: AppImage and DMG drop by ~10 MB,
  the Windows installer by a similar amount. Inner jlink compression
  intentionally not applied; outer LZMA (Inno Setup) and squashfs-zstd
  (AppImage) compress a raw runtime image more tightly than they can
  a pre-compressed one (measured locally: a zip-9 inner pass costs
  8 MB on the AppImage path and ~13 MB on the LZMA path).
- **/diag endpoints for puppet** (developer-facing) — the puppet HTTP
  control surface gains read-only diagnostic endpoints
  (`/diag/threads`, `/diag/jvm`, `/diag/actions`, `/diag/snapshot`)
  for automated profiling and freeze diagnosis. Off
  `Dispatchers.Swing` by design so they do not perturb what they
  measure. Available only in puppet builds (`-PauraPuppetPort=N`).

## [2.2.15] - 2026-05-18

Network plumbing + UI responsiveness release. The "Force proxy mode"
toggle from 2.2.13 finally takes effect across every smartycraft.ru
request, not just the auth handshake -- skins, news images and pack
syncs now honour the user's choice and react to it without a
relaunch. Several "the launcher froze when I clicked X" reports trace
back to native `Desktop.open` / `Desktop.browse` calls running on the
Compose UI thread; every such call now dispatches to a daemon thread
so a wedged `xdg-desktop-portal` D-Bus can no longer hold up the
window. The integrity walk that hashes every file in a modpack now
emits progress while it works, so a cold launch on a 1000-file pack
no longer looks frozen for tens of seconds. Plus an architectural
sweep -- `LauncherController` moves to the right module, the chaos
subsystem hides behind a CompositionLocal, several long-standing
god-files and singleton patterns get cleaned up.

### Highlights
- **"Force proxy mode" actually does what it says** -- pre-fix the
  toggle only affected the auth handshake. Skins, news images and
  pack-file downloads stayed pinned to the SOCKS proxy regardless of
  the setting, so users in networks where `proxy.smartycraft.ru:58613`
  is unreachable saw login work but everything else silently fail.
  Every smartycraft.ru request now reads the toggle freshly per call;
  flipping it in Settings takes effect on the next request without a
  restart.
- **News strip can retry after network recovery** -- the news feed
  used to fetch exactly once on startup; a single failure stuck it
  in the empty state for the rest of the session. The empty state
  now has a Retry button that bypasses the in-memory cache, and the
  feed re-fetches automatically on a force-proxy or SSL-bypass
  toggle.
- **Click handlers don't freeze the launcher anymore** -- `Desktop.open`
  and `Desktop.browse` (Open folder, View on GitHub, Report on GitHub,
  news links, register button, ...) now dispatch to a daemon thread.
  A stuck `xdg-desktop-portal` D-Bus or misconfigured `xdg-open`
  on Linux can no longer hold up the EDT.
- **Integrity walk shows progress** -- when the launcher hashes every
  file in a 1000-file modpack before deciding what to download, the
  progress bar now advances visibly through that phase instead of
  freezing at 20% for tens of seconds.
- **Custom upstream version pin** -- new opt-in field in Settings
  (Experimental section) lets users override the version string the
  launcher sends to the upstream handshake, in case the upstream pins
  a newer version before the next Aura release ships. Persisted across
  restarts; applied without one.

## [2.2.13] - 2026-05-15

Security, concurrency, and platform-completion release. The auto-updater
now refuses to install bytes it cannot integrity-verify against a manifest
hash. The mod-pack file sync gained a sample-existence sanity gate that
catches the case where the manifest cache says "synced" but the actual
files are gone (the bug behind the empty-classpath crash on cold launches).
Two-factor authentication finally works in the launcher itself instead of
locking out 2FA-enabled accounts. macOS Apple Silicon joins Linux and
Windows on the OS-keyring path. Conduit pillar — the network-layer
refactor — is feature-complete; the `Network.BASE_URL` constant is no
longer reached by production code.

### Highlights
- **TOTP 2FA login** — accounts with two-factor authentication enabled
  in SmartyCraft can now sign in directly from Aura. A 6-digit code
  prompt appears after the password step; wrong codes re-prompt
  inline; expired sessions surface a clear "log in again" message
  instead of getting stuck in a verify loop. Russian / English /
  German strings shipped.
- **Auto-update refuses unverified installers** — every released asset
  now requires a SHA-256 entry in `release-manifest.json` before the
  launcher will install it. The pre-fix path silently treated an empty
  hash as success, which would have driven arbitrary bytes through the
  updater if anyone had edited a release page out from under it. Older
  releases that pre-date the manifest convention require manual
  reinstall — the auto-updater will refuse them rather than guess.
- **Cold-launch reliability fixes** — three classes of "click Play,
  game dies" reports addressed: (a) the manifest cache no longer lies
  when the client directory has been wiped between syncs (e.g. after a
  data-dir move or manual `rm`); (b) the natives-folder validity gate
  now requires the actual `lwjgl` library, not just any `.so` file
  (jinput-only directories used to pass and crash the game with
  `UnsatisfiedLinkError`); (c) `mods/*.jar` files are spot-checked
  for ZIP integrity even when their MD5 matches, catching the rare
  corrupt-bytes-with-correct-hash case that NeoForge would otherwise
  surface as "invalid CEN header" mid-launch.
- **macOS keyring (Apple Silicon)** — the third platform on the OS
  keyring path, after Linux libsecret and Windows DPAPI. Passwords
  and access tokens land in the user's login Keychain via the modern
  `SecItem*` API (Project Panama bindings to Security.framework).
  Falls back to the per-machine AES-GCM file when Keychain isn't
  reachable, identical to the Linux/Windows flow.
- **macOS Intel as community-tier** — Apple Silicon stays tier-1 (built
  on every release tag); Intel macOS now ships asynchronously via a
  manual `workflow_dispatch` build and is named
  `*-x86_64-community.dmg` so the support shape is obvious from the
  filename. The README has a new "Platform support tiers" section
  spelling out what tier-1 vs community means.

## [2.2.12] - 2026-05-14

Security and platform-completion release. Passwords and login tokens now
live in your OS keyring instead of an AES-GCM file (Vault — Linux libsecret
and Windows Credential Manager wired up; macOS pending). The "accept SSL
warning" flow stops being all-or-nothing: each bypass is per-host and
expires. Bridge pillar gets the missing UI for moving the data directory
without env-var hackery. macOS finally ships proper dual-architecture
DMGs with a real app icon. Plus a continuous AppImage portability check
in CI to catch "works on my distro" regressions before users do.

### Highlights
- **OS keyring integration** (Vault): launcher credentials now persist
  to GNOME Keyring / KWallet via libsecret on Linux and to Credential
  Manager (DPAPI) on Windows. Falls back to a per-machine AES-GCM file
  if no keyring is reachable, so nothing breaks on minimal desktops or
  headless installs. Both the password and the access token are
  protected — previously only the password was. macOS keyring impl is
  the next chunk.
- **Per-host SSL bypass with expiry**: when you accept a certificate
  warning, the bypass is now scoped to that host and ends when you say
  it does — session-only by default, with optional 1 hour / 1 day /
  7 days. Previous behaviour granted "trust every HTTPS call this
  process makes" until the launcher restarted. Settings → Network lists
  every active bypass with a Revoke button.
- **Move data directory** without touching `AURA_DATA_DIR`. Settings →
  Data directory → pick a new location → "Quit now" or schedule for
  next launch. The picker uses your desktop's native dialog
  (xdg-desktop-portal on Linux, AppKit on macOS, Win32 on Windows)
  instead of the Swing JFileChooser that looked broken on Hyprland and
  several KDE themes.
- **macOS dual-architecture DMGs** — separate builds for Apple Silicon
  (`*-aarch64.dmg`) and Intel (`*-x86_64.dmg`). Auto-updater now reads
  `os.arch` and downloads the correct one; previously the first DMG
  asset wins, which on a dual-arch release produced a 50/50 wrong-arch
  install. Plus a proper `.icns` app icon — the default Compose K-folder
  placeholder is gone.
- **Daily AppImage portability check**: a CI matrix downloads the latest
  released AppImage on Fedora / Arch / Debian-stable containers and
  verifies the app actually starts. Catches glibc / GTK / Skiko-loader
  regressions on distros the maintainer doesn't run day-to-day.

## [2.2.11] - 2026-05-12

Infrastructure-heavy release focused on debuggability when something goes
wrong: a proper logging pipeline (Pulse), a one-click diagnostic bundle
(Beacon), the actual fix for the KDE/GNOME tray hover-title bug, and a
better unauthenticated dashboard state. Plus three audit-driven fixes
that catch regressions before users see them.

### Highlights
- **Centralised logging pipeline** (Pulse): launcher now writes
  structured rolling log files to the platform-correct data directory
  — `launcher.log`, `network.log`, `game.log` and `crash.log`, each
  with size + age caps. Game stdout/stderr persists automatically (no
  more "I forgot to save the console before the crash"). Crash forensics
  survive 30 days in `crash.log` even when active logs roll faster.
- **Per-launch tagging in logs**: every line carries
  `[sessionId/launchId]` — shipping a 200 MB log dump for support and
  needing only the last Play attempt? `grep launchId=abcd1234 *.log`
  slices to that one launch.
- **Token / password / UUID redaction** before any log line hits disk
  or the in-app console — screenshots and copy-pastes from the console
  for support are safe to share without manually scrubbing the
  `accessToken=...` lines.
- **One-click diagnostic bundle** (Beacon): Settings → Diagnostics →
  "Create diagnostic bundle" → ZIP with system info, the action history
  ring, all redacted log files, and every crash report — open the
  containing folder so you can attach the file to a support message
  in one motion.
- **"Report on GitHub" buttons** on the crash dialog and next to the
  diagnostic-bundle button — opens a browser at a pre-filled
  `github.com/issues/new` URL with the crash report (or a body asking
  you to drag-attach the bundle ZIP) already in the editor. Nothing
  leaves your machine until you review and click Submit on github.com;
  the launcher itself never POSTs anything. Designed as the principled
  alternative to telemetry — convenient for both sides without a
  phone-home codepath in the binary.
- **Action history ring buffer** behind the scenes: the last 64
  user/lifecycle events with timestamps. Replaces the old
  `lastAction = "..."` (one global string, only ever the most recent
  thing). Crash reports now include the full trail leading up to the
  crash, not just the last entry.
- **KDE/GNOME tray hover now actually says "Aura Launcher"** instead
  of "SystemTray". The previous tooltip-removal in 2.2.10 didn't fix
  the underlying cause — AppIndicator's hover text comes from the
  constructor argument to `SystemTray.get()`, not from `setTooltip()`.
- **Sign-in screen no longer shows a vacant spinning indicator**:
  when the launcher is waiting on user login, the main panel now
  shows an explicit "Sign in to see servers" message with a hint
  pointing at the right-side login form. Previously, both the brief
  startup-loading state AND the stable unauthenticated state rendered
  the same tiny spinner, making it look like servers were forever
  trying to load.

## [2.2.10] - 2026-05-12

UX polish chunk anchored on the new visual JVM Args Builder — a Compose
dialog for picking GC algorithm and tuning flags so users no longer
have to hand-type Aikar's recipe to get smooth modded MC. Plus the
usual round of stability fixes, a saner default heap size, full
Console-window localisation that was previously hardcoded English,
and a new gothic dark-red theme.

### Highlights
- **Visual JVM Args Builder** (experimental opt-in): pick GC (G1 / ZGC
  / Shenandoah / ParallelGC / SerialGC), tune G1 region size and pause
  targets via sliders, enable AppCDS or JFR profiling — all without
  memorising `-XX:+UnlockExperimentalVMOptions`. Six curated presets
  cover Aikar's flags (canonical modded MC), Heavy modded (GTNH-class),
  Vanilla G1 (stock baseline), ZGC and Shenandoah for huge heaps, plus
  ParallelGC throughput. Live preview at the bottom shows the composed
  arg string. Enable under Settings → Experimental features.
- **Auto-sync installed packs on launch** (experimental opt-in): the
  launcher quietly refreshes every server pack you've already installed
  at startup. Useful if you hop between multiple servers and want
  fresh state without clicking each one. Sequential to avoid bandwidth
  contention. Cheap when nothing changed — the 2.2.9 manifest cache
  short-circuits the integrity walk.
- **NeoForge `--fml.*` args auto-detect**: launcher now reads the
  required NeoForge / FML / NeoForm version values directly from the
  populated `libraries-{mc}/` directory and the universal jar's
  manifest. Removes the recurring "smrt-deco bumped, Aura's hardcoded
  version doesn't match, NeoForge fails to register the `neoforge`
  mod and every dependent mod shows `[MISSING]`" failure mode. Baked-
  in values stay as a safety-net fallback.
- **Default heap bumped 4 → 6 GB** for new per-server profiles: 4 GB
  was borderline tight for the SmartyCraft modpack class (50-70 mods).
  RamSelector still caps choices at 75 % of detected system RAM, so
  the default scales down gracefully on low-RAM machines.
- **Blood Rain theme**: first warm-dark gothic option in the theme
  picker. All accents stay inside the dark-red family (no cool
  counterpoint) for a "blood rain on a moonless night" mood. Sits
  opposite the existing cool-electric presets (Cyberpunk / Vaporwave
  / Synthwave / Neon Dreams).
- **Console window fully localised** (EN / RU / DE): window title,
  filter labels, action tooltips, search placeholder, jump-to-bottom
  button — all previously hardcoded English. Three i18n keys
  (`consoleTitle`, `consoleCopyAll`, `consoleClear`) had existed in
  `AppStrings` since an earlier refactor but were never wired to the
  screen; fixed alongside the new keys.
- **RAM custom-value field no longer clips its placeholder**: the
  `OutlinedTextField` had been forced to 48 dp height, below the
  Material3 default ~56 dp the placeholder layout assumes. The
  placeholder digit appeared to "fall through" the bottom border.
- **Server settings bottom buttons unified**: Open Folder, Reset
  Client, and Return to Spawn now all render in the same outlined
  Celestia style. Open Folder and Spawn Reset were previously
  `AprilFoolsButton` with a transparent-container hack that made them
  read as floating text instead of buttons.
- **Tray init race fix**: the close-request callback treats a close
  as "minimise" while the tray subsystem is still initialising.
  Previously, on systems where dorkbox/SystemTray takes up to a
  minute to fall back to the GTK status icon, the launcher could
  exit before the tray ever appeared — silently, with no error.
- **Offline launches now rebuild the classpath**: per-server
  `ManifestCache` persists the full manifest content alongside its
  hash, so `LauncherController`'s offline branch has the data it
  needs. Previously the cache stored only the hash and offline mode
  produced an empty classpath that failed with a confusing
  class-not-found error.

## [2.2.9] - 2026-05-10

Stability sweep — four user-visible reliability fixes that ride on the
infrastructure shipped in 2.2.8. Targeted at the failure classes observed
in production logs: mid-stream HTTP/2 resets on the SOCKS-proxied
SMARTYcraft channel, downloads restarting from byte 0 on every flake,
duplicate auth requests on the dashboard → Play flow, and the
single-instance gate failing to actually raise the existing window on
KDE / Hyprland / GNOME.

### Highlights
- **Cold-start much faster after a clean session**: when the server
  manifest hasn't changed since the last successful sync (TTL 7 days),
  the launcher skips the per-file MD5 integrity walk. On a 1000-file
  modpack this collapses multi-second checks into a single hash compare.
- **Orphan files now actually leave**: when the upstream modpack
  removes a mod, the corresponding local file is pruned on next sync
  (was: lingered forever, often causing mismatch crashes on join).
- **User-extendable protected-paths list**: drop a mod into
  `dataDir/protected-paths.json` and the launcher will never overwrite
  configs under that directory, even when the manifest says they're
  stale. Defaults shipped with the file on first run.
- **SMARTYcraft channel pinned to HTTP/1.1**: h2 multiplexing over the
  upstream SOCKS proxy was dropping mid-stream on long bodies. 1.1 with
  parallel connections trades multiplexing for resilience. Direct channel
  (GitHub releases, BellSoft JDKs, Maven Central) is unaffected.
- **Auth and downloads now retry on transient resets** (3 attempts, 1 s /
  3 s / 9 s backoff). Auth-rejection responses and SSL cert errors are
  explicitly *not* retried — those need user attention, not a silent loop.
- **Downloads resume via `Range:`** instead of restarting from byte 0.
  A 100 MB asset that drops at 70 % now costs seconds to recover instead
  of restarting the whole transfer.
- **Per-server session cache** in `AuthService`: dashboard list refresh
  and the actual server-launch auth used to fire two back-to-back logins
  for the same server. The second one now returns the 30-second-cached
  session without hitting the network — fewer requests, fewer chances to
  trip the upstream's "sessions don't dedup" race.
- **Single-instance gate raises the existing window**: second-launch
  attempts previously only flipped `visible = true`, leaving the window
  minimised or buried under other windows on KDE / Hyprland / GNOME.
  Now un-minimises and pulses `isAlwaysOnTop` to force a true raise.
  Lock file also stores the holder PID for diagnostics
  (`cat ~/.local/share/aura-launcher/.lock`).

## [2.2.8] - 2026-05-10

Update Channels chunk — gives the launcher two new tools for surviving the
upstream cadence: a server-controlled mandatory-update floor (so the launcher
refuses to start when the protocol breaks compat with installed builds), and
an opt-in pre-release channel (so RC builds reach users before the next
stable cut). Both gated by a master "Experimental features" toggle. Shipped
as a non-prerelease so existing 2.2.7-rc3 users actually receive it — older
launchers ignore prereleases by GitHub API contract.

### Highlights
- **Mandatory updates**: launcher refuses to start when the installed version
  drops below `mandatory_min_version` published in `meta/update-channel.json`.
  No new server infra — the file lives on the `stable` branch and is updated
  via PR. Triggers a non-dismissable dialog with "Install" or "Quit".
- **Pre-release update channel**: opt in to receive RC and beta builds before
  the next stable. Currently ON by default while the upstream protocol is a
  moving target; expected to flip to OFF once cadence stabilises.
- **Experimental features master toggle** in Settings — gates both knobs
  above with a single switch for users who want a calm upgrade story.
- **Near-real-time mandatory rollouts**: a long-running launcher session
  polls `update-channel.json` every 5 minutes (cheap, no GitHub API quota),
  so when an emergency upgrade is published the user sees the blocking
  dialog within ~5 minutes — no need to restart the launcher to pick it up.
  Routine release checks stay on the existing 12 h cadence.
- Strict version comparison in the update flow: `1.3.0 > 1.3.0-rc3`,
  `rc1 < rc2 < rc3`, `alpha < beta < rc`. Without this the prerelease channel
  would consider RC bumps within the same base "the same version".

## [2.2.7-rc3] - 2026-05-10

Release candidate for [2.2.7], superseding rc2 with the freshly-rotated
upstream version pin (smrt-deco 3.6.5, pushed 2026-05-10) and a runtime
knob to ride out the *next* upstream rotation without waiting for a
launcher release. CI internals also got a couple of paper-cut fixes —
metainfo injection now uses `xmlstarlet` instead of regex-on-XML, and
the AppImage assembly bash moved from inline yaml into a shell script.

### Highlights
- Mimicked launcher version bumped to **SMARTYcraft 3.6.5** (rc2 was 3.6.4).
  No protocol bytes changed beyond the version string; proxy creds, AES
  params and salt are all unchanged.
- New **experimental override** for the mimicked version: pass
  `-Dsmrt.mimic.version=X.Y.Z` on the JVM command line to claim a different
  launcher version without rebuilding. Useful when upstream rotates the
  pin and a launcher update has not shipped yet.

## [2.2.7-rc2] - 2026-05-07

Release candidate for [2.2.7]. Same code; canary tag for catching install
regressions on Windows / macOS / Linux before the public bump. (rc1 failed
on Inno Setup `VersionInfoVersion` strict-version validation; fixed by
stripping the pre-release suffix in setup.iss the same way build.gradle.kts
already does for Compose's `packageVersion`.)

### Highlights
- **Required upgrade** once promoted: SMARTYcraft 3.6.4 protocol sync, plus a
  new direct HTTP channel that keeps auto-update alive when the upstream
  proxy is unreachable. See [2.2.7] below for the full notes.

## [2.2.7] - 2026-05-07

### Highlights
- **Required upgrade**: SMARTYcraft 3.6.5 protocol sync — proxy credentials
  rotated upstream, so anything older than this build cannot authenticate.
- Auto-updater and JDK/natives downloads now bypass the SMARTYcraft proxy,
  so the launcher can still update itself when the upstream is unreachable.
- Window icon and WM_CLASS render correctly on KDE Plasma, Hyprland and
  GNOME — workspace overviews show the proper hi-res launcher icon instead
  of a generic "broken file" glyph, on every JDK vendor.
- Per-OS data directory with automatic migration from `~/.aura`; relocate
  via the `AURA_DATA_DIR` env var.
- Update dialog reads a tidy "What's new" summary from a published
  `release-manifest.json` instead of scraping the raw changelog body.
