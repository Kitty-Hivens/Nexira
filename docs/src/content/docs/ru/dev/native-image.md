---
title: Нативная сборка
description: Плагин сборки nexira.native-image, его задачи и значения по умолчанию, и почему Compose-GUI сегодня не собирается нативно.
---

`nexira.native-image` это convention-плагин в `buildSrc` (`hivens.nativeimage`),
который собирает модуль JVM-приложения в нативный бинарь через GraalVM /
Liberica NIK. Сегодня его не применяет ни один модуль. Он оставлен на тот день,
когда нативно будет собираться сам лаунчер, а что этому мешает, см.
[Почему не GUI](#почему-не-gui).

Форма та же, что у `nexira.packaging`: типизированные `ExecOperations`-задачи,
без стороннего Gradle-плагина, чистые по configuration-cache.

## Как применить

```kotlin
plugins {
    application
    id("nexira.native-image")
}

application {
    mainClass.set("example.MainKt")
}

nativeImage {
    imageName.set("nexira")
    buildArgs.add("--initialize-at-run-time=...")  // добавляется к значениям по умолчанию
}
```

Плагин собирает из задачи `jar` модуля и его конфигурации `runtimeClasspath`,
поэтому у модуля должны быть обе. Jar несёт скомпилированные классы и
закоммиченные метаданные достижимости, конфигурация несёт зависимости.

## Задачи

Все три лежат в группе `native-image`.

| Задача                    | Что делает                                                                        |
|---------------------------|-----------------------------------------------------------------------------------|
| `nativeImage`             | Собирает бинарь через `native-image`.                                             |
| `nativeImageAgentRun`     | Запускает приложение на JVM из GraalVM под tracing-агентом и собирает метаданные. |
| `nativeImageMetadataCopy` | Копирует собранные метаданные в закоммиченные ресурсы.                            |

`nativeImage` никогда не берётся из кэша сборки: бинарь большой и привязан к
машине, на которой собран. При этом он пропускается как актуальный, если не
менялись classpath, аргументы, главный класс и выход. `nativeImageAgentRun`
выполняется всегда, потому что запускает приложение.

## Значения по умолчанию

Всё задаётся в блоке `nativeImage { }`, и у каждого свойства есть значение по
умолчанию.

| Свойство               | По умолчанию                                                                                                         |
|------------------------|----------------------------------------------------------------------------------------------------------------------|
| `imageName`            | `app`                                                                                                                |
| `mainClass`            | `mainClass` плагина `application`, если он применён.                                                                 |
| `buildArgs`            | `--no-fallback`, `-H:+UnlockExperimentalVMOptions`, `--enable-native-access=ALL-UNNAMED`, `-Djava.awt.headless=true` |
| `agentArgs`            | Пусто. Аргументы, которые прогон агента передаёт в `main`.                                                           |
| `graalvmHome`          | Не задано, то есть автоопределение (см. ниже).                                                                       |
| `outputDir`            | `build/nativeImage`                                                                                                  |
| `agentOutputDir`       | `build/native/agent-output`                                                                                          |
| `committedMetadataDir` | `src/main/resources/META-INF/native-image/hivens/<imageName>`                                                        |

`--no-fallback` превращает дыру в метаданных в ошибку сборки, а не в образ,
которому втихую нужна JVM. `--enable-native-access=ALL-UNNAMED` нужен
Panama-привязкам вроде привязок keyring. `-Djava.awt.headless=true` подходит
приложению без окон, а для приложения с окном его придётся убрать. `--gc=G1`
нет: этот сборщик есть только в Oracle GraalVM и ломает сборку на Liberica NIK,
которая основана на Community и имеет последовательный сборщик.

## Требования

- Дистрибутив GraalVM с `native-image` на версии Java не ниже той, под которую
  компилируется сборка (сейчас 27): более старый не прочитает class-файлы.
  Liberica NIK это задокументированный выбор. Подойдёт любой GraalVM, потому что
  значения по умолчанию не используют флагов конкретного поставщика.
  - SDKMAN (Liberica NIK): `sdk install nik` ставит его в
    `~/.sdkman/candidates/nik/current`.
  - Или пакет дистрибутива либо tarball (Arch: `jdk25-graalvm-bin`).
- C-тулчейн для финальной линковки: `gcc` и заголовки `zlib`
  (`base-devel` на Arch, `build-essential zlib1g-dev` на Debian и Ubuntu).
- Около 4 ГБ свободной памяти и несколько минут. `native-image` работает
  отдельным процессом, помимо демонов Gradle и Kotlin.

Плагин ищет `native-image`, не завязываясь на поставщика, в таком порядке:
свойство `nativeImage { graalvmHome }`, `GRAALVM_HOME`, `NATIVE_IMAGE_HOME`, скан
`/usr/lib/jvm` (если установок несколько, выигрывает Liberica NIK), затем `PATH`.
Задай `GRAALVM_HOME`, если автоопределение выбрало не ту:

```sh
export GRAALVM_HOME="$HOME/.sdkman/candidates/nik/current"
```

## Сборка

```sh
GRAALVM_HOME=... ./gradlew :<module>:nativeImage
```

Бинарь появляется в `<module>/build/nativeImage/<imageName>`. Поиск рассчитан на
раскладку Unix (`bin/native-image`), а `native-image` не умеет
кросс-компиляцию, так что собирай на той системе, для которой бинарь.

### Целевой CPU (`-march`)

`native-image` фиксирует набор инструкций CPU при сборке, а JIT в JVM
подстраивается во время работы. Цель по умолчанию `x86-64-v3`, ей нужен AVX2
(процессоры примерно с 2013 года). Переопредели через `-PnativeMarch=<значение>`:

```sh
./gradlew :<module>:nativeImage -PnativeMarch=native      # быстрее всего, только этот CPU
./gradlew :<module>:nativeImage -PnativeMarch=x86-64-v2   # переносимый минимум для бинаря, который раздаёшь
```

Бинарь с `-march=native` падает с SIGILL на любом CPU старше машины сборки,
поэтому никогда его не раздавай: для раздачи бери базовый уровень (`x86-64-v2`
или `v3`). `native-image -march=list` перечисляет варианты.

## Метаданные достижимости

С `--no-fallback` всё, до чего код добирается через reflection, ресурсы,
`ServiceLoader` или FFM API, должно быть объявлено, иначе образ падает: на
сборке для большинства видов, на первом вызове для FFM-downcall, который никто
не зарегистрировал. Метаданные собирает tracing-агент GraalVM, а не пишет
человек, и они коммитятся в `committedMetadataDir`, откуда их подхватывает jar.

Агент сливает записанное в разных прогонах, поэтому прогони каждый путь, по
которому пойдёт бинарь, по прогону на набор аргументов:

```sh
GRAALVM_HOME=... ./gradlew :<module>:nativeImageAgentRun -PnativeAgentArgs="<аргументы>"
GRAALVM_HOME=... ./gradlew :<module>:nativeImageAgentRun -PnativeAgentArgs="<другие аргументы>"

./gradlew :<module>:nativeImageMetadataCopy   # build/native/agent-output -> закоммиченные ресурсы
git diff <module>/src/main/resources/META-INF/native-image   # просмотри перед коммитом
```

`-PnativeAgentArgs` делится по пробелам. Прогон использует `java` из самой
установки GraalVM, потому что библиотека агента поставляется с GraalVM, и
передаёт `--enable-native-access=ALL-UNNAMED` и `-Djava.awt.headless=true`.
Путь, которого агент не видел, образ не знает, поэтому собирай метаданные заново
всякий раз, когда у приложения появляется новый путь.

## Почему не GUI

Compose Desktop рисует через Skiko, а Skiko рисует через Skia в окно AWT. Skiko
находит `libjawt` и свою библиотеку Skia по путям, которые выводит из
`java.home`, а у нативного бинаря `java.home` нет. Это известное ограничение
апстрима ([SKIKO-580](https://youtrack.jetbrains.com/issue/SKIKO-580/),
[skiko#925](https://github.com/JetBrains/skiko/issues/925)), а не недостающая
настройка у нас, и именно оно сегодня держит `:client-ui` на JVM.

Работает ли в нативном образе сам оконный путь AWT, в этом проекте не
проверялось. Это не проверено, а не известно как сломанное. Известный обходной
путь заменяет окна AWT библиотекой JWM, а она уровня proof-of-concept и не
тянет стек лаунчера (skinema, FileKit, Coil, libtray).

Поэтому `:client-ui` остаётся на JVM. Если Skiko получит поддержку native-image,
например статическую линковку `jawt`, или путь через JWM дозреет, этот плагин
станет отправной точкой для нативной сборки лаунчера, уже без
`-Djava.awt.headless=true` в аргументах сборки.
