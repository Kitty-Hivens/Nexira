package hivens.ui.i18n

import hivens.core.data.PackAuthRequirement

object RussianStrings : AppStrings {

    // App
    override val appName = "Nexira"

    // Login
    override val loginTitle        = "Nexira"
    override val loginUsername     = "Логин"
    override val loginPassword     = "Пароль"
    override val loginRemember     = "Запомнить пароль"
    override val loginButton       = "Войти"
    override val loginErrorEmpty   = "Введите логин и пароль"
    override val loginErrorGeneric = "Ошибка входа"
    override val loginRegister     = "Зарегистрироваться"
    override val loginPlayOffline  = "Играть офлайн"
    override val loginMicrosoft    = "Войти через Microsoft"
    override val msaTitle          = "Вход через Microsoft"
    override val msaInstruction    = "Откройте страницу и введите код:"
    override val msaCopyCode       = "Скопировать код"
    override val msaOpenBrowser    = "Открыть страницу"
    override val msaWaiting        = "Ожидание подтверждения..."

    // Navigation
    override val navLogout   = "Выйти"
    override val navBack     = "Назад"
    override val navForward  = "Вперёд"

    // Dashboard
    override fun dashboardWelcome(name: String) = "ДОБРО ПОЖАЛОВАТЬ, $name"
    override val dashboardServers              = "Доступные серверы"
    override val dashboardServersEmpty         = "Серверы не найдены"
    override val dashboardLoginRequiredTitle   = "Войдите, чтобы увидеть серверы"
    override val dashboardLoginRequiredHint    = "Список серверов SmartyCraft скрыт за авторизацией. Войти можно в разделе Профиль."

    // Launch Control
    override val launchReady       = "Готов к игре"
    override val launchButton      = "Играть"
    override val launchAbort       = "Отмена"
    override val launchRunning     = "Игра запущена"
    override val launchDownloading = "Загрузка:"
    override val launchPreparing   = "Подготовка"
    override val launchFailed      = "Ошибка запуска"

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
    override fun stateHelperUnavailable(mcVersion: String) =
        "Нет open-smrt хелпера для Minecraft $mcVersion. Запуск заблокирован, чтобы не запускать проприетарный мод Smarty; отключи подмену хелпера в настройках, чтобы играть с ним."
    override fun stateAuthlibUnavailable(mcVersion: String) =
        "Не удалось получить authlib SmartyCraft для Minecraft $mcVersion. Запуск заблокирован: сервер отклонит вход. Проверь соединение и вход в SmartyCraft и попробуй снова."
    override fun stateMissingAuthProvider(providerKey: String) = when (providerKey) {
        PackAuthRequirement.SmartyCraft.PROVIDER_KEY ->
            "Этой сборке нужен аккаунт SmartyCraft. Войдите, чтобы играть."
        else ->
            "Этой сборке нужен вход в '$providerKey'."
    }
    override fun authSuccess(uuid: String) = "Успешный вход. UUID: $uuid"

    // Profile
    override val profileTitle              = "Профиль"
    override val profileStatusLabel        = "Статус"
    override val profileStatusOnline       = "Авторизован"
    override val profileStatusOffline      = "Оффлайн"
    override val profileBalance            = "Баланс"
    override val profileTopUp              = "Пополнить баланс"
    override val profileUploadSkin         = "Загрузить скин"
    override val profileUploadSkinLoading  = "Загрузка..."
    override val profileSkinLoading        = "Загрузка скина..."
    override val profileRefresh            = "Обновить"
    override val profileUploadSuccess      = "Скин успешно загружен"
    override fun profileUploadError(msg: String) = "Ошибка загрузки: $msg"

    // Settings
    override val settingsTitle              = "Глобальные настройки"
    override val settingsSectionUI          = "Интерфейс"
    override val settingsSectionBehavior    = "Поведение"
    override val settingsThemePicker        = "Выбор темы"
    override val settingsThemePickerSub     = "Кастомизируйте цветовую схему"
    override val settingsDarkTheme          = "Тёмная тема"
    override val settingsDarkThemeDesc      = "Тёмное оформление интерфейса"
    override val settingsThemeModeTitle             = "Источник темы"
    override val settingsThemeModeManual            = "Вручную"
    override val settingsThemeModeSystem            = "Система"
    override val settingsThemeModeWallpaper         = "Обои"
    override val settingsThemeModeSystemUnavailable = "Системная схема недоступна в этой среде"
    override val settingsCloseAfterLaunch   = "Свернуть лаунчер в трей после запуска игры"
    override val settingsCloseAfterLaunchDesc = "Прячет лаунчер в системный трей, как только запускается игра."
    override val settingsSaved              = "Настройки сохранены"
    override val settingsLanguage           = "Язык"

    // Theme Picker
    override val themePickerTitle           = "Выбор темы"
    override val themePickerApply           = "Применить"
    override val themePickerPreview         = "Предпросмотр"
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
    override val newsTitle   = "Новости проекта"
    override val newsEmpty   = "Новостей пока нет..."
    override val newsFilterPlaceholder = "Фильтр новостей"
    override val newsFilterClear        = "Сбросить фильтр"
    override val railCollapse           = "Свернуть панель"
    override val railExpand             = "Развернуть панель"
    override val windowMinimize         = "Свернуть"
    override val windowMaximize         = "Развернуть"
    override val windowRestore          = "Восстановить"
    override val windowClose            = "Закрыть"
    override val crumbHome              = "Главная"
    override val crumbLoading           = "Загрузка…"
    override val paginationPrev         = "Предыдущая страница"
    override val paginationNext         = "Следующая страница"

    // Server Detail
    override val serverDetailTitle         = "Информация о сервере"
    override val serverDetailNoImage       = "Нет изображения"
    override val serverDetailNoImageHint   = "banner.png"
    override val serverDetailMissingTitle  = "Информация отсутствует"
    override fun serverDetailMissingPath(path: String, file: String) = "Создайте файл $file в папке:"

    // Server Settings
    override val serverSettingsSubtitle        = "Настройки запуска"
    override val serverSettingsSectionSystem   = "Система"
    override val serverSettingsSectionMods     = "Модификации"
    override val serverSettingsRam             = "ОЗУ"
    override fun serverSettingsRamValue(mb: Int) = "ОЗУ: $mb МБ"
    override val serverSettingsJava            = "Версия Java"
    override fun serverSettingsJavaAuto(version: String) = "Автоматически ($version)"
    override val serverSettingsJavaHint        = "Оставьте пустым для использования встроенной Java"
    override val serverSettingsOpenFolder      = "Открыть папку"
    override val serverSettingsReset           = "Сбросить клиент"

    override val serverSettingsResetConfirmTitle = "Сбросить клиент?"
    override val serverSettingsResetConfirmBody  = "Все скачанные файлы клиента этого сервера будут удалены без возможности восстановления."
    override val backgroundResetConfirmTitle     = "Сбросить фон?"
    override val backgroundResetConfirmBody      = "Вся конфигурация пользовательского фона вернётся к значениям по умолчанию."
    override val logoutConfirmTitle              = "Выйти из аккаунта?"
    override val logoutConfirmBody               = "Сохранённый вход будет удалён с этого устройства. Для повторного входа понадобится снова ввести данные."

    override val serverSettingsNoMods          = "Нет опциональных модов"
    override val serverSettingsPickJava        = "Выберите Java"

    // Update
    override val updateTitle           = "Доступно обновление"
    override val updateTitleCritical   = "Критическое обновление"
    override val updateTitleMandatory  = "Обязательное обновление"
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
    override val updateDownloadNow     = "Скачать сейчас"
    override val updateDownloading     = "Загрузка..."
    override val updateInstall         = "Установить и перезапустить"
    override val updateRetry           = "Повторить"
    override val updateErrorTitle      = "Ошибка загрузки"
    override val updateErrorUnknown    = "Неизвестная ошибка"
    override val updateScheduleFailed  = "Не удалось запланировать обновление"
    override fun updateVersion(version: String) = "Версия $version"
    override val updateDetails         = "Подробнее"

    // Desktop entry install (Advanced)
    override val updateManagerInstallDesktop = "Установить ярлык .desktop"
    override val updateManagerDesktopDone    = "Ярлык установлен"

    // Console
    override val consoleTitle = "Консоль отладки"
    override val consoleEmptyHint = "Пока тихо. Запусти сборку — и логи польются сюда."
    override fun consoleHeaderCount(filtered: Int, total: Int) = "Вывод игры ($filtered/$total)"
    override val consoleCopyAll = "Копировать всё"
    override val consoleClear   = "Очистить"
    override val consoleWrap    = "Перенос строк"
    override val consoleSaveToFile = "Сохранить в файл"
    override val consoleSearchPlaceholder = "Поиск…"
    override val consoleCopied = "Скопировано"
    override val consoleCommandPlaceholder = "команда для игры (Enter, ↑↓ история, Esc)"
    override val consoleMenuCopyLine = "Скопировать строку"
    override val consoleMenuCopySelection = "Скопировать выделенное"
    override val consoleSelectAll = "Выделить всё"
    override val consoleSettingsLabel = "Настройки консоли"
    override val consoleShowGutter = "Показывать полосу severity"
    override val consoleHideGutter = "Скрыть полосу severity"
    override val consoleShowTimestamps = "Показывать timestamps"
    override val consoleHideTimestamps = "Скрыть timestamps"
    override val consoleStatusFollow = "следую"
    override val consoleStatusPaused = "пауза"
    override fun consoleStatusLines(filtered: Int, total: Int) = "строк: $filtered/$total"
    override fun consoleStatusLinesWithHistory(filtered: Int, total: Int, history: Int) =
        "строк: $filtered/$total  +$history в истории"
    override fun consoleStatusFiltered(warn: Int, error: Int) = "WARN $warn  ERROR $error"
    override fun consoleStatusMatch(current: Int, total: Int) = "совп. $current/$total"

