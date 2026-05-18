import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.kotlin.serialization)
}

group = "hivens"

// Repositories live in settings.gradle.kts (dependencyResolutionManagement
// with FAIL_ON_PROJECT_REPOS). Adding a `repositories { ... }` block here
// is a build-time error by design -- changes go in one place only.

kotlin {
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.compose.runtime)
                implementation(libs.compose.foundation)
                implementation(libs.compose.material3)
                implementation(libs.compose.ui)
                implementation(libs.compose.components.resources)
                implementation(libs.compose.material.icons.extended)
                implementation(libs.multiplatform.markdown.m3)

                // Coil pulls `org.jetbrains.skiko` transitively at its own
                // version, which can ABI-mismatch with the Skiko that
                // Compose-MP brings in. The exclude removes Coil's copy so
                // Compose-MP stays the single source of truth.
                //
                // Catalog-accessor + configuration-action overload was
                // re-tested 2026-05-17 against Gradle 9.4 + Kotlin
                // Multiplatform 2.3.21 and the DSL still rejects it
                // ("implementation(provider) { ... }" -> "function
                // 'implementation' cannot be applied to these arguments").
                // String coordinates remain the workable shape; the val
                // pair below at least keeps the version pinned through the
                // catalog so a bump in libs.versions.toml propagates here.
                val coilCoord = "io.coil-kt.coil3"
                val coilV     = libs.versions.coil.get()
                implementation("$coilCoord:coil-compose:$coilV")        { exclude(group = "org.jetbrains.skiko") }
                implementation("$coilCoord:coil-network-okhttp:$coilV") { exclude(group = "org.jetbrains.skiko") }
                implementation(libs.ktor.serialization.json)
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)

                implementation(project(":client-config"))
                implementation(project(":client-core"))
                implementation(project(":client-launcher"))

                implementation(libs.filekit.core)
                implementation(libs.filekit.dialogs.compose)
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.logback.classic)
                implementation(libs.libtray)
                implementation(libs.ktor.client.core)
            }
        }

        // Puppet HTTP control surface (hivens.ui.puppet.RealPuppetServer +
        // META-INF/services descriptor). Source dir + Ktor server deps are
        // added to the desktop compilation ONLY when `-PauraPuppetPort=N`
        // is on the Gradle command line; default production builds do not
        // contain RealPuppetServer or any of the Ktor server classes it
        // requires. Discovery is via Java SPI -- see PuppetServerLifecycle
        // and PuppetServerLoader in src/desktopMain for the rationale.
        //
        // CIO engine chosen over Netty for footprint (~2.5 MB vs ~9 MB);
        // we only need a half-dozen localhost endpoints.
        if (providers.gradleProperty("auraPuppetPort").isPresent) {
            desktopMain.kotlin.srcDir("src/desktopPuppetMain/kotlin")
            desktopMain.resources.srcDir("src/desktopPuppetMain/resources")
            desktopMain.dependencies {
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.json)
            }
        }
    }
}

// BUILD_TIME = Unix epoch millis of last commit (`git log -1 --format=%ct
// HEAD * 1000`). Matches the same derivation in client-config -- both
// modules expose BUILD_TIME and must agree. System.currentTimeMillis()
// would freeze in Gradle config cache; git timestamp invalidates only
// when the commit graph changes, which is the right invalidation shape.
// See client-config/build.gradle.kts for the long-form rationale.
val gitCommitTimeMillis: Long = runCatching {
    providers.exec {
        commandLine("git", "log", "-1", "--format=%ct", "HEAD")
    }.standardOutput.asText.get().trim().toLong() * 1000L
}.getOrElse { System.currentTimeMillis() }

buildConfig {
    packageName("hivens.ui")
    buildConfigField("String", "FORK_VERSION",    "\"${project.version}\"")
    buildConfigField("long",   "BUILD_TIME",      "${gitCommitTimeMillis}L")
    buildConfigField("String", "APP_NAME",        "\"Aura Launcher\"")
    buildConfigField("String", "COMPOSE_VERSION", "\"${libs.versions.compose.get()}\"")
    buildConfigField("String", "KTOR_VERSION",    "\"${libs.versions.ktor.get()}\"")
    buildConfigField("String", "KOIN_VERSION",    "\"${libs.versions.koin.get()}\"")
    buildConfigField("String", "COIL_VERSION",    "\"${libs.versions.coil.get()}\"")
}

