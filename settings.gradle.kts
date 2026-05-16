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

// Repository centralization (Gradle 7+ convention):
//   * RepositoriesMode.FAIL_ON_PROJECT_REPOS makes any per-subproject
//     `repositories { ... }` block a build-time error. Forces the
//     single-source-of-truth rule -- a new repo only enters the project
//     by being added here, not by drifting into one subproject and
//     surprising another.
//   * mavenCentral covers the bulk of dependencies.
//   * JitPack hosts libtray (Kitty-Hivens/libtray) until it ships a real
//     Maven Central release; commit-sha-pinned in libs.versions.toml so
//     an upstream main-branch break does not silently drift the build.
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
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "aura-launcher"

include(":client-config")
include(":client-core")
include(":client-launcher")
include(":client-ui")
