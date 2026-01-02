plugins {
    kotlin("jvm") version "2.1.0" apply false
    id("java")
    id("com.github.gmazzo.buildconfig") version "5.3.5" apply false
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    afterEvaluate {
        if (plugins.hasPlugin("java")) {
            configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
    }

    dependencies {}
}
