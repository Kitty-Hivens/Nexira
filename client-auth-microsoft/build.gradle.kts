plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // SessionData / AuthStatus / AuthException / HttpClientProvider live in core.
    implementation(project(":client-core"))
    // AuthProvider SPI + DeviceCodeAuthProvider capability interface.
    implementation(project(":client-auth"))

    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
}
