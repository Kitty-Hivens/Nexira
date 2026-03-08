package hivens.ui.i18n

object RussianStrings : AppStrings {

    // App
    override val appName = "Aura Launcher"
    override val appVersion get() = appName

    // Splash
    override val splashLoading = "Загрузка..."

    // Login
    override val loginTitle        = "Aura Client"
    override val loginUsername     = "Логин"
    override val loginPassword     = "Пароль"
    override val loginRemember     = "Запомнить пароль"
    override val loginButton       = "ВОЙТИ"
    override val loginSuccess      = "УСПЕШНО"
    override val loginLoading      = "ЗАГРУЗКА"
    override val loginErrorEmpty   = "Введите логин и пароль"
    override val loginErrorGeneric = "Ошибка входа"

    // Navigation
    override val navHome     = "Главная"
    override val navProfile  = "Профиль"
    override val navSettings = "Настройки"
    override val navConsole  = "Консоль"
    override val navLogout   = "Выйти"
    override val navBack     = "Назад"

    // Dashboard
    override fun dashboardWelcome(name: String) = "ДОБРО ПОЖАЛОВАТЬ, $name"
    override val dashboardNews         = "Новости"
    override val dashboardServers      = "ДОСТУПНЫЕ СЕРВЕРЫ"
    override val dashboardServersEmpty = "Серверы не найдены"

    // Launch Control
    override val launchReady       = "Готов к игре"
    override val launchButton      = "ИГРАТЬ"
    override val launchAbort       = "ОТМЕНА"
    override val launchRunning     = "Игра запущена"
    override val launchResetError  = "СБРОСИТЬ ОШИБКУ"
    override val launchDownloading = "Загрузка:"

    // Launcher States
    override val stateInit        = "Инициализация..."
    override val stateAuth        = "Авторизация..."
    override val stateAuthFail    = "Ошибка авторизации (оффлайн?)"
    override val stateNoPassword  = "Пароль не найден, используем текущую сессию."
    override val stateSync        = "Синхронизация файлов..."
    override val stateJvm         = "Подготовка JVM..."
    override val stateLaunching   = "Запуск процесса..."
    override fun stateExitCode(code: Int)  = "Игра закрылась с кодом $code"
    override fun stateError(msg: String)   = "Ошибка: $msg"
    override fun authSuccess(uuid: String) = "Успешный вход. UUID: $uuid"

    // Profile
    override val profileTitle              = "ПРОФИЛЬ"
    override val profileStatusLabel        = "Статус"
    override val profileStatusOnline       = "Авторизован"
    override val profileStatusOffline      = "Оффлайн"
    override val profileBalance            = "Баланс"
    override val profileTopUp              = "Пополнить баланс"
    override val profileUploadSkin         = "Загрузить скин"
    override val profileUploadSkinLoading  = "Загрузка..."
    override val profileSkinFront          = "Перёд"
    override val profileSkinBack           = "Зад"
    override val profileSkinLoading        = "Загрузка скина..."
    override val profileRefresh            = "Обновить"
    override val profileUploadSuccess      = "Скин успешно загружен"
    override fun profileUploadError(msg: String) = "Ошибка загрузки: $msg"

    // Settings
    override val settingsTitle              = "ГЛОБАЛЬНЫЕ НАСТРОЙКИ"
    override val settingsSectionUI          = "Интерфейс"
    override val settingsSectionBehavior    = "Поведение"
    override val settingsThemePicker        = "Выбор темы"
    override val settingsThemePickerSub     = "Кастомизируйте цветовую схему"
    override val settingsDarkTheme          = "Тёмная тема"
    override val settingsSeasonEffect       = "Сезонный эффект"
    override val settingsSeasonEffectSub    = "Анимация на заднем фоне"
    override val settingsCloseAfterLaunch   = "Закрывать лаунчер после запуска игры"
    override val settingsSaved              = "Настройки сохранены"
    override val settingsLanguage           = "Язык"

