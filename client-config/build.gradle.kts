import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.buildconfig)
    alias(libs.plugins.android.kmp.library)
}

// BUILD_TIME derives from `git log -1 --format=%ct HEAD` (Unix epoch seconds
// of the last commit, *1000 for millis). Previous value -- System.currentTimeMillis()
// at config evaluation -- froze inside Gradle's configuration cache until the
// next invalidation, so About-screen showed "build time = N hours ago" that
// never updated even as the user re-ran `./gradlew :client-ui:run`. Git commit
// time is also more honest: "this binary represents code committed at T", not
// "this binary went through Gradle config at T". Falls back to wall clock when
// git is missing (CI checkouts without history, distro packagers, etc.) so
// the field always has a value.
val gitCommitTimeMillis: Long = runCatching {
    providers.exec {
        commandLine("git", "log", "-1", "--format=%ct", "HEAD")
    }.standardOutput.asText.get().trim().toLong() * 1000L
}.getOrElse { System.currentTimeMillis() }

buildConfig {
    packageName("hivens.config")
    buildConfigField("String", "FORK_VERSION", "\"${project.version}\"")
    buildConfigField("long",   "BUILD_TIME",   "${gitCommitTimeMillis}L")
}

kotlin {
    // KMP modules don't apply the `java` plugin, so the root's toolchain pin
    // skips them; set it here so org.gradle.jvm.version matches the JDK 27 modules.
    jvmToolchain(27)
    jvm()
    android {
        namespace = "hivens.config"
        compileSdk = providers.gradleProperty("nexira.android.compileSdk").map(String::toInt).getOrElse(37)
        minSdk = 31
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_25)
        }
    }

    sourceSets {
        // The code both JVM targets share: java.* and System properties exist on
        // ART as well, but a common source set cannot see them.
        val jvmSharedMain = create("jvmSharedMain") {
            dependsOn(getByName("commonMain"))
        }
        getByName("jvmMain").dependsOn(jvmSharedMain)
        getByName("androidMain").dependsOn(jvmSharedMain)

        getByName("jvmTest") {
            dependencies {
                implementation(kotlin("test"))
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
