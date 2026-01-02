plugins {
    kotlin("jvm")
    kotlin("plugin.serialization") version "2.1.0"
}

dependencies {
    implementation(project(":client-core"))
    implementation(project(":client-config"))

    implementation("org.apache.commons:commons-compress:1.26.1")
    implementation("io.insert-koin:koin-core:3.5.3")
    implementation("org.slf4j:slf4j-api:2.0.12")

    // Ktor Client & Serialization
    val ktorVersion = "3.3.3"
    implementation("io.ktor:ktor-client-core:$ktorVersion")
    implementation("io.ktor:ktor-client-okhttp:$ktorVersion")
    implementation("io.ktor:ktor-client-content-negotiation:$ktorVersion")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktorVersion")
}
