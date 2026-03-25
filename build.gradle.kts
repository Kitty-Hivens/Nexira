plugins {
    kotlin("jvm") version "2.3.20-RC3" apply false
    id("java")
    id("com.github.gmazzo.buildconfig") version "6.0.9" apply false
    id("com.github.ben-manes.versions") version "0.53.0"
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

val appVersion = providers.gradleProperty("version")
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
                "-Xlint:none",
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

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "net.java.dev.jna") {
            useVersion("6.1.6")
            because("dorkbox/SystemTray requires exactly JNA 6.1.6")
        }
    }
}
