plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.0"
}

dependencies {
    implementation(project(":client-core"))
    implementation(project(":client-config"))

    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("io.insert-koin:koin-core:3.5.3")
    implementation("org.slf4j:slf4j-api:2.0.12")

    // Ktor Client & Serialization
    val ktorVersion = "3.3.3"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // ─── TEST ────────────────────────────────────────────────────────────────
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.slf4j:slf4j-simple:2.0.12")
    // MockClientFactory и другие тестовые утилиты из client-core
    testImplementation(testFixtures(project(":client-core")))
}

tasks.test {
    useJUnitPlatform()
}
