plugins {
    id("nexira.look")
}

// The nx provider of the cursor axis: the system's pointers (:look:cursor-system), which it stands on by
// name, with what nx answers otherwise, nothing so far. It has no settings and no keys. Compiled into the
// application, and brought in by the look manager when the person's choice names nx for cursor.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        api(project(":widget-api"))
        implementation(project(":look:cursor-api"))
        implementation(libs.compose.runtime)
        implementation(libs.compose.ui)
    }
}

kotlin.sourceSets.named("jvmTest") {
    dependencies {
        implementation(libs.kotlinx.coroutines.test)
    }
}
