---
title: Архитектура
description: Структура модулей и ключевые архитектурные решения Aura Launcher.
---

## Структура модулей

```
aura-launcher/
├── client-config      # Константы, AppConfig, BuildConfig
├── client-core        # Доменные модели, API-сервисы, интерфейсы
├── client-launcher    # DI, загрузка файлов, обновления, запуск
└── client-ui          # Compose Multiplatform UI
```

### client-config

Глобальные константы: URL серверов, таймауты, пути к файлам, конфиг прокси.  
Генерирует `BuildConfig` с версией и временем сборки через `gmazzo/buildconfig`.

### client-core

Чистая бизнес-логика — без UI и DI-фреймворка.

- `AuthService` — вход, расшифровка AES-токена, определение SSL-ошибок
- `ServerRepository` — запрос дашборда, цикл обновления хэша лаунчера
- `SkinRepository` — загрузка скина/плаща
- `HttpClientProvider` — переключение между безопасным/небезопасным OkHttp клиентом в рантайме
- Доменные модели: `SessionData`, `FileManifest`, `SettingsData` и др.

### client-launcher

Связывает всё вместе через Koin DI.

- `FileDownloadService` — разворачивание манифеста, параллельная загрузка, MD5 верификация, распаковка extra.zip
- `LauncherService` + `GameCommandBuilder` — сборка JVM команды для 1.7.10 / 1.12.2 / 1.21.1
- `UpdateService` — GitHub Releases API, определение артефакта по ОС, SHA256 верификация
- `CredentialsManager` — AES-256-GCM шифрование с PBKDF2 деривацией ключа
- `ProfileManager` — профили экземпляров по серверам, избранное, последний сервер
- `JavaManagerService` — загрузка бандлов Bellsoft JDK по версии Minecraft

### client-ui

Compose Multiplatform UI.

- `AppLayout` / `AppRoot` — корневая композиция, управление окном, авто-вход
- `DashboardScreen` — сетка серверов, панель запуска
- `RightPanel` — панель входа/аккаунта, лента новостей
- `TrayManager` — обёртка dorkbox/SystemTray
- `LauncherController` — машина состояний пайплайна запуска (`Idle → Prepare → Downloading → GameRunning`)

---

## Ключевые архитектурные решения

### HttpClientProvider вместо прямой инжекции HttpClient

Репозитории получают провайдер, который определяет правильный клиент при каждом вызове. Это позволяет SSL-обходу вступить в силу немедленно без пересоздания синглтонов Koin.

### Фиксация версии JNA

dorkbox/SystemTray 4.4 выполняет жёстко заданную проверку версии JNA. JetBrains Runtime 25 бандлит JNA 7.x, что не проходит эту проверку. Решение: `resolutionStrategy.eachDependency` форсирует JNA на `6.1.6` глобально, с `5.18.1` явно в `client-ui` для Windows.

### Ручная сборка AppImage

Встроенная упаковка Compose Multiplatform для Linux производит DEB/RPM. AppImage собирается вручную в CI через `jlink` для минимального JRE и `appimagetool`, с инжектированием `.desktop` и AppStream metainfo.

### Конфиги версий GameCommandBuilder

Каждая поддерживаемая версия Minecraft имеет иммутабельный `VersionConfig` — главный класс, tweak-класс, JVM-флаги, директория нативных библиотек. NeoForge (1.21.1) требует разделения module path (`-p`) и дополнительных флагов `--add-opens` для Java 21+.

### Константы протокола (`AppConfig.LEGACY_*`)

Соль аутентификации, дефолтный хэш лаунчера, ID сервера по умолчанию и дескриптор протокольного JAR в `AppConfig` восстановлены из декомпилированного исходника официального лаунчера SMARTYcraft: [`Kitty-Hivens/smrt-deco`](https://github.com/Kitty-Hivens/smrt-deco). Оригинал обфусцирован Proguard-ом (`a.java`, `b.java`, …) и заархивирован с апреля 2026, так что любое будущее изменение протокола придётся выуживать из свежего дампа.
