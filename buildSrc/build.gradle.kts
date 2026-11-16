// buildSrc is Nexira's home for build-logic Kotlin: typed task classes, a
// convention plugin, and any cross-cutting helpers we end up writing for the
// packaging path. The `kotlin-dsl` plugin gives us:
//   - Kotlin compilation of files under src/main/kotlin/, automatically
//     available to every project's build.gradle.kts at evaluation time.
//   - Implicit access to the Gradle API + Kotlin DSL extensions, so a
//     custom DefaultTask subclass can use Property<T>, @Input / @Output
//     annotations, etc., without explicit dependency declarations.
//   - Java 17+ source/target by default, which lines up with the project's
//     JDK 27 toolchain (buildSrc runs under the daemon's JDK, not the
//     subprojects' toolchain pin).
//
// Why buildSrc/ rather than `:build-logic` composite-included module: the
// consumers are `:client-ui` and the modules under look/, all in this build,
// with no library-reuse story and no incremental-build-of-build-logic benefit
// on a project this size. The kotlin-dsl scaffolding here is enough.
//
// Repositories are scoped to this build only -- the root project's
// `RepositoriesMode.FAIL_ON_PROJECT_REPOS` policy applies to the main build
// graph, not to buildSrc, so the duplication here is intentional and
// expected.

plugins {
    `kotlin-dsl`
    // The Kotlin and Android Gradle plugins are buildSrc's dependencies, out of the root
    // build's sight, so their updates are surfaced here: ./gradlew -p buildSrc dependencyUpdates.
    alias(libs.plugins.versions)
}

repositories {
    // The Android Gradle Plugin is published here and nowhere else, the same filter
    // as the root settings.
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

// The plugins the look modules' convention plugin applies and configures by type. A
// plugin on buildSrc's classpath is on every build script's, and Gradle refuses a
// versioned request for a plugin it already holds, so the catalog's aliases for these
// carry no version and the version is pinned here, from the same catalog. The Android
// plugin comes along with Kotlin's: the Kotlin plugin reads its classes, and the two
// have to be loaded side by side.
dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.compose.compiler.gradle.plugin)
    implementation(libs.android.gradle.plugin)
    testImplementation(kotlin("test"))
}

// Gradle builds buildSrc without running its tests, so these run only when asked
// for: `./gradlew -p buildSrc test`, which CI does.
tasks.test {
    useJUnitPlatform()
}

// Kotlin compiles this to JVM 26, the newest target it knows, while the Java side
// follows the daemon's JDK 27. There are no Java sources here, so the Java target
// is held to Kotlin's, which is what the plugin checks the two against.
java {
    targetCompatibility = JavaVersion.VERSION_26
}

// Convention plugins declared here become consumable from project
// build.gradle.kts files via `plugins { id("nexira.packaging") }`. The
// `kotlin-dsl` plugin pulls in the `java-gradle-plugin` capability that
// provides this `gradlePlugin` extension; explicit registration (rather
// than relying on auto-discovery via META-INF/gradle-plugins resources)
// keeps the wiring readable in one place.
gradlePlugin {
    plugins {
        register("packaging") {
            id = "nexira.packaging"
            implementationClass = "hivens.packaging.PackagingPlugin"
        }
        // GraalVM / Liberica-NIK native-image build. No module applies it yet: it
        // is kept for building the launcher itself native. Same shape as
        // `nexira.packaging`: typed ExecOperations tasks, config-cache clean, no
        // third-party plugin on the classpath.
        register("nativeImage") {
            id = "nexira.native-image"
            implementationClass = "hivens.nativeimage.NativeImagePlugin"
        }
        // A module under look/: the targets, Compose and, for an API, its ABI dump
        // and version, so its own build file names only what it depends on.
        register("look") {
            id = "nexira.look"
            implementationClass = "hivens.look.LookModulePlugin"
        }
    }
}