compose.desktop {
    application {
        mainClass = "hivens.ui.MainKt"

        nativeDistributions {
            targetFormats(
                // Windows: distributable dir is fed into Inno Setup (setup.iss).
                // MSI removed — replaced by Inno Setup EXE (issue #51).
                TargetFormat.Exe,

                // Linux: AppImage assembled manually in CI to embed a bundled JRE
                // and inject .desktop / AppStream metainfo (issue #53).
                // DEB and RPM removed.

                // macOS: unchanged.
                TargetFormat.Dmg
            )

            // Shrinker: ProGuard 7.8.2, configured via compose-desktop.pro.
            // optimize=true enables the optimization passes declared in the
            // .pro file; obfuscate=false keeps stack traces / class names
            // readable in crash reports (Aura is GPL, hiding names earns nothing).
            // The `version.set(...)` pin ties ProGuard to the libs catalog
            // entry so a Compose-MP bump cannot silently drift the shrinker.
            buildTypes.release.proguard {
                isEnabled.set(true)
                optimize.set(true)
                obfuscate.set(false)
                configurationFiles.from(project.file("compose-desktop.pro"))
                version.set(libs.versions.proguard.get())
            }

            packageName = "AuraLauncher"

            // jpackage expects a strictly numeric VersionInfoVersion: MAJOR.MINOR
            // [.BUILD[.REVISION]], digits only, no pre-release suffix. Our git
            // tags follow `v<semver>[-<suffix>]` convention (e.g. v2.2.14-rc1,
            // v2.2.14), so:
            //   1. Strip the leading "v" if present (tag invocation forces it,
            //      manual -PappVersion overrides may or may not).
            //   2. Drop everything from the first "-" (rc / beta / dirty suffix).
            //   3. Sanity check what is left actually matches digits-and-dots; if
            //      not, fall back to "1.0.0" so packaging doesn't fail loud on a
            //      cosmetically broken version override.
            // The same normalization runs in setup.iss for Windows installer
            // VersionInfoVersion -- both must agree, otherwise upgrade detection
            // breaks.
            val cleanVersion = project.version.toString().removePrefix("v").substringBefore("-")
            val safeVersion = if (cleanVersion.matches(Regex("\\d+\\.\\d+.*"))) cleanVersion else "1.0.0"
            packageVersion = safeVersion
            description = "Aura Launcher v${project.version} (unofficial)"
            copyright = "© 2026 Hivens"
            vendor = "Hivens"

            // ====================================================================
            // CUSTOM MINIMAL JRE
            // ====================================================================
            // Modules verified used via bytecode scan over the uber jar
            // (every `java/sql/`, `java/net/http/`, `javax/naming/` reference
            // counted across all 31k bundled classes). The three modules
            // removed in this trim:
            //   - java.sql      0 references in the bundle
            //   - java.naming   only ch.qos.logback.* code paths (SMTP
            //                   appender, <insertFromJNDI>, servlet
            //                   container integration) -- none of which our
            //                   logback.xml exercises
            //   - java.net.http 0 references (Ktor is configured with the
            //                   OkHttp engine; java HttpClient unused)
            // java.prefs stays even though we don't use it directly, because
            // java.desktop already requires it transitively -- listing it
            // explicitly is redundant but documents intent.
            modules(
                "java.base",
                "java.desktop",
                "java.logging",
                "java.management",
                "java.prefs",
                "jdk.crypto.ec",
                "jdk.unsupported",
                "jdk.zipfs"
            )

            windows {
                upgradeUuid = "30571060-3129-4503-b09e-716912389146"
                menuGroup = "Aura Launcher"
                shortcut = true
                dirChooser = true
                iconFile.set(rootProject.file("resources/icons/icon.ico"))

                // perUserInstall removed — Inno Setup handles privilege escalation
                // via PrivilegesRequired=lowest in setup.iss
                console = false
            }

            // No `linux { ... }` block: DEB/RPM are not shipped, and AppImage is
            // assembled in CI from `resources/icons/`. The Compose Linux package
            // task is not invoked, so its iconFile is dead weight.

            macOS {
                bundleID = "com.hivens.auralauncher"
                dockName = "Aura Launcher"
                // Without iconFile, jpackage falls back to the default
                // Compose/Kotlin "K + folder" placeholder. Regenerate via
                // `png2icns` (libicns package) from the same source PNGs
                // we use for Linux/Windows — see scripts/regenerate-icons.sh.
                iconFile.set(rootProject.file("resources/icons/icon.icns"))
            }
        }

        // ====================================================================
        // JVM ARGUMENTS OPTIMIZATION
        // ====================================================================
        jvmArgs(
            // Linux window-manager identity. Main.kt reflects into
            // sun.awt.X11.XToolkit.awtAppClassName before the first window is
            // created so the X11 WM_CLASS hint matches StartupWMClass=AuraLauncher
            // in resources/aura-launcher.desktop. KDE / Hyprland / GNOME associate
            // the live window with the .desktop entry on that match and pick up
            // the hicolor icon at the size the compositor actually wants. The
            // --add-opens below is what allows the reflection. Stock OpenJDK
            // derives WM_CLASS from argv[0] by default; without the reflection
            // the launcher would show up as "java" in the taskbar.
            "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",

            // X11/Linux desktop tuning. macOS/Windows JVMs silently ignore
            // these properties (XToolkit code path is not even loaded), so
            // shipping them unconditionally is noise rather than wrong. If
            // Compose-MP ever gains per-platform jvmArgs support upstream we
            // should move them into a linux { ... } block; until then keep
            // here with a comment so a contributor reading the file does
            // not waste time wondering why a Mac build sets _JAVA_AWT_WM_*.
            "-Dawt.useSystemAAFontSettings=on",
            "-Djdk.gtk.version=3",
            "-D_JAVA_AWT_WM_NONREPARENTING=1",
            "-Drobot.need_x11=false",

            // Performance flags
            "-XX:+UseG1GC",
            "-XX:+UseStringDeduplication",
            "-XX:+OptimizeStringConcat",
            "-XX:+UseCompressedOops",

            // No JIT-level lock. The previous block here set TieredStopAtLevel=1
            // (cap at C1, no C2) plus an explicit +TieredCompilation (already the
            // HotSpot default since Java 8). The cap was wrong for Aura's shape:
            // launcher sessions are long-running (multi-minute downloads + game
            // launch + tray idle), Compose recompose is hot, file verify + jdk
            // extract are CPU-bound. C2 compilation pays for itself many times
            // over once the steady state kicks in; the startup ms saved by C1-only
            // are noise next to the 5+ seconds we spend on first window paint.

            // Memory optimization
            "-Xms128m",
            "-Xmx512m",
            "-XX:MaxMetaspaceSize=256m",
            "-XX:ReservedCodeCacheSize=128m",

            // Security — libtray's Panama bindings need access to native
            // memory + downcall stubs. Same flag also enables the
            // macOS keyring + libsecret bindings shipped in 2.2.13.
            "--enable-native-access=ALL-UNNAMED",

            // Puppet mode (hivens.ui.puppet.PuppetServer) -- opt-in HTTP
            // control surface for CLI-driven UI testing. Activated when
            // the launcher is run with `-PauraPuppetPort=N` (forwarded
            // into the JVM as -Daura.puppet.port=N). Without the property
            // the puppet server's startIfRequested() is a no-op.
            //
            // providers.gradleProperty(...) over project.findProperty(...):
            // the Provider variant registers the read with the configuration
            // cache, so a property flip invalidates correctly; findProperty
            // bypasses the cache hook and bakes the value in on first
            // config-resolve.
            *(providers.gradleProperty("auraPuppetPort").orNull
                ?.let { arrayOf("-Daura.puppet.port=$it") }
                ?: emptyArray())
        )
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "hivens.ui.generated.resources"
    generateResClass = always
}

// Kotlin compiler options for every JVM compile task in client-ui.
//
// freeCompilerArgs are split into "always on" and "opt-in" groups.
//
// Removed 2026-05-17 (audit chunk 2 item #26) and worth recording why so
// they do not creep back in:
//
//   * -Xinline-classes : deprecated since the value-classes language feature
//     stabilized (Kotlin 1.5+). Compiler treats it as no-op-or-warning today.
//
//   * -Xno-param-assertions / -Xno-call-assertions / -Xno-receiver-assertions :
//     strip Kotlin's generated nullability runtime checks. Trades a handful
//     of microseconds per call for a deep-stack NullPointerException whenever
//     a non-null contract is violated, instead of an IllegalArgumentException
//     pointing at the boundary. Launcher workloads are nowhere near hot enough
//     for the micros to matter and contract-violation triage suffers a lot
//     from the masked failure mode; the throw-on-boundary side wins.
//
// The Compose-compiler metrics + reports flags are now opt-in via
// `-PauraComposeMetrics=true`. They write per-compile files into
// build/compose_metrics and build/compose_reports, which is useful for
// recomposition audits but wasted IO on every regular compile.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)

        val composeMetricsEnabled = providers.gradleProperty("auraComposeMetrics")
            .map { it == "true" }.orElse(false).get()
        val buildDirAbsolute = layout.buildDirectory.get().asFile.absolutePath

        freeCompilerArgs.addAll(
            // Language: nested typealiases are used in AprilFoolsEngine.kt
            // for the floating-button Event signature. Without the flag the
            // compiler restricts typealiases to top level only.
            "-XXLanguage:+NestedTypeAliases",

            // Bytecode: emit default methods directly (legal on jvmTarget=25).
            // Smaller class files, removes the DefaultImpls indirection.
            "-jvm-default=no-compatibility",

            // Bytecode: invokedynamic-based lambdas instead of synthetic
            // anonymous classes. Smaller jar, slightly slower first invoke
            // per lambda site.
            "-Xlambdas=indy",

            // Compose compiler: disable live literals (hot-reload-style swap
            // of constant values at runtime). Production never observes a
            // live-literal swap; flag pays only at recompose cost.
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:liveLiterals=false",
        )

        if (composeMetricsEnabled) {
            freeCompilerArgs.addAll(
                "-P", "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=$buildDirAbsolute/compose_metrics",
                "-P", "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=$buildDirAbsolute/compose_reports",
            )
        }
    }
}

