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
        // libtray (Kitty-Hivens/libtray) is consumed via JitPack until it
        // cuts a real Maven Central release. Pinned by commit sha in
        // libs.versions.toml so an upstream main-branch break doesn't
        // silently drift the build. Switches to mavenCentral coordinates
        // once libtray ships 0.1.0 + verifies its sonatype namespace.
        maven { url = uri("https://jitpack.io") }
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
        // CI wants maximum throughput (free runners, ephemeral); local
        // dev wants the laptop to stay usable while tests run. Detect via
        // the `CI` env var that GH Actions / most CI providers set.
        // Local cap of 2 forks × 512MB heap = ~1GB peak — leaves modern
        // dev machines (8+ cores) plenty of headroom for IDE/browser/etc.
        val cores = Runtime.getRuntime().availableProcessors()
        val isCi  = System.getenv("CI") == "true"
        maxParallelForks = if (isCi) cores else minOf(2, cores)

        jvmArgs(
            if (isCi) "-Xmx1g" else "-Xmx512m",
            "-XX:+UseParallelGC"
        )
    }
}

// ========================================================================
// GRADLE DAEMON OPTIMIZATION
// ========================================================================
gradle.startParameter.apply {
    val cores = Runtime.getRuntime().availableProcessors()
    val isCi  = System.getenv("CI") == "true"
    // Same CI-vs-local split — local builds shouldn't pin every CPU
    // every time the daemon spins up. 4 workers is enough to parallelise
    // most subproject compiles without thermal-throttling the laptop.
    maxWorkerCount = if (isCi) cores else minOf(4, cores)
}

// JNA pin removed alongside dorkbox/SystemTray (replaced by libtray, the
// pure-Panama tray library at github.com/Kitty-Hivens/libtray). The
// pin existed only to satisfy dorkbox's hardcoded JNA version check;
// libtray uses java.lang.foreign and never pulls in net.java.dev.jna.
// JBR 25's bundled JNA 7.x can resolve naturally now.
