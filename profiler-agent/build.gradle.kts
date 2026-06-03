plugins {
    java
}

// This jar is a -javaagent that loads into the GAME JVM, not the launcher.
// Legacy SmartyCraft packs (1.7.10 / 1.12.2) run on Java 8, so the agent must
// be Java 8 bytecode (class major 52) or it throws UnsupportedClassVersionError
// there. The root build.gradle.kts forces Java 25 on every subproject inside an
// afterEvaluate; this block runs after it (callbacks fire in registration order,
// and the root registers its callback before this script is evaluated), pinning
// the agent back to 8. `options.release` makes javac ignore the inherited
// source/target=25. Verify after a build with:
//   javap -v build/classes/java/main/hivens/profiler/agent/ProfilerAgent.class | grep major
// -> must read "major version: 52".
afterEvaluate {
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(8)
    }
}

// First custom-manifest jar in the repo. Premain-Class is what the JVM looks up
// when the launcher passes -javaagent:<this-jar>.
tasks.jar {
    manifest {
        attributes("Premain-Class" to "hivens.profiler.agent.ProfilerAgent")
    }
}
