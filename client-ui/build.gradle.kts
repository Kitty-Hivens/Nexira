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
    id("org.jetbrains.compose") version "1.11.0-alpha01"
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
                implementation("org.jetbrains.compose.material:material:$composeVersion")
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
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi
            )

            buildTypes.release.proguard {
                isEnabled = true
                optimize = true
                configurationFiles.from(project.file("compose-desktop.pro"))
            }

            packageName = "AuraLauncher"
            val cleanVersion = project.version.toString().removePrefix("v").substringBefore("-")
            val safeVersion = if (cleanVersion.startsWith("0") || cleanVersion.isEmpty()) "1.0.0" else cleanVersion

            packageVersion = safeVersion
            description = "Aura Launcher v${project.version}"
            copyright = "© 2026 Hivens"
            vendor = "Hivens"

            linux {
                packageName = "aura-launcher"
                debMaintainer = "https://github.com/Kitty-Hivens"
                appCategory = "Game"
            }
        }

        jvmArgs(
            "-Dawt.useSystemAAFontSettings=on",
            "-Djdk.gtk.version=3",
            "-Dwayland.debug.children=true",
            "-Dsun.java2d.uiScale=1",
            "-D_JAVA_AWT_WM_NONREPARENTING=1",
            "--enable-native-access=ALL-UNNAMED",
            "-Djdk.gtk.verbose=true",
            "-Dnet.java.awt.embedded=true",
            "-Dskiko.render.backend=SOFTWARE",
            "-Drobot.need_x11=false"
        )
    }
}

// Конфигурация компилятора K2 под JDK 25
tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
        freeCompilerArgs.addAll(
            "-Xbackend-threads=0",
            "-Xtype-optimizations",
            "-Xjvm-default=all",
            "-Xlambdas=indy"
        )
    }
}

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
