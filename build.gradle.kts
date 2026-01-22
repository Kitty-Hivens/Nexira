plugins {
    kotlin("jvm") version "2.3.0" apply false
    id("java")
    id("com.github.gmazzo.buildconfig") version "5.3.5" apply false
}

// 1. Function to get version from Git (for local build)
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

// 2. Final version
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
}
