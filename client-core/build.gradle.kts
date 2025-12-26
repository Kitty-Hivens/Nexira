plugins {
    kotlin("jvm")
}

dependencies {
    implementation(project(":client-config"))

    // HTTP-клиент
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON-маппинг
    implementation("com.google.code.gson:gson:2.10.1")

    implementation("org.slf4j:slf4j-api:2.0.12")
}