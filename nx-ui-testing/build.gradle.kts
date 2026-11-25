plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose.compiler)
}

// What the test source sets that draw with :nx-ui share: a render of a component in one theme, and the
// thread a scene is drawn on. A module
// of its own because a theme is the colour provider's and :nx-ui knows none, and because
// java-test-fixtures has no equivalent in a Kotlin Multiplatform module. Test-only: never a dependency
// of anything that ships.
dependencies {
    api(project(":nx-ui"))
    api(project(":look:colour-nx-api"))
    api(project(":look:preview"))
    implementation(project(":look:preview-api"))
    implementation(libs.compose.runtime)
}
