plugins {
    id("nexira.look")
}

// What previews and tests draw with beyond what nx-ui answers itself: the PreviewLook with the icon
// library the launcher ships. On the class path of previews and tests only, as runtimeOnly, and in no
// application. It knows no component library, nx-ui least of all, which has it on that class path.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        implementation(project(":look:preview-api"))
        implementation(project(":look:icon-api"))
        implementation(project(":look:icon-google-rounded"))
        implementation(libs.compose.runtime)
    }
}
