plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    // Protocol.AUTH_SALT / DEFAULT_JAR / DEFAULT_CSUM -- the SmartyCraft-specific
    // constants the V1 token + login request need.
    implementation(project(":client-config"))
    // IServerProtocol + protocol models + SessionData/AuthStatus live in core.
    implementation(project(":client-core"))
    // AuthProvider SPI + AbstractCachingAuthProvider base.
    implementation(project(":client-auth"))

    implementation(libs.slf4j.api)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.ktor.client.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.slf4j.simple)
    // FakeServerProtocol for the ported provider test.
    testImplementation(testFixtures(project(":client-core")))
}

tasks.test {
    useJUnitPlatform()
}
