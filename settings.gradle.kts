// `RepositoriesMode.FAIL_ON_PROJECT_REPOS` and the corresponding
// `repositoriesMode.set(...)` setter are `@Incubating` in Gradle as of
// 9.x. We accept that risk deliberately (the policy of single-source-of-
// truth for repositories is worth the API churn cost), but the IDE would
// otherwise flag the four call sites. Suppress at the
// file level rather than at every line; if the @Incubating annotation
// gets removed in a future Gradle release, this annotation becomes a
// no-op rather than masking a different issue.
@file:Suppress("UnstableApiUsage")

// Plugin resolution: explicit Plugin Portal pin lets a reader trace any
// plugin id below back to a known repository without guessing. Default
// gradlePluginPortal() resolution is the same source but implicit; spelling
// it out costs one line and removes "where does this plugin come from" as
// a question during code review.
pluginManagement {
    repositories {
        // The Android Gradle Plugin is published here and nowhere else; the filter
        // keeps every other plugin resolving from the portal.
        google {
            content {
                includeGroupByRegex("com\\.android(\\..*)?")
                includeGroupByRegex("com\\.google\\.android(\\..*)?")
                includeGroupByRegex("androidx(\\..*)?")
            }
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

// foojay-resolver-convention -- safety net for the Gradle toolchain
// jvmToolchain(27) call in root build.gradle.kts. If a contributor runs the
// build with a JDK <27 (or no JDK 27 at all on PATH), Gradle resolves and
// downloads one automatically from the foojay.io distributions API rather
// than failing with a cryptic "no toolchains of required version found"
// error. Policy stays loose (any vendor of JDK 27); foojay defaults to a
// safe-bet vendor when it picks for you.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Repository centralization (Gradle 7+ convention):
//   * RepositoriesMode.FAIL_ON_PROJECT_REPOS makes any per-subproject
//     `repositories { ... }` block a build-time error. Forces the
//     single-source-of-truth rule -- a new repo only enters the project
//     by being added here, not by drifting into one subproject and
//     surprising another.
//   * mavenCentral covers the bulk of dependencies (incl. libtray, now
//     published as dev.hivens:libtray).
//
// JetBrains compose-dev maven was previously listed for Compose Multiplatform
// alpha artifacts. Removed alongside the bump to 1.11.0 GA (commit d214466) --
// stable + alpha lines for compose-mp publish to mavenCentral directly.
//
// google() Maven is REQUIRED even on a desktop-only Compose Multiplatform
// project. Compose-MP's `org.jetbrains.androidx.lifecycle:lifecycle-runtime`
// and `org.jetbrains.androidx.savedstate:savedstate-compose` artifacts
// transitively depend on the Google AndroidX originals (`androidx.lifecycle`,
// `androidx.savedstate` -- no `org.jetbrains.` prefix). Those originals live
// on Google Maven only; mavenCentral does not mirror them. Dropping google()
// breaks resolution of Compose-MP itself, not just Android-flavoured modules.
// Discovered the hard way 2026-05-17 while modernizing this file.
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "nexira"

// PARKED. Groundwork for a module-loading architecture that is not the shape the
// launcher is built in today; it lives under experimental/ alongside the design
// notes it came from. Kept in the build so it keeps compiling and its tests keep
// running -- parked code that falls out of the build rots instead of waiting.
include(":experimental:client-boot")
include(":client-config")
include(":client-core")
// Shared test doubles for :client-core and its consumers (stands in for java-test-fixtures).
include(":client-core-testing")
// Auth seam carved out of the launcher god-module: :client-auth holds the
// provider-agnostic AuthProvider SPI, :client-auth-smartycraft the SmartyCraft impl,
// :client-auth-microsoft the Microsoft (MSA device-code) impl.
include(":client-auth")
include(":client-auth-smartycraft")
include(":client-auth-microsoft")
include(":client-launcher")
// The launcher's self-update: release check, delta bundle, binary patch and the
// per-platform applicators. Split from pack update, which shared the package name
// and nothing else and stays in :client-launcher next to the mirror client.
include(":client-update")
// What decides how sound plays: the queue, the repeat modes and the state machine over an
// engine. Shared by the desktop and Android builds; the engines are handed in.
include(":client-playback")
// The Android application: hosts the shared modules on a phone. It cannot even be configured
// without an SDK, so a machine that has none (the analysis container, a contributor who only
// builds the desktop launcher) leaves it out instead of failing the whole build.
val androidSdkKnown = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").takeIf { it.isFile }?.readLines().orEmpty().any { it.startsWith("sdk.dir=") }
if (androidSdkKnown) include(":android-app")
// Media playback support (yt-dlp + URL video cache) feeding the Skinema player.
// Its own seam: consumed by the UI only, unknown to the launch engine.
include(":client-media")
// Tray seam carved out of client-ui: :client-tray holds the TrayController SPI
// and its libtray-backed impl. It depends only on libtray -- the launch engine
// and auth are off-limits by construction, so a tray action can never block on
// them.
include(":client-tray")
// The launcher window's state and its frame on each desktop system: one owner for
// maximised and minimised, the native frame on Windows, the window manager's word
// on X11, and the record of how the window was left. No Compose: the UI reads it
// and asks it, and never keeps a view of the window of its own.
include(":client-window")
include(":client-ui")
// Leaf design-system module (NxUI): tokens, primitives, surfaces, Flexible.
// client-ui depends on it one-way; it depends on nothing in-tree.
include(":nx-ui")
// Layer ordering and the dismiss stack of a window, with no visuals. Below both
// nx-ui and the widget kernel, and depends on neither.
include(":ui-layers")
// The puppet hooks a control registers itself with for the puppet's HTTP control surface, and their
// registry. A development seam with no visuals, used by client-ui and the modules compiled into it.
include(":ui-puppet")
// The launcher's own words: the tables of the `nexira` namespace the language module
// answers, and the composable that reads them. Carved out of
// client-ui, where it was the single largest package and imported by 150
// files while importing nothing back. A string edit no longer recompiles
// the UI.
include(":client-i18n")
// Software 3D: the scene graph and rasteriser (scene3d, render3d) plus the
// Minecraft skin rig on top of them (skin3d). Consumed by the wardrobe and
// the profile; kept out of client-ui so the renderer can be exercised on
// its own.
include(":client-render3d")
// The April Fools engine and its companions. A self-contained seasonal
// surface with three imports into the rest of the tree; separate so it
// cannot be disturbed by, and cannot disturb, ordinary UI work.
include(":client-easter")
include(":widget-model")
include(":widget-api")
include(":widget-processor")
// Not part of the launcher. A worked example of a widget module: it builds to an
// ordinary jar, is discovered from the widgets directory at runtime, and depends
// on the kernel and nothing else in-tree -- no client-ui, no nx-ui, no NxTheme.
// Kept in the build so it keeps compiling against the API it demonstrates.
include(":examples:widget-pixelplayer")
include(":examples:widget-screenfx")
include(":examples:widget-looks")
// Not part of the launcher. A module built on the packs module's API artifact and the
// kernel only, kept in the build so the claim that a module needs nothing else to use
// the packs keeps compiling.
include(":examples:packs-consumer")
// The same for the accounts module's API: reads the session and fires sign-out with
// the kernel and that API alone.
include(":examples:accounts-consumer")
// The same for the catalogue module's API: runs a search and reads what it found with the
// kernel and that API alone.
include(":examples:catalogue-consumer")
// Runtime discovery of widget modules: scans a directory for jars, checks the
// ABI they declare, and hands back registries. Separate from :widget-api so a
// module author compiles against the kernel without pulling in its loader.
include(":widget-loader")
// The packs and launch module: the keys a widget reads and fires to list, launch, stop,
// install, update, delete and reattach packs. The API artifact is what another module
// compiles against, the module answers the keys and is brought in only by a document
// that names it.
include(":packs-api")
include(":packs")
// The accounts and sign-in module: the keys a widget reads and fires for who the
// launcher is signed in as, the stored accounts and every way to sign in and out.
// Its API carries no secret, and the module itself never depends on the interface.
include(":accounts-api")
include(":accounts")
// The catalogue module: the keys a widget reads and fires to search the pack sources and
// the catalogue, open a project, a build or a pack page, and put a project into a pack.
// Like the others, brought in only by a document that names it.
include(":catalogue-api")
include(":catalogue")
// The catalogue's sources, one module each: the Hivens mirror and Modrinth. Each brings
// what it lists, a pack's page and its builds as a contribution of the catalogue API's
// source contract, which a third party implements the same way.
include(":source-mirror")
include(":source-modrinth")
// The language module: the words of the launcher and of every module, by namespace and
// key, in the languages that ship and in any a person adds as files. Its API is what a
// module looks its words up through. The window the launcher boots in already speaks, so
// the module answers its words before the host is up as well.
include(":i18n-api")
include(":i18n")
// The look: a manager that picks one provider per axis of the look from the person's choice
// and hands their answers to the composition, knowing no axis itself. The axes, their providers
// and the packs naming them are modules of their own under look/, built by the nexira.look
// convention plugin in buildSrc, their suites run together by :look:allTests.
include(":look:manager-api")
include(":look:manager")
include(":look:colour-api")
include(":look:packs")
// The nx colour provider, which is the themes module: the themes that ship, the one made from the
// wallpaper, the ones modules bring and the person's own, which is in use and what decides dark and
// light, and where the choice is kept. Its module id, settings and files are still the themes
// module's, nexira.themes and themes.json, so no choice a person made is lost. Like the language
// module, it answers the theme before the host is up, since the boot window is drawn in the
// person's theme.
include(":look:colour-nx-api")
include(":look:colour-nx")
// The UI that steers the nx colour provider: the day and night switch, the mode, the theme picker.
include(":look:colour-nx-controls")
include(":look:motion-api")
// The nx motion provider: nx's durations and curves, and the person's reduce motion, kept in its
// own settings.
include(":look:motion-nx")
// The UI that steers the nx motion provider: the reduce motion switch.
include(":look:motion-nx-controls")
include(":look:shape-api")
// The nx shape provider: nx's corners, with no settings of its own.
include(":look:shape-nx")
include(":look:stroke-api")
// The nx stroke provider: nx's strokes, with no settings of its own.
include(":look:stroke-nx")
include(":profiler-agent")
include(":authlib-agent")
