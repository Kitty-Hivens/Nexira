plugins {
    java
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// This jar is a -javaagent that loads into the GAME JVM, not the launcher. Legacy
// SmartyCraft packs (1.7.10 / 1.12.2) run on Java 8, so the agent must be Java 8
// bytecode (class major 52) or it throws UnsupportedClassVersionError there. The
// root build.gradle.kts forces Java 25 on every subproject in an afterEvaluate;
// this block runs after it and pins the agent back to 8 (`options.release` makes
// javac ignore the inherited source/target=25). Deliberately zero-dependency
// (raw constant-pool rewrite, no ASM) so the agent jar stays self-contained.
afterEvaluate {
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(8)
    }
}

tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "hivens.authlib.agent.AuthlibRedirectAgent",
            "Can-Retransform-Classes" to "false",
        )
    }
}
