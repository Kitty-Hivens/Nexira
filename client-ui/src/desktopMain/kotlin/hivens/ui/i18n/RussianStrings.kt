package hivens.ui.i18n

object RussianStrings : AppStrings {

    // App
    override val appName = "Aura Launcher"
    override val appVersion get() = appName

    // Login
    override val loginTitle        = "Aura Launcher"
    override val loginUsername     = "Логин"
    override val loginPassword     = "Пароль"
    override val loginRemember     = "Запомнить пароль"
    override val loginButton       = "ВОЙТИ"
    override val loginSuccess      = "УСПЕШНО"
    override val loginLoading      = "ЗАГРУЗКА"
    override val loginErrorEmpty   = "Введите логин и пароль"
    override val loginErrorGeneric = "Ошибка входа"
    override val loginRegister     = "Зарегистрироваться"

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
    override val updateTitleMandatory  = "ОБЯЗАТЕЛЬНОЕ ОБНОВЛЕНИЕ"
    override val updateCriticalBanner  = "Это обновление содержит критические исправления безопасности."
    override val updateMandatoryBanner =
        "Совместимость со старыми версиями нарушена на стороне сервера. Запуск без обновления невозможен."
    override fun updateMandatoryBannerWithReason(reason: String) =
        "Требуется по протоколу: $reason"
    override val updateChangelog       = "Полный список изменений"
    override val updateHighlights      = "Что нового"
    override val updateViewOnGitHub    = "Открыть на GitHub"
    override val updateLater           = "Позже"
    override val updateExit            = "Выйти"
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

    // --- Settings: Offline Mode ---
    override val settingsOfflineMode       = "Оффлайн-режим"
    override val settingsOfflineModeDesc   = "Запуск без авторизации. Файлы не будут синхронизированы."

    // --- Launcher States: Offline ---
    override val stateOfflineSkipAuth      = "Оффлайн-режим — авторизация пропущена"
    override val stateOfflineSkipSync      = "Оффлайн-режим — синхронизация пропущена, используем локальные файлы"
    override val stateOfflineNoClient      = "Файлы клиента не найдены. Сначала скачайте их онлайн."
    override val stateOfflineNoManifest    = "Нет кеша манифеста для этого сервера. Войдите онлайн хотя бы раз перед запуском оффлайн."

    // --- Server Settings: Extended ---
    override val serverSettingsJvmArgs     = "Аргументы JVM"
    override val serverSettingsJvmArgsHint = "-XX:+UseZGC -Dfoo=bar"
    override val serverSettingsResolution  = "Размер окна"
    override val serverSettingsWidth       = "Ширина"
    override val serverSettingsHeight      = "Высота"
    override val serverSettingsFullscreen  = "Полный экран"
    override val serverSettingsAutoConnect = "Автоподключение к серверу"

    // --- Server Settings: Icon Upload ---
    override val serverSettingsPickIcon    = "Выбрать иконку сервера"

    // =========================================================================
    // RAM Selector
    // =========================================================================
    override val ramCustomInputLabel = "Своё значение:"
    override fun ramSystemHint(systemRam: String, recommended: String) =
        "Система: $systemRam • Рекомендуется не более $recommended"

    // =========================================================================
    // Mod cards
    // =========================================================================
    override fun modConflictWarning(ids: String) = "Конфликтует с: $ids"
    override fun modIncompatibleHint(ids: String) = "Несовместим с: $ids"

    // =========================================================================
    // Server grid
    // =========================================================================
    override val serversFavorites = "★ ИЗБРАННЫЕ"

