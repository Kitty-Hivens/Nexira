import hivens.packaging.PackagingExtension
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    // nexira.packaging: convention plugin from buildSrc/ that owns the
    // jlink + jpackage tasks for distribution builds. Currently registers
    // `:client-ui:customRuntime` only -- the jpackage path lands in a
    // follow-up commit. Source of truth for jlink flags lives in the
    // `packaging { jlink { ... } }` block below; AppImage shell script
    // consumes the same values via a generated profile fragment (also
    // follow-up).
    id("nexira.packaging")
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
                implementation(project(":client-auth"))
                implementation(project(":client-launcher"))
                implementation(project(":widget-api"))

                implementation(libs.filekit.core)
                implementation(libs.filekit.dialogs.compose)
                implementation(libs.koin.core)
                implementation(libs.koin.compose)
                implementation(libs.kotlinx.coroutines.swing)
                implementation(libs.logback.classic)
                implementation(libs.libtray)
                implementation(libs.libnotify)
                // Video / animated-image backgrounds (hivens.ui.background): FFmpeg
                // via Panama. skinema-compose brings -core + -skiko; the natives are
                // per-platform classifier jars on the runtime classpath, unpacked to a
                // per-user cache on first use. Ship one classifier per target tier.
                implementation(libs.skinema.compose)
                implementation(libs.skinema.skiko)
                runtimeOnly("dev.hivens:skinema-natives:${libs.versions.skinema.get()}:linux-x64")
                runtimeOnly("dev.hivens:skinema-natives:${libs.versions.skinema.get()}:linux-arm64")
                runtimeOnly("dev.hivens:skinema-natives:${libs.versions.skinema.get()}:windows-x64")
                runtimeOnly("dev.hivens:skinema-natives:${libs.versions.skinema.get()}:macos-arm64")
                runtimeOnly("dev.hivens:skinema-natives:${libs.versions.skinema.get()}:macos-x64")
                implementation(libs.ktor.client.core)
                // In-launcher HTML renderer (hivens.ui.render): jsoup parses, the
                // markdown lib does md->html. The velocipede before the standalone lib.
                implementation(libs.jsoup)
                implementation(libs.markdown.core)
                // Material You colour science -- wallpaper-seeded palette engine.
                implementation(libs.material.color.utilities)
            }
        }

        // Puppet HTTP control surface (hivens.ui.puppet.RealPuppetServer +
        // META-INF/services descriptor). Source dir + Ktor server deps are
        // added to the desktop compilation ONLY when `-PnexiraPuppetPort=N`
        // is on the Gradle command line; default production builds do not
        // contain RealPuppetServer or any of the Ktor server classes it
        // requires. Discovery is via Java SPI -- see PuppetServerLifecycle
        // and PuppetServerLoader in src/desktopMain for the rationale.
        //
        // CIO engine chosen over Netty for footprint (~2.5 MB vs ~9 MB);
        // we only need a half-dozen localhost endpoints.
        if (providers.gradleProperty("nexiraPuppetPort").isPresent) {
            desktopMain.kotlin.srcDir("src/desktopPuppetMain/kotlin")
            desktopMain.resources.srcDir("src/desktopPuppetMain/resources")
            desktopMain.dependencies {
                implementation(libs.ktor.server.core)
                implementation(libs.ktor.server.cio)
                implementation(libs.ktor.server.content.negotiation)
                implementation(libs.ktor.serialization.json)
            }
        }

        // The agent jars ship as OPAQUE resources under /runtime/, NOT compile
        // dependencies: they land in the ProGuard'd uber jar where -repackageclasses
        // would rename their Premain-Class and break -javaagent. AgentExtractor reads
        // /runtime/profiler-agent.jar (heap sampling) and /runtime/authlib-agent.jar
        // (SC-bound auth redirect) at runtime. The bundle* tasks (below) fill these
        // generated dirs; every *ProcessResources task depends on them.
        desktopMain.resources.srcDir(layout.buildDirectory.dir("generated/profilerAgent"))
        desktopMain.resources.srcDir(layout.buildDirectory.dir("generated/authlibAgent"))

        // First UI-side test source set. Compose-MP auto-creates the
        // task `desktopTest`; explicit source-set wiring lets the
        // module declare kotlin-test + coroutines-test deps and pull
        // in fakes from sibling modules.
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
}

