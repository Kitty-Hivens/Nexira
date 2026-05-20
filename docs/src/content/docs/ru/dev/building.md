---
title: Сборка из исходников
description: Как собрать и запустить Nexira локально.
---

## Требования

- JDK 25+
- Gradle 9+ (или используй обёртку `./gradlew`)

## Клонирование и запуск

```bash
git clone https://github.com/Kitty-Hivens/Nexira.git
cd Nexira

# Запуск в режиме разработки
./gradlew :client-ui:run

# Тесты
./gradlew :client-core:test :client-launcher:test
```

## Сборка релизных артефактов

```bash
# Windows — папка с дистрибутивом (для Inno Setup)
./gradlew :client-ui:createReleaseDistributable

# macOS — DMG
./gradlew :client-ui:packageReleaseDmg

# Linux — uber JAR (AppImage собирается вручную в CI)
./gradlew :client-ui:packageReleaseUberJarForCurrentOS
```

## CI / Pipeline релизов

Релизы собираются автоматически в `.github/workflows/build_release.yml` при пуше тега (`v*.*.*`).

Процесс:
1. Запуск тестов — сборка падает если тесты не прошли
2. Параллельная сборка Windows EXE, Portable ZIP, Linux AppImage, macOS DMG
3. Генерация SHA256 контрольных сумм
4. Публикация релиза GitHub с changelog из `CHANGELOG.md`

## Замечания по платформам

:::caution[JNA на Windows]
JNA зафиксирован на `5.18.1` в `client-ui` и принудительно установлен на `6.1.6` глобально через Gradle `resolutionStrategy`. Не меняй — dorkbox/SystemTray 4.4 имеет жёстко заданную проверку версии.
:::

:::note[AppImage на Linux]
Собирается вручную в CI через `appimagetool` с минимальным JRE от `jlink`. Не через встроенную упаковку Compose. Смотри job `build-linux` в `build_release.yml`.
:::
