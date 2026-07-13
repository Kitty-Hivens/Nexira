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
        }.standardOutput.asText.get().trim().removePrefix("v")

        // `--always` falls back to a bare commit SHA when no tag is reachable
        // (the test CI does a shallow, no-tags checkout). A SHA carries no dotted
        // version component, and an all-numeric short SHA would otherwise parse as
        // a single huge version number and invert every version comparison. Treat
        // "no reachable tag" as an unknown 0.0.0 build (no prerelease suffix, so
        // it stays a clean lower bound for comparisons).
        if (version.contains('.')) version else "0.0.0"
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
                sourceCompatibility = JavaVersion.VERSION_26
                targetCompatibility = JavaVersion.VERSION_26
                // Vendor-loose toolchain pin: any JDK 26 distribution works
                // (Liberica is the project's documented choice and what CI
                // pulls via JAVA_DISTRIBUTION=liberica, but Temurin / Zulu /
                // Microsoft / etc. are all fine locally). Pinning the
                // languageVersion is what makes new contributors with
                // JDK <26 on PATH auto-download a 26 via the
                // foojay-resolver-convention plugin in settings.gradle.kts
                // instead of failing with cryptic "no matching toolchain"
                // errors. Game-side JRE is provisioned separately by
                // JavaManagerService (Liberica) and is independent of this.
                toolchain {
                    languageVersion.set(JavaLanguageVersion.of(26))
                }
            }
        }
        // Kotlin's compilerOptions.jvmTarget defaults to JVM_1_8 if a
        // subproject's build script does not set it. A new module added
        // without explicit kotlin { jvmToolchain(26) } / compilerOptions {
        // jvmTarget = JVM_26 } would silently produce JVM 1.8 bytecode
        // while loading Java 26 classes from dependencies -- an at-runtime
        // LinkageError waiting to happen, invisible until a 9+-only API
        // gets touched. Force-set on every Kotlin/JVM compile task so the
        // bytecode floor always matches the Java target above.
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
            compilerOptions {
                jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26)
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

    // Stable jar file names. project.version is a `git describe` string that
    // changes almost every commit, so the default `<module>-<version>.jar`
    // name makes build/libs accumulate a new jar per build and never drop the
    // old ones. A classpath that then resolves an older jar loads stale
    // classes: a type added in a later commit reads as NoClassDefFoundError
    // while its older package-mates load fine. Dropping the version from the
    // file name overwrites a single <module>.jar each build. The real version
    // still rides in project.version (Implementation-Version, BuildConfig).
    tasks.withType<Jar>().configureEach {
        archiveVersion.set("")
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
// pure-Panama tray library at github.com/Kitty-Hivens/libtray). The pin
// existed only to satisfy dorkbox's hardcoded JNA version check; libtray
// uses java.lang.foreign and never pulls in net.java.dev.jna. JNA still
// enters the graph transitively via filekit (only on Windows), pinned at
// 5.18.1 in client-ui/build.gradle.kts; no root-level pin needed.
