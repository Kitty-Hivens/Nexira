plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.0"
    `java-test-fixtures`
}

dependencies {
    implementation(project(":client-config"))

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.12")

    // Ktor Client & Serialization
    val ktorVersion = "3.3.3"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // TEST
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.slf4j:slf4j-simple:2.0.12")
    testFixturesImplementation("io.ktor:ktor-client-core:$ktorVersion")
    testFixturesImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testFixturesImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testFixturesImplementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
}