    // Tray
    override val trayConsole  = "Открыть консоль"
    override val trayExit     = "Выход"

    // Settings: Diagnostics
    override val settingsSectionDiagnostics      = "Диагностика"
    override val settingsOpenLogs                = "Открыть логи"
    override val settingsOpenCrashReports        = "Отчёты о сбоях"
    override val settingsCreateDiagnosticBundle  = "Собрать диагностический пакет"
    override val settingsDiagnosticBundleHint    = "Соберёт в один ZIP redact'нутые логи, отчёты о сбоях, историю действий и сведения о системе — отправлять в поддержку."
    override val settingsReportOnGithub          = "Сообщить на GitHub с пакетом"

    // File Manager
    override fun fileDownloading(n: Int) =
        "Загрузка обновлений ($n ${russianPlural(n, "файл", "файла", "файлов")})..."

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
    override val serverSettingsJvmBuildArgs = "Собрать"
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
    override fun ramAutoLabel(resolved: String) = "Авто · ~$resolved"

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
    override val backgroundTitle          = "Внешний вид"
    override val backgroundSubtitle       = "Обои, тема и палитра лаунчера"
    override val backgroundEnable         = "Включить"
    override val backgroundSectionImage   = "Изображение или видео"
    override val backgroundPickFile       = "Выберите изображение или видео для фона"
    override val backgroundPickButton     = "Выбрать файл"
    override val backgroundSectionScale   = "Масштабирование"
    override val backgroundScaleCover     = "Заполнить"
    override val backgroundScaleContain   = "Вписать"
    override val backgroundScaleStretch   = "Растянуть"
    override val backgroundScaleOriginal  = "Оригинал"
    override val backgroundScaleTile      = "Плитка"
    override val backgroundSectionPosition = "Позиция"
    override val backgroundAlignX         = "Горизонтально"
    override val backgroundAlignY         = "Вертикально"
    override val backgroundSectionEffects = "Эффекты"
    override val backgroundBlur           = "Размытие"
    override val backgroundDarken         = "Затемнение"
    override val backgroundOpacity        = "Прозрачность"
    override val backgroundSaturation     = "Насыщенность"
    override val backgroundParallax       = "Параллакс"
    override val backgroundVignette       = "Виньетка"
    override val backgroundAnimationSpeed = "Скорость анимации"
    override val backgroundSectionTint    = "Цветовой оттенок"
    override val backgroundTintNone       = "Нет"
    override val backgroundTintNavy       = "Тёмно-синий"
    override val backgroundTintViolet     = "Фиолет"
    override val backgroundTintEmerald    = "Изумруд"
    override val backgroundTintBordeaux   = "Бордо"
    override val backgroundTintSteel      = "Сталь"
    override val backgroundTintIntensity  = "Интенсивность"
    override val backgroundReset          = "Сбросить к значениям по умолчанию"
    override val backgroundPreview        = "Предпросмотр"
    override val backgroundPreviewServer  = "Пример сервера"
    override val settingsBackground       = "Пользовательский фон"
    override val settingsBackgroundSub    = "Фото или GIF на фон лаунчера"

    // =========================================================================
    // About Screen
    // =========================================================================
    override val aboutTitle                = "О лаунчере"
    override fun aboutDescription(branding: String) = "Неофициальный лаунчер для $branding"
    override val locale = java.util.Locale.of("ru", "RU")
    override fun aboutBuildDate(date: String) = "Собрано: $date"
    override val aboutRenderer = "Отрисовщик"
    override val aboutSectionCreator       = "Создатель"
    override val aboutSectionTechnologies  = "Технологии"
    override val aboutSectionLicense       = "Лицензия"
    override val aboutLicenseText          = "GPLv3 — Свободное программное обеспечение"
    override val aboutSectionUpdates       = "Обновления"
    override val aboutCurrentVersion       = "Текущая версия"
    override val aboutCheckUpdates         = "Проверить обновления"
    override val aboutChecking             = "Проверяем..."
    override fun aboutUpdateAvailable(version: String) = "Доступна версия $version"
    override val aboutCriticalUpdate       = "Критическое обновление"
    override val aboutSectionSystem        = "Система"
    override val aboutOs                   = "ОС"
    override val aboutSectionLinks         = "Ссылки"
    override val aboutLinkGithub           = "GitHub"
    override val aboutLinkBugReport        = "Сообщить о баге"
    override val aboutLinkReleases         = "Релизы"
    override val settingsSectionAbout      = "О ПРОГРАММЕ"

    // Tech stack descriptions
    override val techKotlinDesc  = "Основной язык"
    override val techComposeDesc = "UI фреймворк"
    override val techKtorDesc    = "HTTP клиент"
    override val techKoinDesc    = "Dependency Injection"
    override val techSkiaDesc    = "Графический рендер"
    override val techCoilDesc    = "Загрузка изображений"

    // --- Spawn Reset ---
    override val spawnResetButton  = "Вернуться на спавн"
    override val spawnResetLoading = "Сбрасываем..."
    override val spawnResetSuccess = "Готово! Перезайди"
    override val spawnResetError   = "Ошибка сервера"

    // --- Tray ---
    override val trayStatusIdle    = "Ожидание"
    override val trayStatusRunning = "Игра запущена"
    override val trayShow          = "Открыть лаунчер"
    override val trayHintTitle     = "Nexira всё ещё работает"
    override val trayHintBody      = "Окно свёрнуто в системный трей. Нажмите на значок в трее, чтобы вернуть его."
    override val trayHintShow      = "Показать окно"


    // --- Settings: Experimental features ---
    override val settingsSectionExperimental    = "Экспериментальные функции"
    override val settingsExperimentalMaster     = "Экспериментальные функции"
    override val settingsExperimentalMasterDesc = "Главный выключатель. Если выключить, оба переключателя ниже принудительно гасятся, независимо от их сохранённых значений."
    override val settingsSectionUpdates      = "Обновления"
    override val settingsPreReleases         = "Пре-релизы"
    override val settingsPreReleasesDesc     = "Получать бета-сборки до перевода в стабильные."
    override val settingsMandatoryUpdates       = "Обязательные обновления"
    override val settingsMandatoryUpdatesDesc   = "Блокировать запуск до установки критических обновлений, когда ломается совместимость с протоколом. Сейчас включено по умолчанию."
    override val settingsAutoSyncAllPacks       = "Автосинхронизация всех сборок при запуске"
    override val settingsAutoSyncAllPacksDesc   = "Тихо обновлять все уже установленные сборки в фоне при старте лаунчера. Тратит фоновый трафик — полезно если играешь на нескольких серверах и хочешь свежее состояние без клика по каждому."
    override val settingsAutoUpdatePacks        = "Автообновление установленных сборок"
    override val settingsAutoUpdatePacksDesc    = "Держать установленные сборки зеркала на последней версии. Безопасные обновления ставятся в фоне; смена версии Minecraft или загрузчика ждёт твоего подтверждения. Выключи, чтобы обновлять вручную."
    override val settingsJvmBuilder             = "Визуальный конструктор JVM-аргументов"
    override val settingsJvmBuilderDesc         = "Показывает кнопку «Собрать аргументы» в настройках сервера. Выбираешь сборщик мусора, настраиваешь регионы хипа, включаешь AppCDS или JFR — без необходимости помнить флаги. Готовые пресеты: Aikar's recipe, GTNH-класс, ZGC для больших хипов и другие."
    override val settingsAdaptiveMemory         = "Адаптивная память"
    override val settingsAdaptiveMemoryDesc     = "Уточняет размер хипа каждой сборки по реальному потреблению за несколько сессий, поверх автоматического базового значения от ОЗУ машины. Зафиксируй конкретное значение RAM, чтобы исключить сборку; выключи, чтобы оставить автоматическую базу без обучения."
    override val settingsMimicVersion           = "Подмена версии лаунчера"
    override val settingsMimicVersionDesc       = "Зафиксировать строку версии, которая отправляется в рукопожатии и User-Agent. Оставь пустым для стандартного значения — заполни только если апстрим успел поднять свою версию быстрее цикла релизов Nexira. Применяется на следующем запросе к протоколу после сохранения, перезапуск не требуется."
    override fun settingsMimicVersionPlaceholder(default: String) = "По умолчанию: $default"
    override fun dashboardAutoSyncProgress(serverName: String, current: Int, total: Int) =
        "Синхронизация $serverName ($current/$total)"
    override fun dashboardAutoSyncBytes(readMB: Long, totalMB: Long) = "$readMB / $totalMB МБ"
    override val widgetProgressTitle = "Фоновая активность"
    override val widgetProgressIdle = "Сейчас ничего не качается."
    override fun widgetTabDefaultLabel(index: Int) = "Вкладка $index"

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

    // --- 2FA (TOTP) — #159 ---
    override val auth2faTitle           = "Двухфакторная аутентификация"
    override val auth2faPrompt          = "Введите 6-значный код из приложения-аутентификатора, чтобы завершить вход."
    override val auth2faPlaceholder     = "000000"
    override val auth2faSubmit          = "Подтвердить"
    override val auth2faCancel          = "Отмена"
    override val auth2faInvalid         = "Неверный код. Попробуйте снова."
    override val auth2faExpired         = "Сессия 2FA истекла. Пожалуйста, войдите заново."