// KSP must be wired against the per-target source set on a Kotlin
// Multiplatform project; `ksp(project(...))` alone (the JVM shape) is
// silently ignored. The "kspDesktop" configuration is created by the
// KSP plugin once the jvm("desktop") target above is configured -- one
// configuration per target compilation, not per source set.
dependencies {
    add("kspDesktop", project(":widget-processor"))
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
    buildConfigField("String", "APP_NAME",        "\"Nexira\"")
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
            // readable in crash reports (Nexira is GPL, hiding names earns nothing).
            // The `version.set(...)` pin ties ProGuard to the libs catalog
            // entry so a Compose-MP bump cannot silently drift the shrinker.
            buildTypes.release.proguard {
                isEnabled.set(true)
                optimize.set(true)
                obfuscate.set(false)
                configurationFiles.from(project.file("compose-desktop.pro"))
                version.set(libs.versions.proguard.get())
            }

            packageName = "Nexira"

            // jpackage expects a strictly numeric VersionInfoVersion: MAJOR.MINOR
            // [.BUILD[.REVISION]], digits only, no pre-release suffix. Our git
            // tags follow `v<semver>[-<suffix>]` convention (e.g. `v2.4.0-rc1`,
            // `v2.4.0`), so:
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
            description = "Nexira v${project.version} (unofficial)"
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
                // jdk.management carries com.sun.management.OperatingSystemMXBean, which
                // SystemMemory reflects into for host RAM (Automatic heap sizing). Without it
                // the bean lacks getTotalMemorySize/getTotalPhysicalMemorySize and RAM silently
                // falls back to a wrong 16 GB -- unlike the loud NoClassDefFound a missing
                // jdk.security.auth throws. Keep both module lists in sync.
                "jdk.management",
                "java.prefs",
                "jdk.crypto.ec",
                // jdk.security.auth: provides com.sun.security.auth.module.UnixSystem,
                // which FileKit's Linux dialog backend dlopens for user/group lookup
                // when resolving xdg-desktop-portal session context. Without it the
                // first FilePicker call on Linux crashes with NoClassDefFoundError
                // for UnixSystem -- reported on 2.3.2 via the AdvancedSection
                // openDirectoryPicker after the silent-swallow fix in PR 231 made
                // the underlying exception visible. Module is small (~30 KB);
                // adding it is cheaper than hand-wrapping the Linux backend.
                "jdk.security.auth",
                "jdk.unsupported",
                "jdk.zipfs"
            )

            windows {
                upgradeUuid = "30571060-3129-4503-b09e-716912389146"
                menuGroup = "Nexira"
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
                // Reverse-DNS of the hivens.dev apex (was com.hivens.* by
                // accident in earlier versions -- domain is hivens.dev, so
                // the leftmost component must be `dev`).
                bundleID = "dev.hivens.nexira"
                dockName = "Nexira"
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
            // created so the X11 WM_CLASS hint matches StartupWMClass=Nexira
            // in resources/nexira.desktop. KDE / Hyprland / GNOME associate
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
            // HotSpot default since Java 8). The cap was wrong for Nexira's shape:
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

            // Security — libtray's Panama bindings need native memory +
            // downcall stubs. Same flag also enables the macOS keyring
            // and Linux libsecret bindings.
            "--enable-native-access=ALL-UNNAMED",

            // Puppet mode (hivens.ui.puppet.PuppetServer) -- opt-in HTTP
            // control surface for CLI-driven UI testing. Activated when
            // the launcher is run with `-PnexiraPuppetPort=N` (forwarded
            // into the JVM as -Dnexira.puppet.port=N). Without the property
            // the puppet server's startIfRequested() is a no-op.
            //
            // providers.gradleProperty(...) over project.findProperty(...):
            // the Provider variant registers the read with the configuration
            // cache, so a property flip invalidates correctly; findProperty
            // bypasses the cache hook and bakes the value in on first
            // config-resolve.
            *(providers.gradleProperty("nexiraPuppetPort").orNull
                ?.let { arrayOf("-Dnexira.puppet.port=$it") }
                ?: emptyArray())
        )
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "hivens.ui.generated.resources"
    generateResClass = always
}

