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
// root build.gradle.kts forces Java 26 on every subproject in an afterEvaluate;
// this block runs after it and pins the agent back to 8 (`options.release` makes
// javac ignore the inherited source/target=26). Deliberately zero-dependency
// (raw constant-pool rewrite, no ASM) so the agent jar stays self-contained.
// Scoped to the shipped classes. The tests run in the Gradle test JVM, never in
// the game's, so holding them at 8 only kept the whole build's JUnit on a line
// old enough to still support Java 8.
afterEvaluate {
    tasks.named<JavaCompile>("compileJava") {
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