    override val auth2faUnsupportedTitle   = "К сожалению, 2FA не работает"
    override val auth2faUnsupportedBody    = "К сожалению, мы не можем поддерживать 2FA. Наши протоколы сильно отличаются от тех, что использует Smartycraft. У нас есть поддержка 2FA, но при попытке играть, у тебя просто полезут ошибки. Пожалуйста, отключи 2FA с аккаунта на сайте."
    override val auth2faUnsupportedDismiss = "Понятно"

    // --- SSL Warning ---
    override val sslWarningTitle        = "Сертификат безопасности устарел"
    override val sslWarningBody         = "Сертификат сервера истёк. Соединение может быть небезопасным — данные передаются без проверки подлинности сервера. Продолжить на свой страх и риск?"
    override val sslWarningConnectAnyway = "Всё равно подключиться"
    override val sslWarningCancel       = "Отмена"
    override val sslWarningTrustPrompt  = "Доверять серверу:"
    override val sslWarningTrustHour    = "1 час"
    override val sslWarningTrust30Days  = "30 дней"
    override val sslWarningTrustAlways  = "Постоянно"

    override val settingsSectionNetwork = "Сеть"
    override val sslBypassListTitle     = "Активные SSL-исключения"
    override val sslBypassNoEntries     = "Нет активных исключений"
    override val sslBypassRevoke        = "Отозвать"
    override fun sslBypassExpiresAt(formatted: String) = "Истекает: $formatted"

    override val settingsForceProxyTitle = "Только через прокси"
    override val settingsForceProxyDesc  = "Не пытаться подключаться напрямую — все запросы пойдут через SOCKS-прокси SmartyCraft. Включи если в твоей сети прямое соединение блокируется."

    override val settingsSectionSmarty           = "Серверы Smarty"
    override val settingsOpenSmrtHelperTitle      = "Использовать альтернативный хелпер для сети smrt"
    override val settingsStrictModCheckTitle      = "Точная проверка модификаций"
    override val settingsOpenSmrtHelperDesc       = "Подменяет родной мод Smarty нашим открытым хелпером на серверах Smarty. Те же сетевые функции, но без слежки. Если для версии игры замены нет, запуск блокируется, а не запускает родной мод."
    override val settingsStrictModCheckDesc       = "После синхронизации удаляет из папки mods всё, чего сервер не запрашивал. Держит сборку чистой, но заодно сносит и моды, которые ты добавил вручную."
    override val settingsNetworkAgentTitle        = "Использовать агента для поддержки работы сети"
    override val settingsNetworkAgentDesc         = "Направляет авторизацию игры на SmartyCraft прямо при запуске: вход на сервер и проверку скинов. Вход проходит через SmartyCraft, скины грузятся, и при этом не нужно подставлять пропатченную библиотеку авторизации от SmartyCraft. Нужно для входа на серверы SmartyCraft."
    override val settingsSmartyAuthLibTitle       = "Использовать библиотеку авторизации с SmartyCraft"
    override val settingsSmartyAuthLibDesc        = "Старый способ: берёт пропатченную библиотеку авторизации из клиента SmartyCraft и кладёт её в сборку вместо родной. Заменён агентом выше, оставлен как запасной вариант. Если файл не удаётся получить, запуск блокируется. По умолчанию выключено."

    override val settingsSectionDataDir       = "Каталог данных"
    override val settingsDataDirCurrent       = "Текущий путь:"
    override val settingsDataDirMove          = "Переместить..."
    override val settingsDataDirPickerTitle   = "Выбери новое место для данных Nexira"
    override val settingsDataDirConfirmTitle  = "Переместить каталог данных?"
    override fun settingsDataDirConfirmBody(source: String, target: String) =
        "Nexira перенесёт данные:\nиз: $source\nв:  $target\n\nПеремещение применится при перезапуске лаунчера."
    override val settingsDataDirRestartRequired = "Требуется перезапуск — Nexira применит перемещение при следующем старте"
    override val settingsDataDirQuitNow         = "Выйти сейчас"
    override val settingsDataDirErrorSamePath   = "Это и есть текущий каталог — выбери другую папку"
    override val settingsDataDirErrorNotEmpty   = "Целевая папка не пуста — выбери пустую папку или удали её содержимое"
    override fun settingsDataDirErrorPickerFailed(reason: String) =
        "Не удалось открыть выбор папки: $reason"

    // ── JVM Args Builder ────────────────────────────────────────────────
    override val jvmTitle    = "Конструктор JVM-аргументов"
    override val jvmSubtitle = "Выбери пресет или собери флаги вручную. Результат запишется в jvmArgs."
    override val jvmPresetsHeader = "Пресеты"
    override val jvmTabGc      = "GC"
    override val jvmTabTuning  = "G1 / Z / Shenandoah"
    override val jvmTabCds     = "AppCDS"
    override val jvmTabJit     = "JIT"
    override val jvmTabPerf    = "Производительность"
    override val jvmTabJfr     = "JFR"
    override val jvmTabCustom  = "Свои"
    override val jvmCancel     = "Отмена"
    override val jvmApply      = "Записать в jvmArgs"
    override fun jvmPreviewFlagsCount(n: Int) = "Превью ($n ${russianPlural(n, "флаг", "флага", "флагов")})"

    override val jvmGcHeader            = "Сборщик мусора"
    override val jvmGcG1Hint            = "Рекомендуемый для модового MC, хип 4-32 ГБ."
    override val jvmGcZHint             = "Паузы меньше миллисекунды. Java 17+, хип от 16 ГБ. Generational на Java 21+."
    override val jvmGcShenandoahHint    = "Concurrent low-pause из OpenJDK / Liberica. Java 17+."
    override val jvmGcParallelHint      = "Throughput-first. Длинные stop-the-world паузы. Почти никогда не правильный выбор."
    override val jvmGcSerialHint        = "Однопоточный. Только для крошечных хипов (< 1 ГБ)."

    override val jvmG1Header                  = "Тюнинг G1GC"
    override val jvmG1MaxPauseMillisHint      = "Цель максимальной паузы. Меньше = чаще сборки."
    override val jvmG1RegionSizeHint          = "Размер региона в МБ. Больше = меньше регионов и метаданных."
    override val jvmG1NewSizePercentHint      = "Минимум young generation как % от хипа. Aikar: 30."
    override val jvmG1MaxNewSizePercentHint   = "Максимум young generation как % от хипа. Aikar: 40."
    override val jvmG1IhopHint                = "Когда стартует mixed GC. Aikar: 15 (рано). Стандарт: 45."
    override val jvmG1ParallelRefProcHint     = "Параллельная обработка ссылок. Чистый плюс на много-ядре."
    override val jvmG1PerfDisableSharedMemHint = "Не писать /tmp/hsperfdata. Ломает VisualVM, но чище для диска."

    override val jvmZHeader            = "Тюнинг ZGC"
    override val jvmZGenerationalHint  = "Только Java 21+. Делит хип на young / old. Сильно лучше чем не-generational."

    override val jvmShenandoahHeader        = "Эвристика Shenandoah"
    override val jvmShenandoahAdaptiveHint  = "По умолчанию. Балансирует паузу и throughput."
    override val jvmShenandoahStaticHint    = "Триггерит сборку по фиксированным порогам."
    override val jvmShenandoahCompactHint   = "Агрессивная компакция. Лучше освобождает память."
    override val jvmShenandoahAggressiveHint = "Непрерывная сборка. Высокая цена throughput."

    override fun jvmTuningNotApplicable(gcName: String) =
        "Для $gcName тюнинг не применим. Переключись на G1, Z или Shenandoah на вкладке GC."

    override val jvmCdsHeader            = "Application Class Data Sharing"
    override val jvmCdsIntro             = "Кэшировать метаданные классов между запусками. Для сборок 200+ модов экономит 1-3 секунды на каждом холодном старте после первого."
    override val jvmCdsModeDisabledLabel = "Отключено"
    override val jvmCdsModeDisabledHint  = "CDS выключен. По умолчанию."
    override val jvmCdsModeAutoLabel     = "Авто-архив (Java 19+)"
    override val jvmCdsModeAutoHint      = "JVM сама управляет архивом при выходе. Путь не нужен."
    override val jvmCdsModeArchiveLabel  = "Архивировать при выходе"
    override val jvmCdsModeArchiveHint   = "Записывать архив по указанному пути при завершении."
    override val jvmCdsModeUseLabel      = "Использовать готовый архив"
    override val jvmCdsModeUseHint       = "Читать готовый архив по указанному пути."
    override val jvmCdsArchivePathLabel  = "Путь к архиву"

    override val jvmJitHeader        = "JIT-компилятор"
    override val jvmJitTieredHint    = "On = прогрев интерпретатор → C1 → C2 (по умолчанию). Off = только C2, медленнее старт."
    override val jvmJitCodeCacheHint = "Размер кэша JIT-кода. По умолчанию JVM 240. Модовому MC может пригодиться 512+."

    override val jvmPerfHeader                  = "Производительность и OS-флаги"
    override val jvmPerfAlwaysPreTouchHint      = "Прикоснуться ко всем страницам хипа при старте. Старт медленнее, runtime стабильнее."
    override val jvmPerfDisableExplicitGcHint   = "Сделать System.gc() no-op. Некоторые legacy моды злоупотребляют. Почти всегда плюс."
    override val jvmPerfUseLargePagesHint       = "Требует hugepages, заранее аллоцированные через sysctl. ~2-5% при правильной настройке."
    override val jvmPerfTransparentHugePagesHint = "Проще чем UseLargePages. Добавляет latency-всплески при дефрагментации. Trade-off."
    override val jvmPerfNumaHint                = "NUMA-aware аллокация. Полезно только на multi-socket системах."
    override val jvmPerfHeapDumpHint            = "Сделать heap-dump при OOM. Критично для диагностики."
    override val jvmPerfExitOnOomHint           = "Выход на OOM вместо попыток продолжить. Предотвращает zombie-стейт игры."