// Nexira's distribution-build profile. Single source of truth: the gradle
// `customRuntime` task consumes these values, and (in a follow-up commit)
// the AppImage shell script will read them from a generated profile
// fragment. Mirror of what scripts/build-appimage.sh currently hardcodes;
// once the emitter task lands, the hardcode goes away.
packaging {
    appName.set("Nexira")
    mainClass.set("hivens.ui.MainKt")

    // jpackage's `--app-version` is strict-digits (MAJOR.MINOR[.BUILD[.REVISION]],
    // no pre-release suffix). Reuse Compose Desktop's `safeVersion` derivation
    // immediately above so the two paths agree on what version string lands
    // in the produced binary. Once Compose Desktop's nativeDistributions
    // block is retired in B-3 the safeVersion lookup moves up here.
    appVersion.set(provider {
        val cleanVersion = project.version.toString().removePrefix("v").substringBefore("-")
        if (cleanVersion.matches(Regex("\\d+\\.\\d+.*"))) cleanVersion else "1.0.0"
    })

    modules.set(listOf(
        "java.base",
        "java.desktop",
        "java.logging",
        "java.management",
        // jdk.management: see the matching note in the compose block above. SystemMemory's
        // RAM read needs com.sun.management's OperatingSystemMXBean; its absence is a silent
        // 16 GB fallback (mis-sizing the Automatic heap), not a crash. verifyRuntimeModules guards it.
        "jdk.management",
        "java.prefs",
        "jdk.crypto.ec",
        // See the matching note in the compose.desktop.application block
        // above for why jdk.security.auth is required (FileKit Linux
        // dialog backend dlopens UnixSystem; absence -> NoClassDefFound
        // on first FilePicker call). This is the authoritative list
        // since the customJpackageImage migration; keep it in sync with
        // the legacy compose block until that block goes away.
        "jdk.security.auth",
        "jdk.unsupported",
        "jdk.zipfs",
        "jdk.localedata",
    ))

    // jvmArgs baked into the jpackage launcher script via repeated
    // --java-options. Same set as compose.desktop.application.jvmArgs
    // above (still authoritative until B-3 retires that block).
    // NEXIRA_WAYLAND_TRIAL flow is gone (Liberica swap commit e573318);
    // -Dawt.appClassName is JBR-only honour, dropped in the same
    // commit; jna.nosys was a dorkbox/JBR rudiment, also dropped.
    jvmArgs.set(listOf(
        "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
        "--enable-native-access=ALL-UNNAMED",
        "-Dawt.useSystemAAFontSettings=on",
        "-Djdk.gtk.version=3",
        "-D_JAVA_AWT_WM_NONREPARENTING=1",
        "-Drobot.need_x11=false",
        "-XX:+UseG1GC",
        "-XX:+UseStringDeduplication",
        "-XX:+OptimizeStringConcat",
        "-XX:+UseCompressedOops",
        "-Xms128m",
        "-Xmx512m",
        "-XX:MaxMetaspaceSize=256m",
        "-XX:ReservedCodeCacheSize=128m",
    ))

    windowsIcon.set(rootProject.file("resources/icons/icon.ico"))
    macosIcon.set(rootProject.file("resources/icons/icon.icns"))
    macosPackageIdentifier.set("dev.hivens.nexira")

    jlink {
        // compress unset: inner zip-9 leaves outer compressors less to work
        // with. Measured locally on a 2.2.16 customJpackageImage: dropping
        // zip-9 cut squashfs-zstd-22 output by 8 MB (AppImage path) and
        // xz -9e output by 13 MB (Inno Setup LZMA2/ultra64 proxy).
        vmKind.set("server")
        includeLocales.set("en,ru,de")
        // stripDebug / noHeaderFiles / noManPages default to true via
        // PackagingPlugin's conventions -- omitted intentionally.
    }
}

