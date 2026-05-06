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

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

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

                // String coordinates here (instead of the catalog accessor) because
                // KotlinDependencyHandler.implementation(provider) lacks a configuration-action
                // overload — the exclude requires the String form.
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
                implementation(libs.dorkbox.systemtray)
                implementation(libs.ktor.client.core)

                // Windows-only explicit pin: see libs.versions.toml comment on jnaWindows.
                implementation(libs.jna)
                implementation(libs.jna.platform)
            }
        }
    }
}

buildConfig {
    packageName("hivens.ui")
    buildConfigField("String", "FORK_VERSION",   "\"${project.version}\"")
    buildConfigField("long",   "BUILD_TIME",     "${System.currentTimeMillis()}L")
    buildConfigField("String", "APP_NAME",       "\"Aura Launcher\"")
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

            // ====================================================================
            // PROGUARD AGGRESSIVE OPTIMIZATION
            // ====================================================================
            buildTypes.release.proguard {
                isEnabled.set(true)
                optimize.set(true)
                obfuscate.set(false)

                configurationFiles.from(project.file("compose-desktop.pro"))

                // Additional runtime optimizations
                version.set(libs.versions.proguard.get())
            }

            packageName = "AuraLauncher"
            val cleanVersion = project.version.toString().removePrefix("v").substringBefore("-")

            val safeVersion = if (cleanVersion.matches(Regex("\\d+\\.\\d+.*"))) cleanVersion else "1.0.0"

            packageVersion = safeVersion
            description = "Aura Launcher v${project.version} (unofficial)"
            copyright = "© 2026 Hivens"
            vendor = "Hivens"

            // ====================================================================
            // CUSTOM MINIMAL JRE (saves ~120MB)
            // ====================================================================
            modules(
                "java.base",
                "java.desktop",
                "java.logging",
                "java.management",
                "java.naming",
                "java.net.http",
                "java.prefs",
                "java.sql",
                "jdk.crypto.ec",
                "jdk.unsupported",
                "jdk.zipfs"
            )

            windows {
                upgradeUuid = "30571060-3129-4503-b09e-716912389146"
                menuGroup = "Aura Launcher"
                shortcut = true
                dirChooser = true
                iconFile.set(project.file("src/commonMain/composeResources/drawable/icon.ico"))

                // perUserInstall removed — Inno Setup handles privilege escalation
                // via PrivilegesRequired=lowest in setup.iss
                console = false
            }

            linux {
                // packageName / debMaintainer / appCategory removed —
                // DEB and RPM are no longer shipped; AppImage is assembled in CI.
                iconFile.set(project.file("src/commonMain/composeResources/drawable/icon.png"))
            }

            macOS {
                bundleID = "com.hivens.auralauncher"
                dockName = "Aura Launcher"
            }
        }

        // ====================================================================
        // JVM ARGUMENTS OPTIMIZATION
        // ====================================================================
        jvmArgs(
            // Graphics optimization
            "-Dawt.useSystemAAFontSettings=on",
            "-Djdk.gtk.version=3",
            "-Dwayland.debug.children=true",
            "-D_JAVA_AWT_WM_NONREPARENTING=1",
            "-Drobot.need_x11=false",

            // Performance flags
            "-XX:+UseG1GC",
            "-XX:+UseStringDeduplication",
            "-XX:+OptimizeStringConcat",
            "-XX:+UseCompressedOops",

            // Startup optimization
            "-XX:TieredStopAtLevel=1",
            "-XX:+TieredCompilation",

            // Memory optimization
            "-Xms128m",
            "-Xmx512m",
            "-XX:MaxMetaspaceSize=256m",
            "-XX:ReservedCodeCacheSize=128m",

            // Security
            "--enable-native-access=ALL-UNNAMED",

            // prevent JNA native lib version conflict
            "-Djna.nosys=true"
        )
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "hivens.ui.generated.resources"
    generateResClass = always
}

// ========================================================================
// KOTLIN COMPILER OPTIMIZATIONS
// ========================================================================
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)

        freeCompilerArgs.addAll(
            // Language features
            "-XXLanguage:+NestedTypeAliases",

            // Backend optimizations
            "-jvm-default=no-compatibility",
            "-Xlambdas=indy",

            // Disable debug features
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:liveLiterals=false",

            // Metrics (optional, for analysis)
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=${project.layout.buildDirectory.get().asFile.absolutePath}/compose_metrics",
            "-P", "plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=${project.layout.buildDirectory.get().asFile.absolutePath}/compose_reports",

            // Aggressive inline
            "-Xinline-classes",
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }
}

// ========================================================================
// JAR OPTIMIZATION
// ========================================================================
tasks.withType<Jar>().configureEach {
    // Remove debug metadata
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("**/*.kotlin_metadata")
    exclude("**/*.kotlin_builtins")
    exclude("DebugProbesKt.bin")
    exclude("META-INF/proguard/**")
    exclude("META-INF/com.android.tools/**")

    // Compression
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
