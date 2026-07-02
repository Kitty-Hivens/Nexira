// `RepositoriesMode.FAIL_ON_PROJECT_REPOS` and the corresponding
// `repositoriesMode.set(...)` setter are `@Incubating` in Gradle as of
// 9.x. We accept that risk deliberately (the policy of single-source-of-
// truth for repositories is worth the API churn cost), but Qodana would
// otherwise repeatedly flag the four call sites every PR. Suppress at the
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
        gradlePluginPortal()
        mavenCentral()
    }
}

// foojay-resolver-convention -- safety net for the Gradle toolchain
// jvmToolchain(25) call in root build.gradle.kts. If a contributor runs the
// build with a JDK <25 (or no JDK 25 at all on PATH), Gradle resolves and
// downloads one automatically from the foojay.io distributions API rather
// than failing with a cryptic "no toolchains of required version found"
// error. Policy stays loose (any vendor of JDK 25); foojay defaults to a
// safe-bet vendor when it picks for you.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
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

include(":client-config")
include(":client-core")
// Auth seam carved out of the launcher god-module: :client-auth holds the
// provider-agnostic AuthProvider SPI, :client-auth-smartycraft the SmartyCraft impl,
// :client-auth-microsoft the Microsoft (MSA device-code) impl.
include(":client-auth")
include(":client-auth-smartycraft")
include(":client-auth-microsoft")
include(":client-launcher")
// Headless native-image entrypoint: a Compose-free CLI over the launch
// pipeline (auth -> resolve -> download -> JRE -> runtime -> launch),
// buildable to a GraalVM / Liberica-NIK native binary for Linux. The GUI
// (:client-ui) stays on the JVM -- Skiko/AWT block native-image of Compose.
// See docs/native-image.md.
include(":client-cli")
// Media playback support (yt-dlp + URL video cache) feeding the Skinema player.
// Its own seam: consumed by the UI only, unknown to the launch engine.
include(":client-media")
include(":client-ui")
// Leaf design-system module (NxUI): tokens, primitives, surfaces, Flexible.
// client-ui depends on it one-way; it depends on nothing in-tree.
include(":nx-ui")
include(":widget-model")
include(":widget-api")
include(":widget-processor")
include(":profiler-agent")
include(":authlib-agent")
