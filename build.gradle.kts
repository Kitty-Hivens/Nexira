plugins {
    kotlin("jvm") version "2.1.0" apply false
    id("java")
}

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    // УДАЛЕНО: apply(plugin = "org.jetbrains.kotlin.jvm") <-- Убираем эту строку

    // Оставляем настройки Java (они применятся, если в модуле подключен java плагин)
    afterEvaluate {
        // Используем afterEvaluate, чтобы настройки применились ПОСЛЕ того,
        // как модуль подключит плагин
        if (plugins.hasPlugin("java")) {
            configure<JavaPluginExtension> {
                sourceCompatibility = JavaVersion.VERSION_21
                targetCompatibility = JavaVersion.VERSION_21
            }
        }
    }

    dependencies {
        // Зависимости тоже лучше перенести в модули, но если хотите оставить здесь:
        // implementation(kotlin("stdlib-jdk8")) <-- Это лучше убрать, Kotlin 1.4+ добавляет сам

        // Lombok и логирование можно оставить, если они нужны везде
        // Но для KMP (client-ui) Lombok не сработает.
        // Лучше перенести это в конкретные модули.
    }
}