    override val jvmJfrHeader               = "Java Flight Recorder"
    override val jvmJfrIntro                = "Записывает внутренности JVM (аллокации, GC, потоки, локи). Открой получившийся .jfr в JDK Mission Control или IntelliJ для анализа."
    override val jvmJfrEnableLabel          = "Включить JFR-запись"
    override val jvmJfrEnableHint           = "Default settings = ~1% overhead. Profile settings = ~5%, ловит method-level."
    override val jvmJfrDurationLabel        = "Длительность (минуты)"
    override val jvmJfrSettingsHeader       = "Пресет настроек"
    override val jvmJfrSettingsDefaultHint  = "Низкий overhead, подходит для обычной игры."
    override val jvmJfrSettingsProfileHint  = "Method-level профилирование. ~5% overhead."
    override val jvmJfrOutputPathLabel      = "Путь к выходному .jfr (опционально)"

    override val jvmCustomHeader = "Свои флаги"
    override val jvmCustomIntro  = "Дополнительные флаги добавляются как есть. Для одноразовых экспериментов или vendor-флагов которые мы ещё не вывели в UI. Через пробел."
    override val jvmCustomLabel  = "Дополнительные аргументы"

    // --- Data dir migration UI ---
    override val migrationWelcome      = "Добро пожаловать в Nexira"
    override val migrationDescription  = "Nexira теперь называется Nexira. Перед запуском лаунчера нужно перенести существующие данные в новое расположение. Старая папка остаётся нетронутой как резервная копия; удалите её вручную когда убедитесь что всё работает."
    override val migrationFromHeader   = "Откуда"
    override val migrationToHeader     = "Куда"
    override fun migrationSize(megabytes: Int, files: Int) =
        "$megabytes МБ, $files ${russianPlural(files, "файл", "файла", "файлов")}"
    override val migrationStart        = "Перенести данные"
    override val migrationInProgress   = "Перенос в Nexira"
    override fun migrationCurrentFile(file: String) = "Копируется $file"
    override fun migrationProgressBytes(doneMb: Int, totalMb: Int) = "$doneMb МБ из $totalMb МБ"
    override val migrationCompletedTitle = "Перенос завершён"
    override val migrationCompletedBody  = "Перезапустите Nexira чтобы начать пользоваться перенесёнными данными."
    override val migrationFailedTitle    = "Перенос не удался"
    override fun migrationFailedBody(error: String) = "Некоторые файлы не удалось скопировать: $error"
    override val migrationRetry = "Повторить"
    override val migrationQuit  = "Закрыть Nexira"

    override val placeholderNotImplemented = "Пока не реализовано..."
    override val placeholderHint           = "Этот экран зарезервирован под работу Atelier."

    override val navLibrary = "Библиотека"
    override val navBrowse  = "Каталог"

    override val settingsHomeViewTitle   = "Главный экран"
    override val settingsHomeViewSub     = "Современный экран включён по умолчанию. Классический Dashboard и Library-first доступны в любой момент."
    override val settingsHomeViewClassic = "Классический"
    override val settingsHomeViewLibrary = "Library (alpha)"
    override val settingsHomeViewNew     = "Современный"

    override val settingsUiStyleTitle    = "Стиль интерфейса"
    override val settingsUiStyleSub      = "Переключай форму / поверхности / анимации независимо от цветовой палитры. Celestia — текущий вид с мягкими скруглениями и стеклом; Brut — жёсткий, без скруглений, без анимаций."
    override val settingsUiStyleCelestia = "Celestia"
    override val settingsUiStyleBrut     = "Brut"

    // --- Выделение в левой панели ---
    override val navSelectionTitle        = "Выделение пункта меню"
    override val navSelectionSub          = "Как подсвечивается активный пункт в левой панели"
    override val navStylePill             = "Капсула"
    override val navStyleSquare           = "Квадрат"
    override val navStyleCircle           = "Круг"
    override val navStyleBar              = "Полоса"
    override val navStyleDot              = "Точка"
    override val navStyleNone             = "Нет"
    override val navSelectionOutlineIcons = "Контурные иконки у невыбранных"
    override val navSelectionAccent       = "Цвет выделения"
    override val navHoverHighlight        = "Подсветка при наведении"

    override val settingsCategoryAppearance   = "Внешний вид"
    override val settingsCategoryNetwork      = "Сеть"
    override val settingsCategorySmarty       = "Smarty"
    override val settingsCategoryExperimental = "Эксперименты"
    override val settingsCategoryAdvanced     = "Расширенные"
    override val settingsCategoryDiagnostics  = "Диагностика"
    override val settingsCategoryConsole      = "Консоль"
    override val consoleSecDisplay            = "Отображение"
    override val consoleSecColors             = "Цвета severity"
    override val consoleSecFontSize           = "Размер шрифта"
    override val consoleSecWrap               = "Перенос строк"
    override val consoleSecGutter             = "Полоса severity"
    override val consoleSecTimestamps         = "Временные метки"
    override val consoleSecBuffer             = "Буфер строк"
    override val consoleSecColorInfo          = "Info"
    override val consoleSecColorWarn          = "Warn"
    override val consoleSecColorError         = "Error"
    override val consoleSecColorAuto          = "Авто"
    override val consoleSecApplyNote          = "Изменения применяются при следующем открытии консоли."
    override val consoleSecHighlightRules     = "Правила подсветки"
    override val consoleSecFilterRules        = "Фильтр / мьют"
    override val consoleSecAddRule            = "Добавить правило"
    override val consoleSecRulePattern        = "Паттерн"
    override val consoleSecRegex              = "regex"
    override val consoleSecBold               = "Жирный"
    override val consoleSecRulesEmpty         = "Правил пока нет."
    override val consoleSecArt                 = "Арты пустой консоли"
    override val consoleSecArtAdd              = "Добавить арт"
    override val consoleSecArtPaste            = "Вставь ASCII или брайль-арт"
    override val consoleSecArtEmpty            = "Своих артов пока нет."

    override val profileCategoryAccount = "Аккаунт"
    override val profileCategorySignIn      = "Вход"
    override val profileCategorySecurity    = "Безопасность"
    override val profileForgetSavedSignIn   = "Забыть сохранённый вход"
    override val profileSecurityHint        = "Твой вход сохранён на этом устройстве для авто-входа."
    override val accountsTitle               = "Аккаунты"
    override val accountRemove               = "Удалить"
    override val accountFaceLabel            = "Показывать как"
    override val accountFaceAuto             = "Авто"
    override val profileSignOutSmartycraft   = "Выйти из SmartyCraft"
    override val profileSignOutMicrosoft     = "Выйти из Microsoft"
    override val wardrobeTitle               = "Гардероб"
    override val wardrobeSignedOut           = "Войдите, чтобы управлять скинами и плащами."
    override val wardrobeUpload               = "Загрузить"
    override val wardrobeApplySmartycraft     = "Применить (SmartyCraft)"
    override val wardrobeEmpty                = "Библиотека пуста. Загрузите PNG-скин, чтобы начать."
    override val wardrobeSaved               = "Сохранённые"
    override val wardrobeCapes               = "Плащи"
    override val wardrobeApplyCape           = "Задать плащ клана"
    override val wardrobeCapeClanHint        = "Плащ клановый — задаёт только глава клана."
    override val wardrobeDefaults            = "Стандартные скины"
    override val wardrobePoseStand           = "Стоя"
    override val wardrobePoseWave            = "Приветствие"
    override val wardrobePoseSit             = "Сидя"
    override val wardrobePoseFaceCover       = "Прикрыть лицо"
    override val wardrobePoseWalk            = "Ходьба"

    override val backgroundLoopMode      = "Луп"
    override val backgroundLoopUseCodec  = "Из кодека"
    override val backgroundLoopForever   = "Бесконечно"
    override val backgroundLoopOnce      = "Один раз"

    override val customizationAccentClear     = "Сбросить акцент"
    override val customizationSectionVisual   = "Визуал"
    override val customizationSectionColors   = "Переопределение цветов"
    override val customizationHexInvalid      = "Неверный hex"
    override val themePickerAccentOverride    = "Свой акцент (применяется сразу)"

    override val browseTitle             = "Каталог"
    override val browseSearchPlaceholder = "Поиск сборок"
    override val browseImport            = "Импорт файла"
    override val libraryAddAction        = "Добавить сборку"
    override val libraryNewLocalPack     = "Новая локальная сборка"
    override val libraryImportPack       = "Импортировать сборку"
    override val createPackName          = "Название"
    override val createPackMc            = "Версия Minecraft"
    override val createPackLoader        = "Загрузчик"
    override val createPackLoaderVersion = "Версия загрузчика (необязательно)"
    override val createPackConfirm       = "Создать"
    override val createPackCancel        = "Отмена"
    override val createPackShowSnapshots = "Показать снапшоты"
    override val createPackHideSnapshots = "Скрыть снапшоты"
    override val browseEmptyTitle        = "Каталог пуст"
    override val browseEmptyMessage      = "Зеркало доступно, но пока не публикует сборки. Загляни позже."
    override val browseErrorTitle        = "Зеркало недоступно"
    override val browseErrorMessage      = "Не удалось дотянуться до зеркала. Проверь соединение и повтори."
    override val browseRetry             = "Повторить"
    override fun modrinthCategory(id: String) = when (id) {
        "adventure"    -> "Приключения"
        "challenging"  -> "Сложные"
        "combat"       -> "Сражения"
        "kitchen-sink" -> "Всё включено"
        "lightweight"  -> "Минималистичное"
        "magic"        -> "Магия"
        "multiplayer"  -> "Мультиплеер"
        "optimization" -> "Оптимизация"
        "quests"       -> "Квесты"
        "technology"   -> "Технологии"
        else           -> humanizeCategory(id)
    }

