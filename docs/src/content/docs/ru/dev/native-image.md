---
title: Нативная сборка
description: Сборка headless-CLI Nexira в нативный бинарь под Linux через GraalVM / Liberica NIK, и почему Compose-GUI пока нельзя собрать нативно.
---

Пайплайн запуска Nexira (авторизация, разрешение пака, скачивание/проверка,
провижининг JRE, разрешение рантайма, старт игры) свободен от Compose и живёт в
`:client-core` / `:client-launcher` / `:client-auth*`. Модуль `:client-cli` — это
headless-вход над этим пайплайном, собираемый в нативный бинарь под Linux через
GraalVM / Liberica NIK. Compose-GUI (`:client-ui`) остаётся на JVM — см.
[Почему не GUI](#почему-не-gui).

## Что получаешь

Один самодостаточный исполняемый файл `nexira-cli`: JVM в рантайме не нужна,
холодный старт ~10 мс (против ~130 мс у JVM-лаунчера), ~55 МБ. Гоняет тот же
launcher-core, что и GUI, включая OS keyring по D-Bus через Panama-биндинги
libvault.

```
nexira-cli list                              # установленные инстансы паков
nexira-cli launch <packId>                   # offline-запуск
nexira-cli launch <packId> --provider smartycraft   # переиспользует аккаунт из GUI
nexira-cli launch <packId> --dry-run         # разрешить + вывести план, без старта
nexira-cli version | help
```

## Требования

- Дистрибутив GraalVM 25 с `native-image`. Liberica NIK — задокументированный
  выбор; подойдёт любой GraalVM 25 (headless-CLI не использует вендор-специфичных
  флагов).
  - SDKMAN (Liberica NIK): `sdk install nik` -> ставится в
    `~/.sdkman/candidates/nik/current`.
  - Либо пакет дистрибутива / tarball (например, Arch `jdk25-graalvm-bin`).
- C-тулчейн для финальной линковки: `gcc` + заголовки `zlib`
  (`base-devel` на Arch, `build-essential zlib1g-dev` на Debian/Ubuntu).
- ~4 ГБ свободной RAM и пара минут — `native-image` работает отдельным процессом,
  не упирается в Gradle/Kotlin-демоны.

Сборка ищет `native-image` вендор-агностично: свойство `nativeImage { graalvmHome }`,
иначе `GRAALVM_HOME`, иначе `NATIVE_IMAGE_HOME`, иначе скан `/usr/lib/jvm`
(предпочитая Liberica NIK, если их несколько), иначе `PATH`. Задай `GRAALVM_HOME`,
если автодетект выбрал не тот:

```sh
export GRAALVM_HOME="$HOME/.sdkman/candidates/nik/current"
```

## Сборка и запуск

```sh
GRAALVM_HOME=... ./gradlew :client-cli:nativeImage
./client-cli/build/nativeImage/nexira-cli list
```

Бинарь — `client-cli/build/nativeImage/nexira-cli`. Только Linux x86-64;
`native-image` не кросс-компилирует, собирай на целевой платформе.

### Целевой CPU (`-march`)

native-image зашивает набор инструкций CPU на этапе сборки (в отличие от JIT в
JVM, который адаптируется в рантайме). По умолчанию — `x86-64-v3` (нужен AVX2,
~2013+). Переопредели через `-PnativeMarch=<значение>`:

```sh
./gradlew :client-cli:nativeImage -PnativeMarch=native      # быстрее всего, только ЭТОТ CPU
./gradlew :client-cli:nativeImage -PnativeMarch=x86-64-v2   # портируемый пол для раздачи
```

`-march=native` словит SIGILL на любом CPU старше машины сборки — не раздавай
такой бинарь; для раздачи бери пол (`x86-64-v2`/`v3`). `native-image -march=list`
покажет опции. Для I/O-bound лаунчера разница мала; PGO и startup важнее `-march`.

## Метаданные достижимости

`native-image` собирается с `--no-fallback`: всё, что reflection-, resource-,
ServiceLoader- или FFM-зависимое, должно быть объявлено, иначе сборка падает (для
большинства видов — на сборке, для незарегистрированных FFM-downcall'ов — на
первом вызове). Закоммиченные метаданные лежат в
`client-cli/src/main/resources/META-INF/native-image/hivens/nexira-cli/` и
собираются tracing-агентом GraalVM, а не пишутся руками.

Процесс (агент мержит прогоны, так что прогоняй разные пути):

```sh
GRAALVM_HOME=... ./gradlew :client-cli:nativeImageAgentRun                                   # по умолчанию: 'list'
GRAALVM_HOME=... ./gradlew :client-cli:nativeImageAgentRun -PnativeAgentArgs="launch <id> --dry-run"
# smartycraft dry-run дёргает keyring libvault -> ловит FFM-downcall'ы:
GRAALVM_HOME=... ./gradlew :client-cli:nativeImageAgentRun -PnativeAgentArgs="launch <id> --provider smartycraft --dry-run"

./gradlew :client-cli:nativeImageMetadataCopy   # build/native/agent-output -> ресурсы
git diff client-cli/src/main/resources/META-INF/native-image   # просмотри, потом коммить
```

Перегенерируй, когда появляется новый достижимый путь: первый реальный запуск игры
(`GameCommandBuilder`, `RuntimeProvisioner`, полное скачивание `JavaManagerService`)
проходит код, который dry-run'ы не трогают, и может добавить ещё записей.
Библиотеки несут свои метаданные там, где это важно — OkHttp поставляет
`OkHttpFeature`, kotlinx.serialization использует статически сгенерированные
сериализаторы, — так что harvest в основном про внутренности корутин,
security-провайдеры, горстку ресурсов и FFM-downcall'ы libvault.

## Обвязка

`nexira.native-image` — convention-плагин в `buildSrc` (`hivens.nativeimage`),
той же формы, что `nexira.packaging`: типизированные `ExecOperations`-таски, без
стороннего Gradle-плагина, чистые по configuration-cache. Регистрирует под группой
`native-image`:

- `nativeImage` — собрать бинарь.
- `nativeImageAgentRun` — прогон под tracing-агентом для сбора метаданных.
- `nativeImageMetadataCopy` — перенести собранные метаданные в ресурсы.

Дефолты (переопределяются в блоке `nativeImage { }`): `--no-fallback`,
`-H:+UnlockExperimentalVMOptions`, `--enable-native-access=ALL-UNNAMED`,
`-Djava.awt.headless=true`. Без `--gc=G1` — он только для Oracle GraalVM и
сломается на Liberica NIK (на базе Community, serial GC).

## Почему не GUI

Compose Desktop рендерит через Skiko (Skia + AWT). Vanilla Compose-AWT не
собирается в рабочий нативный образ: Skiko грузит `libjawt`/Skia в рантайме по
путям, которых в нативном бинаре нет (`java.home` отсутствует), а оконный путь AWT
не готов к native-image. Это ограничение апстрима
([SKIKO-580](https://youtrack.jetbrains.com/issue/SKIKO-580/),
[skiko#925](https://github.com/JetBrains/skiko/issues/925)), а не пробел в
конфиге. Единственный известный обходняк заменяет оконный AWT на библиотеку JWM —
он уровня proof-of-concept и не тянет наш стек (skinema, FileKit, Coil, libtray).

Поэтому `:client-ui` остаётся на JVM. Плагин `nexira.native-image` не привязан к
CLI — если Skiko получит поддержку native-image (статическая линковка jawt) или
путь JWM дозреет, тот же плагин сможет нацелиться на GUI-модуль. До тех пор
нативная поверхность — headless-CLI, а launcher-core намеренно держат
Compose-free, чтобы это было возможно.

## Ограничения

- Только Linux x86-64; кросс-компиляции нет.
- Путь keyring требует `--enable-native-access=ALL-UNNAMED` (зашит в дефолты
  сборки) и работающей сессии Secret Service / D-Bus в рантайме.
- Реальный запуск игры дальше `--dry-run` пока не прогоняется в CI; перед опорой на
  это в проде прогони harvest против реального запуска.
