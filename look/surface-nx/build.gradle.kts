plugins {
    id("nexira.look")
}

// The nx surface provider: it answers the surface axis of :look:surface-api with nx's planes, which
// nx-ui keeps (nxSurfaceAnswers) because its previews draw with them too. It has no settings and no
// keys, so it answers the same before the host is up and after. Compiled into the application, and
// brought in by the look manager when the person's choice names nx for surface.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":widget-api"))
        implementation(project(":look:surface-api"))
        implementation(project(":nx-ui"))
        implementation(libs.compose.runtime)
    }
}

kotlin.sourceSets.named("jvmTest") {
    dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
