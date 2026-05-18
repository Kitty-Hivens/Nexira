plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.buildconfig)
}

// BUILD_TIME derives from `git log -1 --format=%ct HEAD` (Unix epoch seconds
// of the last commit, *1000 for millis). Previous value -- System.currentTimeMillis()
// at config evaluation -- froze inside Gradle's configuration cache until the
// next invalidation, so About-screen showed "build time = N hours ago" that
// never updated even as the user re-ran `./gradlew :client-ui:run`. Git commit
// time is also more honest: "this binary represents code committed at T", not
// "this binary went through Gradle config at T". Falls back to wall clock when
// git is missing (CI checkouts without history, distro packagers, etc.) so
// the field always has a value.
val gitCommitTimeMillis: Long = runCatching {
    providers.exec {
        commandLine("git", "log", "-1", "--format=%ct", "HEAD")
    }.standardOutput.asText.get().trim().toLong() * 1000L
}.getOrElse { System.currentTimeMillis() }

buildConfig {
    packageName("hivens.config")
    buildConfigField("String", "FORK_VERSION", "\"${project.version}\"")
    buildConfigField("long",   "BUILD_TIME",   "${gitCommitTimeMillis}L")
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
