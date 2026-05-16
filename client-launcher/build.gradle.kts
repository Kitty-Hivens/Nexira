plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    implementation(project(":client-core"))
    implementation(project(":client-config"))

    implementation(libs.commons.compress)
    implementation(libs.koin.core)
    implementation(libs.slf4j.api)
    // No JNA in client-launcher: Vault keyring and other native bindings here
    // use Project Panama (java.lang.foreign.*, JEP 454, finalized Java 22).
    // The remaining JNA presence in the project is in client-ui, transitively
    // via filekit (Win32 IFileDialog + GTK fallback). dorkbox/SystemTray was
    // dropped in 2.2.14 along with the dorkbox-specific JNA pin; what JNA we
    // still ship is filekit's responsibility, not ours.

    // Ktor Client & Serialization
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // ─── TEST ────────────────────────────────────────────────────────────────
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.slf4j.simple)

    // MockClientFactory and other test utilities from client-core
    testImplementation(testFixtures(project(":client-core")))
}

// Tags that the regular `tasks.test` set must NEVER run. The dev/maintenance
// probes attached to these tags hit real external systems (smartycraft.ru
// for `smoke`, the developer's Secret Service daemon for `live-keyring`,
// real Windows Credential Manager for `live-windows-keyring`) and need
// explicit operator intent before they fire. Each entry corresponds to a
// dedicated registering Test task below; adding a new live-probe should
// add its tag here AND its registering call -- the registerLiveProbeTask
// helper keeps that link visible.
//
// Note: JUnit Jupiter tag expressions support `|`, `&`, `!` but NOT glob
// wildcards. If the live-probe list grows large enough that the literal
// enumeration becomes maintenance load, the right next step is to re-tag
// every probe with a shared `live` tag (plus its specific subtag) so this
// list collapses to `excludeTags("smoke", "live")` -- not worth doing
// pre-emptively at two entries.
val excludedTestTags = listOf("smoke", "live-keyring", "live-windows-keyring")

tasks.test {
    useJUnitPlatform {
        excludeTags(*excludedTestTags.toTypedArray())
    }
    // Vault unit tests construct LinuxLibsecretKeyringStorage even when
    // isAvailable() ends up false, and Panama symbol lookup happens at
    // construction time. Without this flag, Linker.nativeLinker().downcallHandle
    // throws IllegalCallerException on JDK 22+. The same flag is baked into the
    // AppImage AppRun via scripts/build-appimage.sh so production runs have it.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Registers a live-probe Test task. All live probes share the same shape
// (single tag include, Panama --enable-native-access, always-run output
// policy, post-:test ordering); the helper exists to keep that shape in
// one place. Probes themselves are skipped at the @BeforeAll level via
// assumeTrue() when the required external resource is unavailable -- so
// running on the wrong OS or without the daemon installed is a green skip,
// not a failure.
fun registerLiveProbeTask(taskName: String, tag: String, taskDescription: String) =
    tasks.register<Test>(taskName) {
        description = taskDescription
        group = "verification"
        testClassesDirs = sourceSets.test.get().output.classesDirs
        classpath = sourceSets.test.get().runtimeClasspath
        useJUnitPlatform {
            includeTags(tag)
        }
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        outputs.upToDateWhen { false }
        shouldRunAfter(tasks.test)
    }

@Suppress("unused") // gradle task wiring -- accessed via `./gradlew liveKeyringTest`
val liveKeyringTest = registerLiveProbeTask(
    taskName = "liveKeyringTest",
    tag = "live-keyring",
    taskDescription = "Runs the LinuxLibsecretKeyringStorage live probe against the local Secret Service daemon.",
)

@Suppress("unused") // gradle task wiring -- accessed via `./gradlew liveWindowsKeyringTest`
val liveWindowsKeyringTest = registerLiveProbeTask(
    taskName = "liveWindowsKeyringTest",
    tag = "live-windows-keyring",
    taskDescription = "Runs WindowsCredentialManagerKeyringStorage live probe against the local Credential Manager service.",
)

