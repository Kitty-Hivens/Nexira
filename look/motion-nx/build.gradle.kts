plugins {
    id("nexira.look")
    alias(libs.plugins.kotlin.serialization)
}

// The nx motion provider: it answers the motion axis of :look:motion-api with nx's durations and
// curves (nxMotionAnswers), and holds still what only decorates where the person asked for less
// movement. That choice is this module's own setting, in its own settings domain, answered before the
// host is up from the same file, so the boot window moves as the shell then does. Compiled into the
// application, and brought in by the look manager when the person's choice names nx for motion. Its
// keys are what the reduce motion switch of :look:motion-nx-controls reads and fires.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":widget-api"))
        implementation(project(":look:motion-api"))
        implementation(libs.compose.runtime)
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.slf4j.api)
    }
}

kotlin.sourceSets.named("jvmTest") {
    dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
