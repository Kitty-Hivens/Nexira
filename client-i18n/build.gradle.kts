import hivens.android.launcherApi
import hivens.i18n.TranslationCheckTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

// The launcher's own words: the tables of the `nexira` namespace, one
// i18n/nexira/<tag>.properties per shipped language, which the language module
// (:i18n) answers lookups from, and the composable that reads them. A string edit
// is an edit to a table and recompiles nothing.
//
// Compose runtime only, for the composable lookup: nothing here draws.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose)
    alias(libs.plugins.kotlin.compose.compiler)
    alias(libs.plugins.android.kmp.library)
}

group = "hivens"

// Repositories live in settings.gradle.kts (FAIL_ON_PROJECT_REPOS); no block here.

kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin
    // (java-plugin modules only) skips them; set it here so org.gradle.jvm.version
    // matches the JDK 27 leaf modules instead of falling back to the daemon JVM.
    jvmToolchain(27)
    jvm("desktop")
    android {
        namespace = "hivens.ui.i18n"
        compileSdk { launcherApi(providers) }
        minSdk = 31
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    sourceSets {
        val jvmSharedMain = create("jvmSharedMain") {
            dependsOn(getByName("commonMain"))
        }
        getByName("desktopMain").dependsOn(jvmSharedMain)
        getByName("androidMain").dependsOn(jvmSharedMain)

        getByName("commonMain") {
            dependencies {
                implementation(libs.compose.runtime)
            }
        }

        getByName("jvmSharedMain") {
            dependencies {
                api(project(":i18n"))
            }
        }

        getByName("desktopTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

// jvmTarget is also forced project-wide by the root subprojects {} block; set it
// explicitly here too, matching nx-ui, so the module is correct in isolation.
tasks.withType<KotlinJvmCompile>().matching { !it.name.contains("Android") }.configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_27)
    }
}

// Warns, and never fails, about the tables: a key a language lacks, a key the code
// looks up that English does not have, a message that does not parse. Part of check,
// so build runs it. A malformed message also fails NexiraTablesTest: that one is a
// bug, where a missing translation is only a key shown as written.
val checkTranslations = tasks.register<TranslationCheckTask>("checkTranslations") {
    group = "verification"
    description = "Warns about missing and malformed translations of the launcher's own words."
    checker.from(configurations.named("desktopRuntimeClasspath"))
    tables.set(layout.projectDirectory.dir("src/jvmSharedMain/resources/i18n/nexira"))
    english.set("en")
    // The main sources of the modules that read the launcher's own words. A test may look
    // up a key of another namespace, a module's, which English here has no business with.
    listOf("client-ui", "android-app").forEach { module ->
        sources.from(layout.settingsDirectory.dir("$module/src").asFileTree.matching { include("*Main/**/*.kt", "main/**/*.kt") })
    }
}
tasks.named("check") { dependsOn(checkTranslations) }
