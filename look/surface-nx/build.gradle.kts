plugins {
    id("nexira.look")
    alias(libs.plugins.kotlin.serialization)
}

// The nx surface provider: it answers the surface axis of :look:surface-api with nx's planes, which
// nx-ui keeps (nxSurfaceAnswers) because its previews draw with them too, and draws no blur where the
// person switched it off. That choice is this module's own setting, in its own settings domain,
// answered before the host is up from the same file, so the boot window's glass is the shell's. Compiled
// into the application, and brought in by the look manager when the person's choice names nx for
// surface. Its keys are what the blur switch of :look:surface-nx-controls reads and fires.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":widget-api"))
        implementation(project(":look:surface-api"))
        implementation(project(":nx-ui"))
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