    override val browseDetailErrorTitle    = "Не удалось загрузить сборку"
    override val browseDetailErrorMessage  = "Не удалось получить manifest. Проверь соединение и повтори."
    override val browseDetailInstallReady  = "Готово к установке"
    override val browseDetailInstallHint   = "Создаст новый instance в твоей data-папке."
    override val browseDetailInstallButton = "Установить"
    override val browseDetailTagsTitle     = "Теги"
    override val browseDetailAboutTitle       = "О сборке"
    override fun browseDetailAbout(mods: Int, assets: Int) =
        "Сборка включает $mods ${russianPlural(mods, "мод", "мода", "модов")} и $assets ${russianPlural(assets, "ассет", "ассета", "ассетов")}."
    override val browseDetailAboutNote        = "Развёрнутое описание появится здесь когда зеркало начнёт заполнять его в manifest."
    override val browseDetailCompatTitle      = "Совместимость"
    override val browseDetailCompatMc         = "Minecraft"
    override val browseDetailCompatLoader     = "Лоадер"
    override val browseDetailCompatJava       = "Runtime"
    override val browseDetailVersionTitle     = "Версия"

    override val browseDetailInstallRunningTitle  = "Установка..."
    override fun browseDetailInstallProgress(filename: String, current: Int, total: Int) =
        "$filename  ($current / $total)"
    override val browseDetailInstallStarting      = "Запуск..."
    override val browseDetailInstallDoneTitle     = "Установлено"
    override val browseDetailInstallDoneHint      = "Добавлено в Library."
    override val browseDetailInstallOpenLibrary   = "Открыть в Library"
    override val browseDetailInstallFailedTitle   = "Ошибка установки"
    override val browseDetailInstallFailedGeneric = "Установка не удалась по неизвестной причине."

    override val fileBrowserNoRoot          = "У этого экземпляра ещё нет файлов на диске."
    override val fileBrowserPickAFile       = "Выберите файл слева для предпросмотра."
    override val fileBrowserBinaryHint      = "Бинарный файл — предпросмотр недоступен."
    override val fileBrowserOpenExternally  = "Открыть во внешней программе"
    override fun fileBrowserTextTruncated(maxKb: Long) =
        "Предпросмотр обрезан до первых $maxKb KB. Открой во внешней программе чтобы увидеть весь файл."
    override val fileBrowserEmptyFolder      = "(пусто)"

    override val contentTabUnsupportedOrigin    = "Просмотр содержимого пока работает только для сборок с зеркала. Для остальных источников поддержку добавим в следующих PR."
    override val contentAddFiles                = "Добавить файлы"
    override val contentFindProjects            = "Найти проекты"
    override val contentSearchPlaceholder       = "Поиск в содержимом..."
    override val contentEmpty                   = "Ничего не найдено"
    override val contentFilterAll               = "Всё"
    override val contentFilterMods              = "Моды"
    override val contentFilterResourcePacks     = "Ресурсы"
    override val contentFilterShaderPacks       = "Шейдеры"
    override val contentDeleteTitle             = "Удалить файл?"
    override val contentDeleteBody              = "Файл будет удалён с диска навсегда."
    override val contentActionDetails           = "Детали"
    override val contentActionOpenPage          = "Открыть страницу"
    override val contentDetailAuthors           = "Авторы"
    override val contentDetailSize              = "Размер"
    override val contentTabFetchErrorTitle      = "Не удалось загрузить содержимое сборки"
    override val contentTabFetchErrorGeneric    = "Манифест с зеркала не загрузился."
    override val contentTabRetry                = "Повторить"
    override val contentTabRoleSection          = "Слоты по ролям"
    override fun contentTabOptionalSection(count: Int) = "Опциональные моды ($count)"
    override fun contentTabIncompatibleWith(name: String) = "Несовместим с $name"
    override fun contentTabModsSection(count: Int) = "Моды ($count)"
    override fun contentTabAssetsSection(count: Int) = "Ассеты ($count)"
    override val contentTabResolverIssuesTitle  = "Найдены проблемы в манифесте"
    override fun contentTabResolverMissing(count: Int) = russianPlural(
        count,
        "$count зависимость ссылается на мод, которого нет в сборке.",
        "$count зависимости ссылаются на моды, которых нет в сборке.",
        "$count зависимостей ссылаются на моды, которых нет в сборке.",
    )
    override fun contentTabResolverCycles(count: Int) = russianPlural(
        count,
        "Найден $count цикл в зависимостях — автору сборки стоит перепроверить requires.",
        "Найдено $count цикла в зависимостях — автору сборки стоит перепроверить requires.",
        "Найдено $count циклов в зависимостях — автору сборки стоит перепроверить requires.",
    )
    override val contentTabRoleRecipeViewer     = "Просмотр рецептов"
    override val contentTabRoleMinimap          = "Миникарта"
    override val contentTabRoleBlockInfo        = "Инфо о блоке"
    override val contentTabRolePerformance      = "Производительность"
    override val contentTabRoleInventorySearch  = "Поиск в инвентаре"
    override fun contentTabRoleAltCount(count: Int) =
        if (count == 0) "один вариант"
        else "$count ${russianPlural(count, "альтернатива", "альтернативы", "альтернатив")}"
    override val contentTabRoleAlternativesHeader = "Альтернативы в этой сборке"
    override val contentTabModNoDescription     = "Описания пока нет в манифесте."
    override fun contentTabModLicensePrefix(license: String) = "Лицензия: $license"
    override val contentTabModUrlLabel          = "Страница мода"
    override fun contentTabModSizeLabel(kb: Long) = "$kb KB"
    override fun contentTabModDependencies(count: Int) = "Зависимости ($count)"
    override fun contentTabModMissingCount(count: Int) = "$count нет"
    override val contentTabDepOptional          = "опционально"
    override val contentTabDepMissing           = "нет"
    override val contentTabModOptional          = "опциональный"
    override fun contentTabLibrariesSection(count: Int)     = "Библиотеки ($count)"
    override fun contentTabResourcePacksSection(count: Int) = "Ресурспаки ($count)"
    override fun contentTabShaderPacksSection(count: Int)   = "Шейдеры ($count)"
    override fun contentTabConfigsSection(count: Int)       = "Конфиги ($count)"
    override fun contentTabOtherAssetsSection(count: Int)   = "Прочие файлы ($count)"
    override fun contentTabAssetSizeLabel(kb: Long) = "$kb KB"
    override val contentTabAssetOptional        = "опционально"
    override val contentTabAssetNoDescription   = "Описания пока нет в манифесте."

    override fun worldsTabLocalSection(count: Int) = "Локальные миры ($count)"
    override val worldsTabLocalEmpty            = "Сохранённых миров пока нет. Начни новый одиночный мир внутри игры и он появится здесь."
    override fun worldsTabServersSection(count: Int) = "Серверы из истории ($count)"
    override val worldsTabServersEmpty          = "В мультиплеер-истории этого экземпляра пока нет серверов."
    override val worldsTabErrorTitle            = "Не удалось прочитать миры"
    override val worldsTabErrorMessage          = "Не удалось прочитать сохранения или список серверов этого экземпляра. Возможно, файлы повреждены или недоступны."
    override fun worldsTabLastPlayed(rel: String) = "Был в игре: $rel"
    override val worldsTabServerHiddenLabel     = "скрыт из ванильного списка"
    override val worldsTabGameSurvival          = "Выживание"
    override val worldsTabGameCreative          = "Креатив"
    override val worldsTabGameAdventure         = "Приключение"
    override val worldsTabGameSpectator         = "Наблюдение"
    override val worldsTabGameUnknown           = "Неизвестный режим"
    override val worldsTabDimOverworld          = "Верхний мир"
    override val worldsTabDimNether             = "Нижний мир"
    override val worldsTabDimEnd                = "Край"
    override val worldsTabDimOther              = "Прочее"

    override val packDetailTabContent           = "Содержимое"
    override val packDetailTabFiles             = "Файлы"
    override val packDetailTabWorlds            = "Миры"
    override val packDetailTabLogs              = "Логи"
    override val packDetailTabSettings          = "Настройки"
    override val packVersionSection             = "Версия и обновления"
    override val packVersionInstalled           = "Установленная сборка"
    override val packVersionCheck               = "Проверить"
    override val packVersionUpToDate            = "Стоит последняя сборка"
    override fun packVersionAvailable(version: String) = "Доступна сборка $version"
    override val packVersionSafe                = "Безопасное обновление"
    override val packVersionNeedsCare           = "Меняет Minecraft или загрузчик — сначала снимается снапшот"
    override val packVersionUpdateNow           = "Обновить"
    override val packVersionFollowLatest        = "Следовать за последней"
    override val packVersionFollowLatestDesc    = "Автообновлять этот пак до новейшей сборки."
    override fun packVersionLatestBuilt(version: String, publishedAt: String) = "Последний билд: $version, опубликован $publishedAt"
    override val packVersionSwitch              = "Переключить"
    override val packVersionCurrentTag          = "Текущая"
    override val packVersionUpdateBadge         = "Обновление"
    override val packVersionCheckFailed         = "Не удалось проверить обновления"

