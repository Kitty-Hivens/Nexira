---
title: Сборка из исходников
description: Как собрать и запустить Nexira локально.
---

## Требования

- Установленный JDK 27 любого поставщика. Демон Gradle обязан работать на нём, и Gradle находит его сам.
- Обёртка `./gradlew` из репозитория, она приносит свой Gradle.
- Только для приложения под Android: Android SDK, который находится через `ANDROID_HOME` или `local.properties`. Без него модуль Android выпадает из сборки, остальное собирается как обычно.

## Клонирование и запуск

```bash
git clone https://github.com/Kitty-Hivens/Nexira.git
cd Nexira

# Запуск в режиме разработки
./gradlew :client-ui:run

# Тесты одного модуля
./gradlew :client-launcher:test
```

Все модули с тестами перечислены в `.github/workflows/tests.yml`: это та самая команда, которую запускают проверки пул-реквестов.

## Сборка релизных артефактов

```bash
# Windows: образ приложения jpackage, из него Inno Setup (setup.iss) делает установщик
./gradlew :client-ui:customJpackageImage

# macOS: DMG
./gradlew :client-ui:customDmg

# Linux: uber JAR, который scripts/build-appimage.sh упаковывает в AppImage
./gradlew :client-ui:packageReleaseUberJarForCurrentOS

# Android: APK
./gradlew :android-app:assembleRelease
```

Упаковка запускается на той системе, под которую собирается: образ для Windows собирается на Windows, DMG на macOS.

## CI / Pipeline релизов

Релизы собирает `.github/workflows/build_release.yml` при пуше тега (`v*.*.*`).

Процесс:
1. Запуск тестов. Сборка падает, если хоть один тест не прошёл.
2. Параллельная сборка установщика и Portable ZIP для Windows, AppImage для Linux, DMG для macOS и APK для Android.
3. Генерация контрольных сумм SHA256.
4. Публикация релиза GitHub с описанием из `CHANGELOG.md`.

Ночные сборки делает `.github/workflows/nightly.yml`, каждую ночь из ветки разработки.

## Замечания по платформам

:::note[AppImage на Linux]
Собирается скриптом `scripts/build-appimage.sh` через `appimagetool` с рантаймом от `jlink`, а не встроенной упаковкой Compose. Смотри job `build-linux` в `build_release.yml`.
:::

:::note[Рантайм на Windows]
`jlink` берёт нативные файлы рантайма из jmods JDK без подписи. Сборка заменяет каждый из них подписанной копией из `bin` того же JDK, если это тот же файл без учёта подписи, поэтому рантайм уходит с подписями поставщика JDK.
:::
