plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose") version "1.9.3"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
    id("com.github.gmazzo.buildconfig")
}

group = "hivens"

repositories {
    google()
    mavenCentral()
    maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
}

kotlin {
    // Объявляем цель Desktop (JVM)
    jvm("desktop")

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(compose.runtime)
                implementation(compose.foundation)
                implementation(compose.material)
                implementation(compose.ui)
                implementation(compose.components.resources)
                implementation(compose.materialIconsExtended)

                // Coil (Multiplatform)
                implementation("io.coil-kt.coil3:coil-compose:3.3.0")
                implementation("io.coil-kt.coil3:coil-network-okhttp:3.0.4")
            }
        }

        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)

                // Проектные зависимости (оставляем здесь, так как они JVM)
                implementation(project(":client-config"))
                implementation(project(":client-core"))
                implementation(project(":client-launcher"))

                // Koin для Desktop
                implementation("io.insert-koin:koin-core:3.5.3")
                implementation("io.insert-koin:koin-compose:1.1.2")

                // Desktop-specific Coroutines
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")

                implementation("ch.qos.logback:logback-classic:1.4.14")
            }
        }
    }
}

// Настройка генератора конфига
buildConfig {
    packageName("hivens.ui") // Пакет, где будет лежать класс
    buildConfigField("String", "FORK_VERSION", "\"${project.version}\"")
    buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
    buildConfigField("String", "APP_NAME", "\"Aura Launcher\"")
}

compose.desktop {
    application {
        mainClass = "hivens.ui.MainKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Dmg,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Exe,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage
            )
            packageName = "AuraLauncher"

            val cleanVersion = project.version.toString().removePrefix("v").substringBefore("-")
            // Если версия начинается с "0." (например 0.1.0), превращаем её в 1.0.0
            // Иначе оставляем как есть.
            val safeVersion = if (cleanVersion.startsWith("0") || cleanVersion.isEmpty()) "1.0.0" else cleanVersion

            
            println("Packaging version: $safeVersion (Original: ${project.version})")

            packageVersion = safeVersion

            description = "Aura Launcher v${project.version}"
            copyright = "© 2026 Hivens"
            vendor = "Hivens"

            linux {
                packageName = "aura-launcher"
                debMaintainer = "hivens@smartycraft.ru"
                appCategory = "Game"
            }
        }
    }
}