// Guard the one jlink module whose omission fails SILENTLY. SystemMemory reads host
// RAM via com.sun.management's OperatingSystemMXBean (the jdk.management module); drop
// it from the runtime image and the read falls back to a wrong 16 GB, mis-sizing the
// Automatic heap with no crash to flag it. A missing jdk.security.auth / jdk.crypto.ec
// throws NoClassDefFound (loud), so those need no guard. This reads the configured list
// only -- no jlink or release build -- and is wired into `check`.
// the<PackagingExtension>() is resolved at Project scope; inside the task lambda `the`
// would resolve against the Task's own extensions. The config-time .get() snapshot keeps
// doLast configuration-cache safe -- it closes over a plain List, not the build script.
val packagingExtension = the<PackagingExtension>()
val verifyRuntimeModules by tasks.registering {
    group = "verification"
    description = "Fails if packaging.modules omits a module the runtime read needs (jdk.management)."
    val modules = packagingExtension.modules.get()
    doLast {
        require("jdk.management" in modules) {
            "packaging.modules is missing \"jdk.management\": SystemMemory.totalPhysicalMb() will " +
                "silently fall back to 16 GB on the packaged build, mis-sizing the Automatic heap."
        }
    }
}

tasks.named("check") { dependsOn(verifyRuntimeModules) }

// Kotlin compiler options for every JVM compile task in client-ui.
// freeCompilerArgs split into "always on" and "opt-in" groups.
//
// Flags explicitly NOT set (worth recording so they don't creep back in):
//
//   * `-Xinline-classes` -- deprecated since value classes stabilized
//     (Kotlin 1.5+). Today the compiler treats it as no-op-or-warning.
//
//   * `-Xno-param-assertions / -Xno-call-assertions / -Xno-receiver-assertions`
//     -- strip Kotlin's generated nullability runtime checks. Trades
//     a handful of microseconds per call for a deep-stack NPE whenever
//     a non-null contract is violated, instead of an
//     IllegalArgumentException pointing at the boundary. Launcher
//     workloads aren't hot enough for the microseconds to matter and
//     contract-violation triage suffers a lot from the masked failure
//     mode; throw-on-boundary wins.
//
// Compose-compiler metrics + reports are opt-in via
// `-PnexiraComposeMetrics=true`. They write per-compile files into
// `build/compose_metrics` and `build/compose_reports`, useful for
// recomposition audits but wasted IO on every regular compile.
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)

        val composeMetricsEnabled = providers.gradleProperty("nexiraComposeMetrics")
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
// We do NOT exclude `**/*.kotlin_metadata` / `**/*.kotlin_builtins` --
// stripping those breaks reflection on our own classes: kotlin-reflect
// (3 MB on the runtime classpath), sealed-class enumeration,
// `KClass.members`, and kotlinx.serialization runtime fallback paths
// all read `.kotlin_metadata` for type information. Saves kilobytes
// per jar at the cost of silent runtime reflection breakage in
// production -- not a trade worth making.
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

// Stage the :profiler-agent jar (renamed to a stable name) into the generated
// resources dir registered above, so it ships inside the uber jar as
// /runtime/profiler-agent.jar. `from(task)` wires the dependency on
// :profiler-agent:jar; the explicit *ProcessResources dependency guarantees the
// file exists before resources are processed (the srcDir is a plain dir, so
// Gradle does not infer the task edge on its own).
val bundleProfilerAgent = tasks.register<Copy>("bundleProfilerAgent") {
    from(project(":profiler-agent").tasks.named("jar")) { rename { "profiler-agent.jar" } }
    into(layout.buildDirectory.dir("generated/profilerAgent/runtime"))
}

// Same staging for the authlib-redirect agent (separate generated dir / stable
// name so AgentExtractor.AUTHLIB_RESOURCE resolves it).
val bundleAuthlibAgent = tasks.register<Copy>("bundleAuthlibAgent") {
    from(project(":authlib-agent").tasks.named("jar")) { rename { "authlib-agent.jar" } }
    into(layout.buildDirectory.dir("generated/authlibAgent/runtime"))
}
tasks.matching { it.name.endsWith("ProcessResources") }.configureEach {
    dependsOn(bundleProfilerAgent, bundleAuthlibAgent)
}

// Portable ZIP packaging lives in the build_release workflow's
// PowerShell step, not in a Gradle task. The previous local-dev task
// here still pointed at the pre-B-3 Compose Desktop output path
// (compose/binaries/main-release/app) which is no longer produced
// since the customJpackageImage migration, so the task produced an
// empty / broken zip. Having two sources of truth for the same
// artifact (Gradle task + CI step) is the worst-of-both: divergence
// goes unnoticed locally until CI's version reaches a user. CI is
// the single source of truth; reproduce the steps from
// build_release.yml directly if you need a local portable.