    // =========================================================================
    // Custom Background
    // =========================================================================
    override val backgroundTitle          = "ПОЛЬЗОВАТЕЛЬСКИЙ ФОН"
    override val backgroundSubtitle       = "Настройте обои лаунчера"
    override val backgroundEnable         = "Включить"
    override val backgroundSectionImage   = "ИЗОБРАЖЕНИЕ"
    override val backgroundPickFile       = "Выберите изображение для фона"
    override val backgroundPickButton     = "Выбрать файл"
    override val backgroundSectionScale   = "МАСШТАБИРОВАНИЕ"
    override val backgroundScaleCover     = "Заполнить"
    override val backgroundScaleContain   = "Вписать"
    override val backgroundScaleStretch   = "Растянуть"
    override val backgroundScaleOriginal  = "Оригинал"
    override val backgroundScaleTile      = "Плитка"
    override val backgroundSectionPosition = "ПОЗИЦИЯ"
    override val backgroundAlignX         = "Горизонтально"
    override val backgroundAlignY         = "Вертикально"
    override val backgroundSectionEffects = "ЭФФЕКТЫ"
    override val backgroundBlur           = "Размытие"
    override val backgroundDarken         = "Затемнение"
    override val backgroundOpacity        = "Прозрачность"
    override val backgroundSaturation     = "Насыщенность"
    override val backgroundParallax       = "Параллакс"
    override val backgroundVignette       = "Виньетка"
    override val backgroundSectionTint    = "ЦВЕТОВОЙ ОТТЕНОК"
    override val backgroundTintNone       = "Нет"
    override val backgroundTintNavy       = "Тёмно-синий"
    override val backgroundTintViolet     = "Фиолет"
    override val backgroundTintEmerald    = "Изумруд"
    override val backgroundTintBordeaux   = "Бордо"
    override val backgroundTintSteel      = "Сталь"
    override val backgroundTintIntensity  = "Интенсивность"
    override val backgroundReset          = "Сбросить к значениям по умолчанию"
    override val backgroundPreview        = "ПРЕДПРОСМОТР"
    override val backgroundPreviewServer  = "Пример сервера"
    override val settingsBackground       = "Пользовательский фон"
    override val settingsBackgroundSub    = "Фото или GIF на фон лаунчера"

    // =========================================================================
    // About Screen
    // =========================================================================
    override val aboutTitle                = "О ЛАУНЧЕРЕ"
    override fun aboutDescription(branding: String) = "Неофициальный лаунчер для $branding"
    override fun aboutBuildDate(date: String) = "Собрано: $date"
    override val aboutSectionCreator       = "СОЗДАТЕЛЬ"
    override val aboutSectionTechnologies  = "ТЕХНОЛОГИИ"
    override val aboutSectionLicense       = "ЛИЦЕНЗИЯ"
    override val aboutLicenseText          = "GPLv3 — Свободное программное обеспечение"
    override val aboutSectionUpdates       = "ОБНОВЛЕНИЯ"
    override val aboutCurrentVersion       = "Текущая версия"
    override val aboutCheckUpdates         = "Проверить обновления"
    override val aboutChecking             = "Проверяем..."
    override val aboutUpToDate             = "У вас последняя версия!"
    override val aboutCheckAgain           = "Проверить ещё раз"
    override fun aboutUpdateAvailable(version: String) = "Доступна версия $version"
    override val aboutCriticalUpdate       = "Критическое обновление"
    override val aboutSectionSystem        = "СИСТЕМА"
    override val aboutOs                   = "ОС"
    override val aboutJvmHeap             = "JVM Heap"
    override val aboutSectionLinks         = "ССЫЛКИ"
    override val aboutLinkGithub           = "GitHub"
    override val aboutLinkBugReport        = "Сообщить о баге"
    override val aboutLinkReleases         = "Релизы"
    override val settingsSectionAbout      = "О ПРОГРАММЕ"

    // Tech stack descriptions
    override val techKotlinDesc  = "Основной язык"
    override val techComposeDesc = "UI фреймворк"
    override val techKtorDesc    = "HTTP клиент"
    override val techKoinDesc    = "Dependency Injection"
    override val techSkiaDesc    = "Рендеринг скинов"
    override val techCoilDesc    = "Загрузка изображений"