    override val packVersionsTitle              = "Версии сборки"
    override val packVersionsAllVersions        = "Все версии"
    override val packVersionsLatestTag          = "Последняя"
    override fun packVersionsRebuilds(n: Int)   = "+$n ${russianPlural(n, "пересборка", "пересборки", "пересборок")} без изменений"
    override val packVersionsChannelRelease     = "Релиз"
    override val packVersionsChannelBeta        = "Бета"
    override val packVersionsChannelAlpha       = "Альфа"
    override fun packVersionsCounts(mods: Int, assets: Int) =
        "$mods ${russianPlural(mods, "мод", "мода", "модов")}, $assets ${russianPlural(assets, "ассет", "ассета", "ассетов")}"
    override val packVersionsDiffVsPrevious     = "К предыдущему билду"
    override val packVersionsDiffVsInstalled    = "К установленной"
    override val packVersionsIdentical          = "Файлы не менялись: пересборка с новой меткой"
    override val packVersionsFirstBuild         = "Первый билд пака, сравнивать не с чем"
    override fun packVersionsAdded(n: Int)      = "Добавлено ($n)"
    override fun packVersionsUpdated(n: Int)    = "Обновлено ($n)"
    override fun packVersionsRemoved(n: Int)    = "Удалено ($n)"
    override val packVersionsSectionMods        = "Моды"
    override val packVersionsSectionAssets      = "Файлы пака"
    override val packVersionsSectionPack        = "Параметры"
    override val packVersionsNotes              = "Заметки к билду"
    override val packVersionsSwitchTo           = "Переключиться на этот билд"
    override val packVersionsConfirmTitle       = "Переключить версию?"
    override fun packVersionsConfirmBody(from: String, to: String) =
        "Сборка перейдёт с $from на $to. Перед применением снимается точка восстановления."
    override fun packVersionsPlanCounts(add: Int, update: Int, remove: Int) = "Изменения: +$add, ~$update, -$remove"
    override fun packVersionsConflicts(n: Int) =
        "$n ${russianPlural(n, "конфликт", "конфликта", "конфликтов")} с вашими правками: файлы пака лягут рядом как .new"
    override fun packVersionsApplying(current: Int, total: Int, name: String) = "Применение $current/$total: $name"
    override fun packVersionsApplied(version: String) = "Готово: установлен билд $version"
    override fun packVersionsFailed(reason: String) = "Не получилось: $reason"
    override val packVersionsRetry              = "Повторить"
    override val packVersionsLoadError          = "Зеркало недоступно, список версий не загрузился"

    override val packSettingsTitle              = "Настройки сборки"
    override val packSettingsClose              = "Закрыть"
    override val packSettingsCategoryGeneral    = "Основное"
    override val packSettingsCategoryRuntime    = "Запуск"
    override val packSettingsCategoryVersion    = "Версия"
    override val packSettingsCategoryContent    = "Содержимое"
    override val packSettingsCategoryData       = "Данные"
    override val packSettingsIdentity           = "Идентичность"
    override val packSettingsName               = "Название"
    override val packSettingsNamePlaceholder    = "Название сборки"
    override val packSettingsNotes              = "Заметки"
    override val packSettingsNotesPlaceholder   = "Заметки для себя"
    override val packSettingsSource             = "Источник"
    override fun packSettingsForkedFrom(name: String) = "Ответвление от $name"
    override val packSettingsPackId             = "ID пака"
    override val packSettingsMemory             = "Память"
    override val packSettingsEnvironment        = "Среда"
    override val packSettingsJava               = "Java"
    override fun packSettingsJavaManaged(major: Int) = "Управляемая — Java $major"
    override val packSettingsJavaCustom         = "Свой путь к Java"
    override val packSettingsJavaPathPlaceholder = "/путь/к/bin/java"
    override val packSettingsJavaReset          = "На управляемую"
    override val packSettingsJvmArgs            = "Аргументы JVM"
    override val packSettingsJvmArgsDefault     = "По умолчанию"
    override val packSettingsJvmArgsEdit        = "Изменить"
    override val packSettingsWindow             = "Окно игры"
    override val packSettingsWindowOverride     = "Свой размер окна"
    override val packSettingsWindowOverrideDesc = "Иначе клиент запоминает размер сам"
    override val packSettingsWidth              = "Ширина"
    override val packSettingsHeight             = "Высота"
    override val packSettingsFullscreen         = "Полноэкранный режим"
    override val packSettingsOptional           = "Опциональное содержимое"
    override val packSettingsOptionalNone       = "У этой сборки нет опций"
    override val packContentPresenceClient      = "Только клиент"
    override val packContentPresenceServer      = "Только сервер"
    override val packContentPresenceBoth        = "Клиент и сервер"
    override val packContentPresenceCoremod     = "Coremod"
    override val packSettingsDependencies       = "Зависимости"
    override val packSettingsDependenciesNone   = "Всё на месте"
    override fun packSettingsMissing(name: String) = "Не хватает: $name"
    override val packSettingsContentUnavailable = "Список недоступен без манифеста"
    override val packSettingsContentLoading     = "Загрузка"
    override val packSettingsStorage            = "Размещение"
    override val packSettingsFolder             = "Папка сборки"
    override val packSettingsOpenFolder         = "Открыть"
    override val packSettingsSizeComputing      = "подсчёт размера"
    override val packSettingsDetach             = "Отсоединить в локальную"
    override val packSettingsDetachDesc         = "Стать своей копией; провенанс сохранится"
    override val packSettingsDetachAction       = "Отсоединить"
    override val packSettingsRepair             = "Проверить и восстановить файлы"
    override val packSettingsRepairDesc         = "Заново синхронизировать сборку с зеркалом"
    override val packSettingsRepairAction       = "Восстановить"
    override val packSettingsRepairDone         = "Файлы пересинхронизированы"
    override val packSettingsDangerZone         = "Опасная зона"
    override val packSettingsDelete             = "Удалить сборку"
    override val packSettingsDeleteDesc         = "Файлы инстанса будут стёрты безвозвратно"
    override val packVersionSnapshots           = "Точки восстановления"
    override val packVersionRestore             = "Восстановить"
    override val packVersionSnapshotsHint       = "Снимок сохраняет ваши правки; создаётся перед структурным обновлением"
    override val consoleSessionLive             = "Общий"
    override fun consoleSessionPickerLabel(current: String) = "Лог: $current"

    override val packDetailReadyTitle           = "Готов к запуску"
    override fun packDetailInstanceDirHint(dirName: String) = "Папка экземпляра: instances/$dirName"
    override val packDetailPlay                 = "Играть"
    override val packDetailPlayLoginRequired    = "Войдите, чтобы играть"
    override val packPlayWait                   = "Подождите"
    override val packPlayExit                   = "Выход"
    override val packDetailNotFoundTitle        = "Экземпляр не найден"
    override val packDetailNotFoundHint         = "Возможно, удалён в другом окне."
    override val packDetailNotFoundBack         = "Назад в Library"

    // --- Notification subsystem ---
    override val notificationExpandHistory   = "Раскрыть историю уведомления"
    override val notificationCollapseHistory = "Свернуть историю уведомления"
    override val notificationDismiss         = "Закрыть уведомление"
    override val notifHistoryEmpty           = "Сообщений пока нет"
    override val notifHistoryClear           = "Очистить"
    override val notifDoNotDisturb           = "Не беспокоить"
    override fun notifGroupCount(count: Int) = "×$count"
    override fun notifCountTitle(count: Int): String {
        val n  = count % 100
        val n1 = count % 10
        val word = when {
            n in 11..14 -> "сообщений"
            n1 == 1     -> "сообщение"
            n1 in 2..4  -> "сообщения"
            else        -> "сообщений"
        }
        return "$count $word"
    }
    override fun notificationShowMore(count: Int)               = "ещё $count"
    override fun notificationAbsoluteTime(instant: java.time.Instant): String =
        java.time.format.DateTimeFormatter
            .ofPattern("d MMMM yyyy, HH:mm:ss", java.util.Locale.of("ru", "RU"))
            .withZone(java.time.ZoneId.systemDefault())
            .format(instant)

    override fun notifPackPreparing(packName: String)   = "Подготовка $packName"
    override fun notifPackStage(stage: String)          = "Этап: $stage"
    override fun notifPackSyncing(packName: String)     = "Синхронизация $packName"
    override fun notifPackSyncBody(current: Int, total: Int, pctLabel: String) =
        "$current/$total файлов, $pctLabel"
    override val notifPackSyncIndeterminate             = "загрузка..."
    override fun notifPackSyncPercent(pct: Int)         = "$pct%"
    override fun notifPackRunning(packName: String)     = "$packName запущен"
    override fun notifPackFailed(packName: String)      = "$packName не удалось запустить"
    override fun notifPackSessionEnded(packName: String) = "Сессия $packName завершена"
    override fun notifInstallSyncing(packName: String)  = "Установка $packName"
    override fun notifInstallDone(packName: String)     = "$packName установлен"
    override fun notifPackUpdatePending(packName: String, version: String) = "$packName: доступен билд $version"
    override fun notifPackUpdated(packName: String, version: String) = "$packName обновлён до $version"
    override fun notifPackUpdateFailed(packName: String) = "$packName: обновление не удалось"
    override val notifActionOpenVersions                = "Открыть версии"
    override fun notifInstallFailed(packName: String)   = "$packName не удалось установить"
    override fun notifInstallCancelled(packName: String) = "Установка $packName отменена"
    override val notifActionCancel                      = "Отменить"
    override val notifActionShowConsole                 = "Открыть консоль"
    override val notifActionStop                        = "Остановить"
    override val notifActionPlayOffline                 = "Играть офлайн"
    override fun notifReasonExitCode(code: Int)         = "Игра завершилась с кодом $code"
    override val notifReasonInternal                    = "Внутренняя ошибка"
    override fun notifReasonInternalDetail(detail: String) = detail
    override val notifReasonAuthFail                    = "Не удалось войти"
    override fun notifReasonAuthFailDetail(detail: String) = detail
    override val notifReasonOfflineNoClient             = "Файлы сборки отсутствуют на диске"
    override val notifReasonOfflineNoManifest           = "Нет кэша манифеста; выйди в сеть один раз для синхронизации"
    override val notifReasonTwoFactorExpired            = "Войди ещё раз, чтобы обновить учётные данные"
    override fun notifReasonMissingAuthProvider(providerKey: String) = when (providerKey) {
        PackAuthRequirement.SmartyCraft.PROVIDER_KEY -> "Войдите в SmartyCraft, чтобы играть на этой сборке"
        else                                          -> "Нужен вход в '$providerKey', чтобы играть"
    }

