plugins {
    id("nexira.look")
}

// The nx timing provider: it answers the timing axis of :look:timing-api with nx's waits
// (nxTimingAnswers). It has no settings and no keys, so it answers the same before the host is up and
// after. Compiled into the application, and brought in by the look manager when the person's choice
// names nx for timing.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":widget-api"))
        implementation(project(":look:timing-api"))
        implementation(libs.compose.runtime)
    }
}

kotlin.sourceSets.named("jvmTest") {
    dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
