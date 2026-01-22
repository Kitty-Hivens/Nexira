# Внедрение зависимостей (Koin)

> **Модуль:** `docs/ru/dependency-injection.md`
> **Контекст:** Управление графом объектов, связывание сервисов и разрешение зависимостей во время выполнения.

## 1. Обзор

Aura Launcher использует **Koin** — прагматичный фреймворк для внедрения зависимостей (DI) в Kotlin. В отличие от фреймворков, основанных на рефлексии (как Spring) или генерации кода во время компиляции (как Dagger/Hilt), Koin использует легковесный DSL для определения графа зависимостей во время выполнения.

### Почему Koin?

1. **Ноль бойлерплейта:** Никаких аннотаций (`@Inject`, `@Provides`) или кодогенерации (`kapt`) в модулях `core` или `launcher`.
2. **Multiplatform Ready:** Нативно работает с Compose Desktop и Kotlin JVM.
3. **Явный граф:** Зависимости объявляются централизованно в файлах модулей, что делает архитектуру прозрачной.

---

## 2. Граф зависимостей

DI граф строится иерархически, соблюдая слои Чистой Архитектуры проекта. Граф инициализируется один раз во время запуска приложения.

### 2.1 Инициализация

**Расположение:** `client-ui/src/desktopMain/kotlin/hivens/ui/Main.kt`

Функция `startKoin` является точкой входа. Она должна быть вызвана **до** отрисовки любого UI контента, чтобы гарантировать, что контроллеры смогут внедрить свои зависимости.

```kotlin
fun main() {
    startKoin {
        // Контекст логгирования (опционально, полезно для отладки)
        // androidLogger() // Не используется в Desktop версии

        // Загрузка модулей
        modules(networkModule, appModule, uiModule)
    }

    application {
        // Отрисовка UI...
    }
}
```

### 2.2 Организация модулей

Граф разделен на три логических модуля для поддержания разделения ответственности.

| Имя модуля          | Исходный файл                       | Scope (Область)    | Описание                                                                                                                                              |
|---------------------|-------------------------------------|--------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------|
| **`networkModule`** | `client-launcher/.../di/Modules.kt` | **Singleton**      | Предоставляет низкоуровневую инфраструктуру: `OkHttpClient` (Движок), `HttpClient` (Ktor) и конфиг JSON. Это "тяжелые" объекты, создаваемые один раз. |
| **`appModule`**     | `client-launcher/.../di/Modules.kt` | **Singleton**      | "Связующее звено сервисов". Связывает абстрактные интерфейсы из `client-core` с конкретными реализациями в `client-launcher`.                         |
| **`uiModule`**      | `client-ui/.../ui/Main.kt`          | **Factory/Single** | UI Контроллеры и ViewModels. Определены в UI слое, так как `client-launcher` не видит классы UI.                                                      |

---

## 3. Определения модулей

### 3.1 Сетевой модуль (`networkModule`)

Обрабатывает внешние подключения. Мы используем **Ktor Client**, работающий на движке **OkHttp**. `OkHttpClient` настраивается отдельно для поддержки SOCKS прокси и используется совместно во всем приложении.

```kotlin
val networkModule = module {
    // 1. JSON Конфигурация (Kotlinx Serialization)
    single<Json> {
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
            encodeDefaults = true
        }
    }

    // 2. Движок OkHttp (SOCKS Прокси и Таймауты)
    single<OkHttpClient> {
        // Настройка аутентификации Прокси...
        OkHttpClient.Builder()
            .connectTimeout(AppConfig.TIMEOUT_CONNECT, TimeUnit.MILLISECONDS)
            .readTimeout(AppConfig.TIMEOUT_READ, TimeUnit.MILLISECONDS)
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(AppConfig.Proxy.HOST, AppConfig.Proxy.PORT)))
            .build()
    }

    // 3. Ktor HttpClient
    single<HttpClient> {
        val okHttpInstance = get<OkHttpClient>()

        HttpClient(OkHttp) {
            engine { preconfigured = okHttpInstance }
            
            install(ContentNegotiation) {
                json(get()) // Внедряет экземпляр Json, определенный выше
            }
            
            install(HttpTimeout) {
                requestTimeoutMillis = 600_000
                connectTimeoutMillis = 30_000
            }
            
            defaultRequest {
                header("User-Agent", "SMARTYlauncher/${AppConfig.LAUNCHER_VERSION}")
                contentType(ContentType.Application.Json)
            }
        }
    }
    
    // Репозитории (используя singleOf для внедрения через конструктор)
    singleOf(::ServerRepository)
    singleOf(::SkinRepository)
}
```

### 3.2 Модуль приложения (`appModule`)

