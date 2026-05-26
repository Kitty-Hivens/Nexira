plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    implementation(project(":widget-api"))
    implementation(libs.ksp.api)
}