    override val notifTimeNow                           = "сейчас"
    override fun notifTimeSeconds(seconds: Long)        = "$seconds с"
    override fun notifTimeMinutes(minutes: Long)        = "$minutes мин"
    override fun notifTimeHours(hours: Long)            = "$hours ч"
    override fun notifTimeDays(days: Long)              = "$days дн"

    // --- Home (new) + launch tiles ---
    override val homeRecentTitle    = "Твои сборки"
    override val homeNoPacksTitle   = "Сборок пока нет"
    override val homeNoPacksBody    = "Установи что-нибудь через Browse — твои сборки появятся здесь."
    override val browseOpen         = "Открыть Browse"
    override val homeQuickContinue  = "Продолжить"
    override val homeQuickStart     = "Запустить"
    override val homeQuickButton    = "Играть"
    override fun homeHeroPlaytime(hours: Long) = "В игре $hours ч"
    override val launchTileReady    = "Запустить"
    override val launchTileBlocked  = "Играть нельзя"

    // --- Library widgets ---
    override val libraryEmptyTitle     = "Пока пусто"
    override val libraryEmptyBody      = "Установите сборку через Browse — она появится здесь."
    override val libraryHeaderTitle    = "Библиотека"
    override val libraryHeaderSubtitle = "Установленные сборки"

    // --- Customization widget labels ---

    // --- Layout editor: common actions ---
    override val editorClose   = "Закрыть"
    override val editorCancel  = "Отмена"
    override val editorDelete  = "Удалить"
    override val editorReset   = "Сбросить"
    override val editorUnsupportedWidget = "Неподдерживаемый виджет"
    override val editorResetAll = "Сбросить всё"
    override val editorToFront = "На передний план"
    override val editorToBack = "На задний план"
    override val widgetLabels: Map<String, String> = mapOf(
        "widget.about.credits" to "Авторы и технологии",
        "widget.about.credits.title" to "Заголовок (авторы)",
        "widget.about.links.card" to "Ссылки",
        "widget.about.links.card.title" to "Заголовок",
        "widget.about.logo" to "Логотип и версия",
        "widget.about.logo.title" to "Заголовок",
        "widget.about.logo.showVersion" to "Показывать версию",
        "widget.about.logo.showBuildDate" to "Показывать дату сборки",
        "widget.about.logo.showTagline" to "Показывать подзаголовок",
        "widget.about.system.card" to "Система",
        "widget.about.system.card.title" to "Заголовок",
        "widget.about.update.panel" to "Обновления",
        "widget.about.update.panel.title" to "Заголовок",
        "widget.appshell.region.center" to "Центральная область",
        "widget.appshell.region.collapsed" to "Свёрнут",
        "widget.appshell.region.swipeToCollapse" to "Сворачивать свайпом",
        "widget.appshell.region.frostTier" to "Матовость",
        "widget.appshell.region.glassAlphaPct" to "Стекло, %",
        "widget.appshell.region.left" to "Левая панель",
        "widget.appshell.region.top" to "Шапка окна",
        "widget.appshell.region.body" to "Основная область",
        "widget.appshell.topbar.breadcrumb" to "Хлебные крошки",
        "widget.appshell.topbar.heightDp" to "Высота",
        "widget.appshell.topbar.cornerStyle" to "Углы",
        "widget.appshell.topbar.groupStyle" to "Группировка",
        "widget.appshell.topbar.frostTier" to "Матовость",
        "widget.appshell.topbar.controls" to "Кнопки окна",
        "widget.appshell.region.right" to "Правая панель",
        "widget.appshell.region.showDivider" to "Разделитель",
        "widget.appshell.region.widthDp" to "Ширина (0 — гибкая)",
        "widget.appshell.rightrail.compactnews" to "Лента новостей",
        "widget.appshell.rightrail.compactnews.maxItems" to "Макс. элементов (0 = все)",
        "widget.appshell.rightrail.compactnews.showTitle" to "Показывать заголовок",
        "widget.bg.enable.toggle" to "Фон вкл/выкл",
        "widget.bg.fx.animspeed" to "Скорость анимации",
        "widget.bg.fx.blur" to "Размытие",
        "widget.bg.fx.darken" to "Затемнение",
        "widget.bg.fx.opacity" to "Прозрачность",
        "widget.bg.fx.parallax" to "Параллакс",
        "widget.bg.fx.saturation" to "Насыщенность",
        "widget.bg.fx.vignette" to "Виньетка",
        "widget.bg.image.picker" to "Картинка фона",
        "widget.bg.loop.mode" to "Цикл воспроизведения",
        "widget.bg.position.x" to "Позиция X",
        "widget.bg.position.y" to "Позиция Y",
        "widget.bg.preview" to "Превью",
        "widget.bg.reset" to "Сброс фона",
        "widget.bg.scale.mode" to "Масштабирование",
        "widget.bg.tint" to "Тонировка",
        "widget.container.group" to "Группа",
        "widget.checklist" to "Чеклист",
        "widget.checklist.add" to "Добавить пункт...",
        "widget.checklist.empty" to "Пока пусто",
        "widget.checklist.hideCompleted" to "Скрывать выполненные",
        "widget.checklist.title" to "Заголовок",
        "widget.container.tabs" to "Вкладки",
        "widget.container.tabs.label1" to "Вкладка 1",
        "widget.container.tabs.label2" to "Вкладка 2",
        "widget.container.tabs.label3" to "Вкладка 3",
        "widget.container.tabs.tabCount" to "Вкладок",
        "widget.home.classic.content" to "Классический дашборд",
        "widget.home.new.clock" to "Часы",
        "widget.home.new.clock.accent" to "Цвет акцента",
        "widget.home.new.clock.faceSize" to "Размер циферблата",
        "widget.home.new.clock.format24h" to "24-часовой формат",
        "widget.home.new.clock.mode" to "Режим",
        "widget.home.new.clock.showSeconds" to "Секунды",
        "widget.home.new.clock.title" to "Заголовок",
        "widget.home.new.hero" to "Hero-карта пака",
        "widget.home.new.hero.height" to "Высота",
        "widget.home.new.hero.showMeta" to "Метаданные",
        "widget.home.new.launchbutton" to "Кнопка запуска",
        "widget.home.new.launchbutton.label" to "Надпись",
        "widget.home.new.music" to "Музыкальный плеер",
        "widget.home.new.music.title" to "Заголовок",
        "widget.home.new.playback.mini" to "Мини-плеер",
        "widget.home.new.progress" to "Фоновая активность",
        "widget.home.new.progress.idleText" to "Текст простоя",
        "widget.home.new.progress.title" to "Заголовок",
        "widget.home.new.quicklaunch" to "Быстрый запуск",
        "widget.home.new.quicklaunch.buttonLabel" to "Надпись кнопки",
        "widget.home.new.recent" to "Плитки сборок",
        "widget.home.new.recent.maxTiles" to "Сколько плиток",
        "widget.home.new.recent.title" to "Заголовок",
        "widget.home.new.spacer" to "Отступ",
        "widget.home.new.spacer.height" to "Высота",
        "widget.home.new.video" to "Видео-плеер",
        "widget.home.new.video.url" to "Ссылка на видео",
        "widget.home.new.welcome" to "Баннер приветствия",
        "widget.home.new.welcome.customGreeting" to "Свой текст приветствия",
        "widget.home.new.welcome.showSubtitle" to "Показывать подзаголовок",
        "widget.library.body" to "Тело библиотеки",
        "widget.library.body.emptyText" to "Текст пустого состояния",
        "widget.library.body.emptyTitle" to "Заголовок пустого состояния",
        "widget.library.header" to "Шапка библиотеки",
        "widget.library.header.subtitle" to "Подзаголовок",
        "widget.library.header.title" to "Заголовок",
        "widget.library.header.show" to "Показывать шапку",
        "widget.nav.entry" to "Пункт навигации",
        "widget.notes.scratch" to "Заметки",
        "widget.notes.scratch.placeholder" to "Напишите что-нибудь...",
        "widget.notes.scratch.title" to "Заголовок",
        "widget.notifications.history" to "История сообщений",
        "widget.notifications.history.expandUp" to "Раскрывать вверх",
        "widget.notifications.history.clock12h" to "12-часовой формат (am/pm)",
        "widget.notifications.history.verticalTime" to "Время в столбик",
        "widget.profile.account.section" to "SmartyCraft",
        "widget.profile.signin" to "Microsoft",
        "widget.profile.nav" to "Навигация профиля",
        "widget.profile.skin.section" to "Скин",
        "widget.profile.skin.section.previewHeight" to "Высота превью",
        "widget.server.details.banner" to "Баннер сервера",
        "widget.server.details.banner.cornerRadius" to "Скругление углов",
        "widget.server.details.description" to "Описание сервера",
        "widget.server.details.tagbar" to "Теги сервера",
        "widget.server.details.title" to "Заголовок сервера",
        "widget.theme.picker.grid" to "Сетка тем",
        "widget.theme.picker.preview" to "Превью темы",
    )
    override val recoverySafeModeTitle = "Интерфейс не удаётся восстановить"
    override val recoverySafeModeBody  = "Интерфейс падал несколько раз подряд. Отчёт о сбое сохранён на диск. Перезапусти лаунчер."
    override val recoverySafeModeQuit  = "Выйти"