    // Theme Picker
    override val themePickerTitle           = "ВЫБОР ТЕМЫ"
    override val themePickerApply           = "ПРИМЕНИТЬ"
    override val themePickerPreview         = "ПРЕДПРОСМОТР"
    override val themePickerSelected        = "Выбрана"
    override val themePickerColorPrimary    = "Основной"
    override val themePickerColorSecondary  = "Дополнительный"
    override val themePickerColorBackground = "Фон"
    override val themePickerColorSurface    = "Поверхность"
    override val themePickerColorAccent     = "Акцент"
    override val themePickerColorSuccess    = "Успех"
    override val themePickerColorError      = "Ошибка"
    override val themePickerBtnSample       = "Пример кнопки"
    override val themePickerBtnOutlined     = "Кнопка с рамкой"

    // News
    override val newsTitle   = "НОВОСТИ ПРОЕКТА"
    override val newsLoading = "Загрузка новостей..."
    override val newsEmpty   = "Новостей пока нет..."
    override val newsNoImage = "НЕТ ФОТО"

    // Server Detail
    override val serverDetailTitle         = "ИНФОРМАЦИЯ О СЕРВЕРЕ"
    override val serverDetailLoading       = ""
    override val serverDetailNoImage       = "Нет изображения"
    override val serverDetailNoImageHint   = "banner.png"
    override val serverDetailMissingTitle  = "Информация отсутствует"
    override val serverDetailMissingBody   = "Создайте файл в папке:"
    override fun serverDetailMissingPath(path: String, file: String) = "Создайте файл $file в папке:"

    // Server Settings
    override val serverSettingsSubtitle        = "Настройки запуска"
    override val serverSettingsSectionSystem   = "СИСТЕМА"
    override val serverSettingsSectionMods     = "МОДИФИКАЦИИ"
    override val serverSettingsRam             = "ОЗУ"
    override fun serverSettingsRamValue(mb: Int) = "ОЗУ: $mb МБ"
    override val serverSettingsJava            = "Версия Java"
    override fun serverSettingsJavaAuto(version: String) = "Автоматически ($version)"
    override val serverSettingsJavaHint        = "Оставьте пустым для использования встроенной Java"
    override val serverSettingsOpenFolder      = "Открыть папку"
    override val serverSettingsReset           = "Сбросить клиент"
    override val serverSettingsNoMods          = "Нет опциональных модов"
    override val serverSettingsPickJava        = "Выберите Java"

    // Update
    override val updateTitle           = "Доступно обновление"
    override val updateTitleCritical   = "КРИТИЧЕСКОЕ ОБНОВЛЕНИЕ"
    override val updateCriticalBanner  = "Это обновление содержит критические исправления безопасности."
    override val updateChangelog       = "Что нового:"
    override val updateLater           = "Позже"
    override val updateDownload        = "Скачать и установить"
    override val updateDownloadNow     = "СКАЧАТЬ СЕЙЧАС"
    override val updateDownloading     = "Загрузка..."
    override val updateInstall         = "Установить и перезапустить"
    override val updateRetry           = "Повторить"
    override val updateErrorTitle      = "Ошибка загрузки"
    override val updateErrorUnknown    = "Неизвестная ошибка"
    override val updateScheduleFailed  = "Не удалось запланировать обновление"
    override fun updateVersion(version: String) = "Версия $version"
    override val updateDetails         = "Подробнее"

    // Console
    override val consoleTitle = "Консоль отладки"
    override fun consoleTitleCount(n: Int) = "Вывод игры ($n)"
    override val consoleCopyAll = "Копировать всё"
    override val consoleClear   = "Очистить"

    // Tray
    override val trayShowHide = "Показать / Скрыть"
    override val trayConsole  = "Открыть консоль"
    override val trayExit     = "Выход"

    // Settings: Diagnostics
    override val settingsSectionDiagnostics = "Диагностика"
    override val settingsOpenLogs           = "Открыть логи"
    override val settingsOpenCrashReports   = "Отчёты о сбоях"

    // File Manager
    override val fileCheckIntegrity = "Проверка целостности файлов..."
    override val fileNoUpdates      = "Файлы проверены, обновлений нет."
    override fun fileDownloading(n: Int) = "Загрузка обновлений ($n файлов)..."
    override val fileClientSetup    = "Настройка клиента..."
}
