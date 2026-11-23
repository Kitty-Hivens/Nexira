plugins {
    id("nexira.look")
}

// The nx feedback provider: it answers the feedback axis of :look:feedback-api with nx's state layers,
// which nx-ui keeps (nxFeedbackAnswers) because its previews draw with them too. It has no settings and
// no keys, so it answers the same before the host is up and after. Compiled into the application, and
// brought in by the look manager when the person's choice names nx for feedback.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":widget-api"))
        implementation(project(":look:feedback-api"))
        implementation(project(":nx-ui"))
        implementation(libs.compose.runtime)
    }
}

kotlin.sourceSets.named("jvmTest") {
    dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