// Jar packaging.
//
// Removed 2026-05-17 (audit chunk 2 item #27):
//   exclude("**/*.kotlin_metadata"), exclude("**/*.kotlin_builtins").
//   Stripping those breaks reflection on our own classes -- kotlin-reflect
//   (which is on the runtime classpath at 3 MB), sealed-class enumeration,
//   KClass.members, and kotlinx.serialization runtime fallback paths all
//   read .kotlin_metadata for type information. The size win was kilobytes
//   per jar against the risk of silent runtime reflection breakage that
//   would only show up in production -- not a trade worth making.
tasks.withType<Jar>().configureEach {
    // Signed-JAR manifest metadata: not relevant; we ship unsigned and
    // jpackage / Inno Setup handle authenticode on their own envelope.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    // kotlinx.coroutines debug-mode instrumentation. Only consulted when
    // launched with -Dkotlinx.coroutines.debug=on; production never does.
    exclude("DebugProbesKt.bin")
    // ProGuard config shipped inside third-party jars (so consumers can
    // apply recommended keep rules). Not needed at runtime; we have our
    // own compose-desktop.pro and shrink ourselves.
    exclude("META-INF/proguard/**")
    // Android tooling artifacts (lint baselines, mocked-resources hints).
    // JVM-only desktop; never loaded.
    exclude("META-INF/com.android.tools/**")

    // Reproducibility / deterministic packaging
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// ========================================================================
// BUILD PERFORMANCE
// ========================================================================
tasks.configureEach {
    if (name.contains("checkRuntime")) {
        dependsOn(
            ":client-config:generateBuildConfigClasses",
            ":client-config:processResources",
            ":client-core:processResources",
            ":client-launcher:processResources"
        )
    }
}

// ========================================================================
// PORTABLE ZIP (Windows)
// For local dev use; CI also runs this step independently.
// ========================================================================
tasks.register<Zip>("packageWindowsPortableZip") {
    group = "compose desktop"
    description = "Packages the Windows distributable as a portable ZIP"
    dependsOn("createReleaseDistributable")

    from(layout.buildDirectory.dir("compose/binaries/main-release/app"))
    archiveFileName.set("AuraLauncher-${project.version}-Windows-Portable.zip")
    destinationDirectory.set(layout.buildDirectory.dir("compose/binaries/main-release"))
}
