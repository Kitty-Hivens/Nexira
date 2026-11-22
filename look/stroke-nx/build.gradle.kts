plugins {
    id("nexira.look")
}

// The nx stroke provider: it answers the stroke axis of :look:stroke-api with nx's strokes, which
// nx-ui keeps (nxStrokeAnswers) because its previews draw with them too. It has no settings and no
// keys, so it answers the same before the host is up and after. Compiled into the application, and
// brought in by the look manager when the person's choice names nx for stroke.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":widget-api"))
        implementation(project(":look:stroke-api"))
        implementation(project(":nx-ui"))
        implementation(libs.compose.runtime)
    }
}

kotlin.sourceSets.named("jvmTest") {
    dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