    // --- Spawn Reset ---
    override val spawnResetButton  = "Вернуться на спавн"
    override val spawnResetLoading = "Сбрасываем..."
    override val spawnResetSuccess = "Готово! Перезайди"
    override val spawnResetError   = "Ошибка сервера"

    // --- Tray ---
    override val trayStatusIdle    = "● Ожидание"
    override val trayStatusRunning = "▶ Игра запущена"
    override val trayShow          = "Открыть лаунчер"
    override val trayServers       = "Серверы"
    override val trayNoServers     = "Серверы не загружены"

    // --- Settings: Start in tray ---
    override val settingsStartInTray     = "Запускать в трее"
    override val settingsStartInTrayDesc = "Лаунчер стартует свёрнутым; закрытие окна прячет его в трей"

    // --- Settings: Experimental features ---
    override val settingsSectionExperimental    = "Экспериментальные функции"
    override val settingsExperimentalMaster     = "Экспериментальные функции"
    override val settingsExperimentalMasterDesc = "Главный выключатель. Если выключить, оба переключателя ниже принудительно гасятся, независимо от их сохранённых значений."
    override val settingsMandatoryUpdates       = "Обязательные обновления"
    override val settingsMandatoryUpdatesDesc   = "Блокировать запуск до установки критических обновлений, когда ломается совместимость с протоколом. Сейчас включено по умолчанию."
    override val settingsPrereleaseChannel      = "Канал нестабильных обновлений"
    override val settingsPrereleaseChannelDesc  = "Получать RC и beta-сборки. Позволяет получать фиксы до выхода стабильного релиза. Сейчас временно включено по умолчанию."
    override val settingsAutoSyncAllPacks       = "Автосинхронизация всех сборок при запуске"
    override val settingsAutoSyncAllPacksDesc   = "Тихо обновлять все уже установленные сборки в фоне при старте лаунчера. Тратит фоновый трафик — полезно если играешь на нескольких серверах и хочешь свежее состояние без клика по каждому."
    override fun dashboardAutoSyncProgress(serverName: String, current: Int, total: Int) =
        "Синхронизация $serverName ($current/$total)"
    override fun dashboardAutoSyncBytes(readMB: Long, totalMB: Long) = "$readMB / $totalMB МБ"

    // April Fools
    override fun aprilCloseTitle(escapes: Int) = when {
        escapes == 0 -> "Подождите секунду..."
        escapes < 3  -> "Вы уверены?"
        escapes < 6  -> "Пожалуйста... нам было так хорошо"
        escapes < 8  -> "Это становится неловким для нас обоих"
        else         -> "Ладно. Сдаюсь."
    }

    override fun aprilCloseBody(escapes: Int) = when {
        escapes == 0 -> "Лаунчер так старался сегодня. Неужели вы его бросите?"
        escapes < 3  -> "Всё, что вам нужно — здесь. Кнопка просто... стесняется."
        escapes < 6  -> "Попыток поймать: $escapes. Кнопка не может бегать вечно."
        escapes < 8  -> "Вы очень настойчивы. Кнопка устаёт. Почти поймали..."
        else         -> "Вы выиграли. Вы невероятно упорный человек."
    }

    override val aprilCloseStay      = "Остаться"
    override val aprilCloseClose     = "Закрыть"
    override val aprilCloseSurrender = "Закрыть (наконец-то)"
    override val aprilCloseHideTray  = "Свернуть в трей"
    override fun aprilCloseEscapeCount(current: Int, max: Int) =
        "Кнопка сбежала $current / $max раз"

    // --- SSL Warning ---
    override val sslWarningTitle        = "Сертификат безопасности устарел"
    override val sslWarningBody         = "Сертификат сервера истёк. Соединение может быть небезопасным — данные передаются без проверки подлинности сервера. Продолжить на свой страх и риск?"
    override val sslWarningConnectAnyway = "Всё равно подключиться"
    override val sslWarningCancel       = "Отмена"
}
