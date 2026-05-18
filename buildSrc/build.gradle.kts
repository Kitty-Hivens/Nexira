// buildSrc is Aura's home for build-logic Kotlin: typed task classes, a
// convention plugin, and any cross-cutting helpers we end up writing for the
// packaging path. The `kotlin-dsl` plugin gives us:
//   - Kotlin compilation of files under src/main/kotlin/, automatically
//     available to every project's build.gradle.kts at evaluation time.
//   - Implicit access to the Gradle API + Kotlin DSL extensions, so a
//     custom DefaultTask subclass can use Property<T>, @Input / @Output
//     annotations, etc., without explicit dependency declarations.
//   - Java 17+ source/target by default, which lines up with the project's
//     JDK 25 toolchain (buildSrc runs under the daemon's JDK, not the
//     subprojects' toolchain pin).
//
// Why buildSrc/ rather than `:build-logic` composite-included module:
// single consumer (`:client-ui`), no library-reuse story, no incremental-
// build-of-build-logic benefit on a project this size. The kotlin-dsl
// scaffolding here is enough; graduate later if multiple consumers appear
// (they almost certainly will not).
//
// Repositories are scoped to this build only -- the root project's
// `RepositoriesMode.FAIL_ON_PROJECT_REPOS` policy applies to the main build
// graph, not to buildSrc, so the duplication here is intentional and
// expected.

plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}