    override val recoveryTitle              = "Режим восстановления"
    override val recoveryBody               = "Отключи модуль или сбрось повреждённое состояние, затем продолжи. Изменения применятся после перезапуска лаунчера."
    override val recoveryModulesHeading     = "Отключить модули"
    override val recoveryModuleTray         = "Системный трей"
    override val recoveryModuleNotify       = "Уведомления"
    override val recoveryModuleSkinema      = "Медиа-фоны"
    override val recoveryModuleKeyring      = "Системный keyring"
    override val recoveryResetsHeading      = "Сброс"
    override val recoveryResetLayout        = "Вёрстка"
    override val recoveryResetCustomization = "Кастомизация"
    override val recoveryResetSettings      = "Настройки"
    override val recoveryContinue           = "Продолжить обычную загрузку"
    override val recoveryRelaunchFailed     = "Не удалось перезапустить автоматически. Открой лаунчер заново."
    override val recoveryRestartInApp       = "Перезапустить в режиме восстановления"
    override val thresholdStageFiles     = "проверяем файлы"
    override val thresholdStageNetwork   = "состояние сети"
    override val thresholdStageMigration = "проверка миграции"
    override val thresholdStageModules   = "запускаем модули"
    override val thresholdErrorTitle     = "запуск не удался"
    override val thresholdOpenLogs       = "папка логов"
    override val thresholdQuit           = "выйти"
    override val recoveryReloadedNotice = "Интерфейс перезапущен после ошибки"
    override val editorSave    = "Сохранить"
    override val editorApply   = "Применить"
    override val editorExport  = "Экспорт"
    override val editorWidgets = "Виджеты"

    // --- Layout editor: slot orientation ---
    override val editorSlotStack  = "Стек"
    override val editorSlotRow    = "Ряд"
    override val editorSlotGrid   = "Сетка"
    override val editorSlotCanvas = "Холст"
    override val editorSlotCubeGrid = "Кубы"
    override val editorSlotLayoutMenuTitle     = "Раскладка"
    override val editorSlotGridColumns         = "Столбцы"
    override val editorSlotGridColumnsDecrease = "Меньше столбцов"
    override val editorSlotGridColumnsIncrease = "Больше столбцов"
    override val editorSlotLayoutHandle        = "Раскладка слота"

    // --- Layout editor: prop panel ---
    override val editorResetToDefault = "Сбросить к умолчанию"
    override val editorBackingTitle   = "Подложка"
    override val editorSurfaceSettings = "Настройки"
    override val editorBackingGlass   = "Непрозрачность стекла"
    override val editorBackingCorner  = "Скругление"
    override val editorBackingPadding = "Отступ (все стороны)"
    override val editorBackingPaddingTop    = "Отступ сверху"
    override val editorBackingPaddingEnd    = "Отступ справа"
    override val editorBackingPaddingBottom = "Отступ снизу"
    override val editorBackingPaddingStart  = "Отступ слева"
    override val editorBackingNoGlassHint   = "Без стекла подложка не видна. Скругление и отступ всё равно применяются к виджету."

    // --- Layout editor: presets ---
    override val editorPresetsTitle          = "Пресеты"
    override val editorPresetsIntro          = "Снимок layout + темы + стиля. Сохрани сейчас, загрузи когда угодно."
    override val editorPresetNamePlaceholder = "Имя пресета..."
    override fun editorPresetsSaved(count: Int) = "Сохранённые ($count)"
    override val editorPresetsEmpty          = "Пусто. Сохрани текущий layout как первый пресет."

    // --- Layout editor: palette ---
    override val editorPaletteHide  = "Скрыть палитру"
    override val editorPaletteHint  = "Перетащи в нужный слот"
    override val editorPaletteEmpty = "Реестр виджетов пуст (ошибка сборки)."
    override val editorPaletteSearch = "Поиск виджетов…"
    override val editorPaletteNoMatch = "Ничего не найдено"

    // --- Layout editor: empty slot + chrome ---
    override val editorDragWidgetHere   = "Перетащи виджет сюда"
    override val editorDragReorder      = "Перетащить"
    override val editorConfigure        = "Настроить"
    override val editorForceRemove      = "Удалить принудительно"
    override val editorForceRemoveTitle = "Удалить виджет принудительно?"
    override fun editorForceRemoveBody(name: String) =
        "\"$name\" помечен как неудаляемый. Обычно такие виджеты держат на месте, чтобы пользователь не остался без навигации. Если ты уверен, что виджет тут не нужен, можно снести его прямо сейчас. А если что, сбрось поверхность к умолчанию через меню справа от чипа поверхности."

    // --- Layout editor: host (reset / pill / fab) ---
    override val editorResetSurfaceTitle = "Сбросить поверхность к умолчанию?"
    override fun editorResetSurfaceBody(name: String) =
        "\"$name\" вернётся к расстановке виджетов из встроенного default-layout. Все локальные изменения на этой поверхности (добавленные виджеты, перестановки, удаления) пропадут. Другие поверхности не тронем."
    override val editorPreview           = "Просмотр"
    override val editorPreviewHidden     = "Скрыто"
    override val editorPaletteToggleHide = "Скрыть"
    override val editorEscHint           = "Esc — выйти"
    override val editorFabEdit           = "Редактировать раскладку"
    override val editorFabDone           = "Готово"

    // --- Layout editor: surface short names ---
    override val editorSurfShortHome      = "Главная"
    override val editorSurfShortLibrary   = "Библиотека"
    override val editorSurfShortLeftRail  = "Лев. рейл"
    override val editorSurfShortRightRail = "Прав. рейл"
    override val editorSurfShortAbout     = "О приложении"
    override val editorSurfShortBg        = "Фон"
    override val editorSurfShortProfile   = "Профиль"
    override val editorSurfShortServer    = "Сервер"
    override val editorSurfShortTheme     = "Темы"
    override val editorSurfShortShell     = "Оболочка"
    override val editorSurfShortTopBar    = "Верх"
    override val editorSurfShortBody      = "Область"

    // --- Layout editor: surface long names ---
    override val editorSurfHomeClassic = "Главная (классика)"
    override val editorSurfHomeNew     = "Главная (новая)"
    override val editorSurfLibrary     = "Библиотека"
    override val editorSurfLeftRail    = "Боковая панель"
    override val editorSurfRightRail   = "Правая панель"
    override val editorSurfAbout       = "О приложении"
    override val editorSurfBg          = "Настройки фона"
    override val editorSurfProfile     = "Профиль"
    override val editorSurfServer      = "Детали сервера"
    override val editorSurfTheme       = "Выбор темы"
    override val editorSurfShell        = "Оболочка приложения"
    override val editorSurfTopBar       = "Верхняя панель"
    override val editorSurfBody         = "Основная область"

    // --- Music player widgets ---
    override val musicPlayerTitle      = "Музыкальный плеер"
    override val audioPlay             = "Воспроизвести"
    override val audioPause            = "Пауза"
    override val audioStop             = "Стоп"
    override val audioOpenFile         = "Открыть файл"
    override val audioPickTrack        = "Выбери трек"
    override val audioVolume           = "Громкость"
    override val audioNoFile           = "Без файла"
    override val audioStatusReady      = "Готов"
    override val audioStatusPlaying    = "Играет"
    override val audioStatusPaused     = "Пауза"
    override val audioFormatHint       = "MP3, FLAC, OGG, WAV и другие."
    override val audioNoPlayerHere     = "Нет плеера на этой раскладке"
    override val audioAddMusicPlayer   = "Добавь Music player"
    override val audioErrorUnsupported = "Формат не поддерживается или файл повреждён."
    override val audioErrorOpenFailed  = "Не удалось открыть файл"
    override val audioErrorDeviceBusy  = "Аудиоустройство занято"
    override val audioErrorPlaybackFailed = "Ошибка воспроизведения"

    // --- Video player ---
    override val videoFullscreen     = "Во весь экран"
    override val videoExitFullscreen = "Выйти из полноэкранного"
    override val videoMute           = "Без звука"
    override val videoUnmute         = "Включить звук"
    override val videoReplay         = "Заново"
    override val videoError          = "Не удалось воспроизвести видео"
    override val videoLoading        = "Загрузка видео…"
    override val videoOpenInBrowser  = "Открыть в браузере"
    override val videoSkipBack        = "Назад 10 секунд"
    override val videoSkipForward     = "Вперёд 10 секунд"
    override val videoWidgetEmpty     = "Укажите ссылку на видео в настройках виджета"

    // --- Library pack card ---
    override val packCardPlay          = "Играть"
    override val packCardSettings      = "Настройки"
    override val packCardMore          = "Ещё"
    override val packCardDeleteTitle   = "Удалить инстанс?"
    override val packCardDeleteBody    = "Инстанс и все его файлы (миры, настройки, моды) удалятся навсегда. Отменить нельзя."
    override val packCardNeverPlayed   = "Не запускался"
    override val packCardPlayedJustNow = "только что"
    override fun packCardPlayedMinutesAgo(n: Long) = "$n мин назад"
    override fun packCardPlayedHoursAgo(n: Long)   = "$n ч назад"
    override fun packCardPlayedDaysAgo(n: Long)    = "$n дн назад"
    override val packCardPlayedLongAgo = "давно"

    // --- Session chip + about logo a11y ---
    override val sessionsActiveTitle = "Активные сессии"
    override val aboutLogoDesc       = "Логотип приложения"
}
