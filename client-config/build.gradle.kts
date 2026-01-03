plugins {
    kotlin("jvm")
    id("com.github.gmazzo.buildconfig")
}

// Настраиваем генерацию класса
buildConfig {
    // Указываем пакет, куда сгенерируется класс
    packageName("hivens.config")

    // Генерируем поле VERSION, беря его из project.version
    // project.version уже установлено в корневом скрипте
    buildConfigField("String", "FORK_VERSION", "\"${project.version}\"")

    // И время сборки...
    buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
}