Именно здесь происходит связывание в рамках "Чистой Архитектуры". Мы мапим **Интерфейс** (Контракт) на **Реализацию**.

```kotlin
val appModule = module {
    // Сервисы
    single<ILauncherService> { LauncherService(get(), get()) }
    single<IAuthService> { AuthService(get()) }
    single<ISettingsService> { SettingsService() }
}
```

* **`single<Interface> { Impl() }`**: Это говорит Koin: *"Когда кто-то просит `Interface`, дай ему этот единственный экземпляр `Impl`"*.
* **`singleOf(::Class)`**: Современная фича DSL Koin, которая автоматически разрешает все параметры конструктора.

### 3.3 UI Модуль (`uiModule`)

Контроллеры часто хранят состояние. Хотя в этом десктопном приложении они обычно синглтоны, они могут быть `factory`, если нужен свежий экземпляр для каждого экрана.

```kotlin
val uiModule = module {
    // Внедрение через ссылку на конструктор
    singleOf(::LauncherController)
}
```

---

## 4. Паттерны внедрения

### 4.1 В Сервисах (Внедрение через конструктор)

Это стандартный паттерн для `client-launcher`. Зависимости передаются через конструктор.

```kotlin
// client-launcher/src/main/kotlin/hivens/launcher/LauncherService.kt
class LauncherService(
    private val settingsService: ISettingsService, // Внедрено
    private val fileIntegrity: IFileIntegrityService // Внедрено
) : ILauncherService { ... }
```

### 4.2 В Compose UI (`koinInject`)

Composable функции не имеют конструкторов. Мы используем функцию `koinInject()` для получения зависимостей из контекста.

```kotlin
// client-ui/src/desktopMain/kotlin/hivens/ui/screens/DashboardScreen.kt
@Composable
fun DashboardScreen() {
    // Ленивое внедрение
    val controller: LauncherController = koinInject()
    val settings: ISettingsService = koinInject()
    
    // Использование...
}
```

**⚠️ Предупреждение:** Избегайте вызова `koinInject()` внутри плотных циклов или фаз отрисовки (`Canvas`). Это выполняет поиск по карте (Map lookup), который быстр, но не бесплатен.

---

## 5. Инструкция: Добавление нового сервиса

Следуйте этому строгому протоколу, чтобы добавить новую фичу (например, `DiscordRPCSrevice`) не ломая архитектуру.

### Шаг 1: Определите Контракт (`client-core`)

Создайте интерфейс. Это позволяет UI общаться с сервисом, не зная его кода.

```kotlin
// client-core/src/main/kotlin/hivens/core/api/interfaces/IDiscordService.kt
interface IDiscordService {
    fun updatePresence(status: String)
}
```

### Шаг 2: Реализуйте Логику (`client-launcher`)

Напишите реализацию. Вы можете внедрять сюда другие сервисы при необходимости.

```kotlin
// client-launcher/src/main/kotlin/hivens/launcher/DiscordService.kt
class DiscordService(
    private val authService: IAuthService // Пример зависимости
) : IDiscordService {
    override fun updatePresence(status: String) { ... }
}
```

### Шаг 3: Зарегистрируйте в Koin (`client-launcher`)

Откройте `hivens.launcher.di.Modules.kt` и добавьте определение в `appModule`.

```kotlin
val appModule = module {
    // ... существующие определения
    single<IDiscordService> { DiscordService(get()) }
}
```

### Шаг 4: Внедрите в UI (`client-ui`)

Теперь вы можете использовать его в своем контроллере или экране.

```kotlin
class LauncherController(
    private val discordService: IDiscordService
) { ... }
```

---

## 6. Устранение неполадок

### `NoBeanDefFoundException`

**Ошибка:** `No definition found for class '...' Check your definitions!`
**Причина:** Вы забыли **Шаг 3**. Класс существует, но Koin о нем не знает.
**Решение:** Добавьте `single { ... }` или `singleOf(...)` в `Modules.kt`.

### `InstanceCreationException`

**Ошибка:** `Could not create instance for ...`
**Причина:** Одна из зависимостей, требуемых вашим сервисом, отсутствует или не смогла инициализироваться.
**Решение:** Проверьте стек-трейс, чтобы увидеть, *какой именно* аргумент не удалось разрешить. Рекурсивно проверьте его зависимости.

### Циклическая зависимость (Circular Dependency)

**Ошибка:** `StackOverflow` во время запуска.
**Причина:** Сервису A нужен Сервис B, а Сервису B нужен Сервис A.
**Решение:** Рефакторинг логики. Вынесите общую логику в третий Сервис C или используйте `by inject()` (Lazy) внутри одного из классов (не рекомендуется).
