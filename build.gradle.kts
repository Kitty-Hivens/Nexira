plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose.compiler) apply false
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.buildconfig) apply false
    alias(libs.plugins.versions)
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

// Repositories centralized in settings.gradle.kts (dependencyResolutionManagement
// with FAIL_ON_PROJECT_REPOS). Only version + group are set per-project here.
allprojects {
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
        // Kotlin's compilerOptions.jvmTarget defaults to JVM_1_8 if a
        // subproject's build script does not set it. A new module added
        // without explicit kotlin { jvmToolchain(25) } / compilerOptions {
        // jvmTarget = JVM_25 } would silently produce JVM 1.8 bytecode
        // while loading Java 25 classes from dependencies -- an at-runtime
        // LinkageError waiting to happen, invisible until a 9+-only API
        // gets touched. Force-set on every Kotlin/JVM compile task so the
        // bytecode floor always matches the Java target above.
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
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
        //
        // providers.environmentVariable() registers the env-var read with
        // the configuration cache, so a flipped CI value invalidates the
        // cache correctly. System.getenv() bypasses that wiring and silently
        // bakes the value into the cache forever on first config-resolve.
        //
        // Local cap of 2 forks x 512MB heap = ~1GB peak -- leaves modern
        // dev machines (8+ cores) plenty of headroom for IDE/browser/etc.
        val cores = Runtime.getRuntime().availableProcessors()
        val isCi = providers.environmentVariable("CI").map { it == "true" }.orElse(false).get()
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
    // Same CI-vs-local split as the per-subproject Test config above --
    // local builds shouldn't pin every CPU every time the daemon spins up.
    // 4 workers is enough to parallelise most subproject compiles without
    // thermal-throttling the laptop. See the Test block above for why
    // providers.environmentVariable beats System.getenv here (config cache
    // wiring).
    val cores = Runtime.getRuntime().availableProcessors()
    val isCi = providers.environmentVariable("CI").map { it == "true" }.orElse(false).get()
    maxWorkerCount = if (isCi) cores else minOf(4, cores)
}

// JNA pin removed alongside dorkbox/SystemTray (replaced by libtray, the
// pure-Panama tray library at github.com/Kitty-Hivens/libtray). The
// pin existed only to satisfy dorkbox's hardcoded JNA version check;
// libtray uses java.lang.foreign and never pulls in net.java.dev.jna.
// JBR 25's bundled JNA 7.x can resolve naturally now.
