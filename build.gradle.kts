plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.buildconfig) apply false
    alias(libs.plugins.versions)
    id("java")
}

fun getGitVersion(providerFactory: ProviderFactory): String {
    return try {
        val version = providerFactory.exec {
            commandLine("git", "describe", "--tags", "--always", "--dirty")
        }.standardOutput.asText.get().trim()

        version.removePrefix("v")
    } catch (e: Exception) {
        println("Git version lookup failed: ${e.message}")
        "0.0.0-dev"
    }
}

// The property name MUST match `gradle.properties` (`appVersion=`). Reading
// "version" here was a name mismatch — the override never fired and the build
// always fell through to `git describe`, leaving BuildConfig.FORK_VERSION as
// e.g. `2.2.7-rc3-37-g5763371` instead of the intended `2.2.9`.
val appVersion = providers.gradleProperty("appVersion")
    .getOrElse(getGitVersion(providers))

allprojects {
    repositories {
        mavenCentral()
    }
    version = appVersion
    group = "hivens"
}

subprojects {
    afterEvaluate {
        if (plugins.hasPlugin("java")) {
            configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_25
                targetCompatibility = JavaVersion.VERSION_25
            }
        }
    }

    // ====================================================================
    // AGGRESSIVE BUILD OPTIMIZATIONS
    // ====================================================================
    tasks.withType<JavaCompile>().configureEach {
        options.apply {
            isFork = true
            isIncremental = true

            forkOptions.apply {
                memoryMaximumSize = "2g"
                jvmArgs = listOf(
                    "-XX:+UseParallelGC",
                    "-XX:CICompilerCount=2"
                )
            }

            compilerArgs.addAll(listOf(
                "-parameters"
            ))
        }
    }

    tasks.withType<Test>().configureEach {
        maxParallelForks = Runtime.getRuntime().availableProcessors()

        jvmArgs(
            "-Xmx1g",
            "-XX:+UseParallelGC"
        )
    }
}

// ========================================================================
// GRADLE DAEMON OPTIMIZATION
// ========================================================================
gradle.startParameter.apply {
    maxWorkerCount = Runtime.getRuntime().availableProcessors()
}

// dorkbox/SystemTray 4.4 has a hardcoded JNA version check; JBR 25 ships
// JNA 7.x natively. Pin the global resolution to whatever the catalog says.
val pinnedJnaVersion = libs.versions.jna.get()
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "net.java.dev.jna") {
            useVersion(pinnedJnaVersion)
            because("dorkbox/SystemTray requires exactly JNA $pinnedJnaVersion")
        }
    }
}
