plugins {
    id("nexira.look")
}

// What previews and tests draw with: the PreviewLook with nx's answers and the icon library the
// launcher ships. On the class path of previews and tests only, as runtimeOnly, and in no application.
// It knows no component library and neither does anything it depends on, nx-ui least of all, which has
// it on that class path.
kotlin.sourceSets.named("jvmSharedMain") {
    dependencies {
        implementation(project(":look:preview-api"))
        implementation(project(":look:motion-api"))
        implementation(project(":look:motion-nx"))
        implementation(project(":look:shape-api"))
        implementation(project(":look:shape-nx"))
        implementation(project(":look:stroke-api"))
        implementation(project(":look:stroke-nx"))
        implementation(project(":look:surface-api"))
        implementation(project(":look:surface-nx"))
        implementation(project(":look:timing-api"))
        implementation(project(":look:timing-nx"))
        implementation(project(":look:feedback-api"))
        implementation(project(":look:feedback-nx"))
        implementation(project(":look:type-api"))
        implementation(project(":look:type-nx"))
        implementation(project(":look:colour-api"))
        // The theme previews are drawn in, whose type the tests take too.
        api(project(":look:colour-nx-api"))
        implementation(project(":look:icon-api"))
        implementation(project(":look:icon-google-rounded"))
        implementation(libs.compose.runtime)
    }
}
