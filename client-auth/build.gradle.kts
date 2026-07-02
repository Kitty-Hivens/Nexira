plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    // SessionData, AuthStatus, AuthException/TwoFactorRequiredException,
    // IServerProtocol + protocol models, HashUtils, retryWithBackoff -- all stay
    // in core; this module sits above core and stays SmartyCraft-agnostic.
    implementation(project(":client-core"))

    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.coroutines.core)
    // credentials.json (the non-secret account list) is a @Serializable envelope.
    implementation(libs.kotlinx.serialization.json)
    // api (not implementation): SecretVault sits in CredentialsManager's public
    // constructor, so the assembler constructing it needs the type.
    api(libs.libvault)

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
