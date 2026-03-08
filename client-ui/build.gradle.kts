import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

val composeVersion: String by project
val iconsVersion: String by project
val coilVersion: String by project
val coilNetworkVersion: String by project
val filekitVersion: String by project
val koinVersion: String by project
val koinComposeVersion: String by project
val ktorVersion: String by project

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose") version "1.11.0-alpha02"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
    id("com.github.gmazzo.buildconfig")
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
                implementation("org.jetbrains.compose.runtime:runtime:$composeVersion")
                implementation("org.jetbrains.compose.foundation:foundation:$composeVersion")
                implementation("org.jetbrains.compose.material3:material3:${composeVersion}")
                implementation("org.jetbrains.compose.ui:ui:$composeVersion")
                implementation("org.jetbrains.compose.components:components-resources:$composeVersion")
                implementation("org.jetbrains.compose.material:material-icons-extended:$iconsVersion")

                implementation("io.coil-kt.coil3:coil-compose:$coilVersion")
                implementation("io.coil-kt.coil3:coil-network-okhttp:$coilNetworkVersion")
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)

                implementation(project(":client-config"))
                implementation(project(":client-core"))
                implementation(project(":client-launcher"))

                implementation("io.github.vinceglb:filekit-core:$filekitVersion")
                implementation("io.github.vinceglb:filekit-dialogs-compose:$filekitVersion")
                implementation("io.insert-koin:koin-core:$koinVersion")
                implementation("io.insert-koin:koin-compose:$koinComposeVersion")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
                implementation("ch.qos.logback:logback-classic:1.4.14")
            }
        }
    }
}

buildConfig {
    packageName("hivens.ui")
    buildConfigField("String", "FORK_VERSION", "\"${project.version}\"")
    buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    buildConfigField("String", "APP_NAME", "\"Aura Launcher\"")
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
                version.set("7.8.2")
            }

            packageName = "AuraLauncher"
            val cleanVersion = project.version.toString().removePrefix("v").substringBefore("-")
            val safeVersion = if (cleanVersion.startsWith("0") || cleanVersion.isEmpty()) "1.0.0" else cleanVersion

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
            "-XX:+UseCompressedClassPointers",

            // Startup optimization
            "-XX:TieredStopAtLevel=1",
            "-XX:+TieredCompilation",
            "-Xverify:none",

            // Memory optimization
            "-Xms128m",
            "-Xmx512m",
            "-XX:MaxMetaspaceSize=256m",
            "-XX:ReservedCodeCacheSize=128m",

            // Security
            "--enable-native-access=ALL-UNNAMED"
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
            ":client-config:generateBuildConfig",
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
