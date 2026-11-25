plugins {
    id("nexira.look")
}

// The nx feedback provider: it answers the feedback axis of :look:feedback-api with nx's state layers
// (nxFeedbackAnswers). It has no settings and no keys, so it answers the same before the host is up
// and after. Compiled into the application, and brought in by the look manager when the person's
// choice names nx for feedback.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":widget-api"))
        implementation(project(":look:feedback-api"))
        implementation(libs.compose.runtime)
        // The content colour a state layer takes where the widget names none.
        implementation(libs.compose.material3)
        implementation(libs.kotlinx.coroutines.core)
    }
}

kotlin.sourceSets.named("jvmTest") {
    dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
