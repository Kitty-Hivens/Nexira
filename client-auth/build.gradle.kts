plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // SessionData, AuthStatus, AuthException/TwoFactorRequiredException,
    // IServerProtocol + protocol models, HashUtils, retryWithBackoff -- all stay
    // in core; this module sits above core and stays SmartyCraft-agnostic.
    implementation(project(":client-core"))

    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.coroutines.core)

    // ktor-io's ClosedByteChannelException is one of the transient-network
    // failures the agnostic retry classifier recognises.
    implementation(libs.ktor.client.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
}
