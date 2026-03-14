plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.3.20-RC3"
    `java-test-fixtures`
}

val ktorVersion: String by project
val slf4jVersion: String by project
val coroutinesVersion: String by project
val mockkVersion: String by project
val serializationVersion: String by project

dependencies {
    implementation(project(":client-config"))

    // Logging
    implementation("org.slf4j:slf4j-api:$slf4jVersion")

    // Ktor Client & Serialization
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")

    // TEST
    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$coroutinesVersion")
    testImplementation("io.mockk:mockk:$mockkVersion")
    testImplementation("org.slf4j:slf4j-simple:$slf4jVersion")

    testFixturesImplementation("io.ktor:ktor-client-core:$ktorVersion")
    testFixturesImplementation("io.ktor:ktor-client-mock:$ktorVersion")
    testFixturesImplementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    testFixturesImplementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
}

tasks.test {
    useJUnitPlatform()
}
