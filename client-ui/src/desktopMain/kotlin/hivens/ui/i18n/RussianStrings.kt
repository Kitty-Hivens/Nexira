package hivens.ui.i18n

object RussianStrings : AppStrings {

    // App
    override val appName = "Aura Launcher"

    // Login
    override val loginTitle        = "Aura Launcher"
    override val loginUsername     = "Логин"
    override val loginPassword     = "Пароль"
    override val loginRemember     = "Запомнить пароль"
    override val loginButton       = "ВОЙТИ"
    override val loginErrorEmpty   = "Введите логин и пароль"
    override val loginErrorGeneric = "Ошибка входа"
    override val loginRegister     = "Зарегистрироваться"

    // Navigation
    override val navLogout   = "Выйти"
    override val navBack     = "Назад"

    // Dashboard
    override fun dashboardWelcome(name: String) = "ДОБРО ПОЖАЛОВАТЬ, $name"
    override val dashboardServers              = "ДОСТУПНЫЕ СЕРВЕРЫ"
    override val dashboardServersEmpty         = "Серверы не найдены"
    override val dashboardLoginRequiredTitle   = "Войдите, чтобы увидеть серверы"
    override val dashboardLoginRequiredHint    = "Воспользуйтесь панелью справа. Список серверов на SMARTYcraft скрыт за авторизацией."

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
    override val settingsCloseAfterLaunch   = "Свернуть лаунчер в трей после запуска сервера"
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
    override val newsEmpty   = "Новостей пока нет..."

    // Server Detail
    override val serverDetailTitle         = "ИНФОРМАЦИЯ О СЕРВЕРЕ"
    override val serverDetailNoImage       = "Нет изображения"
    override val serverDetailNoImageHint   = "banner.png"
    override val serverDetailMissingTitle  = "Информация отсутствует"
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
    override fun consoleHeaderCount(filtered: Int, total: Int) = "Вывод игры ($filtered/$total)"
    override val consoleCopyAll = "Копировать всё"
    override val consoleClear   = "Очистить"
    override val consoleWrap    = "Перенос строк"
    override val consoleSaveToFile = "Сохранить в файл"
    override val consoleSearchPlaceholder = "Поиск…"
    override val consoleJumpToBottom = "↓ К концу"

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
    override fun fileDownloading(n: Int) = "Загрузка обновлений ($n файлов)..."

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
    override val trayStatusIdle    = "Ожидание"
    override val trayStatusRunning = "Игра запущена"
    override val trayShow          = "Открыть лаунчер"
    override val trayServers       = "Серверы"
    override val trayNoServers     = "Серверы не загружены"


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
    override val settingsJvmBuilder             = "Визуальный конструктор JVM-аргументов"
    override val settingsJvmBuilderDesc         = "Показывает кнопку «Собрать аргументы» в настройках сервера. Выбираешь сборщик мусора, настраиваешь регионы хипа, включаешь AppCDS или JFR — без необходимости помнить флаги. Готовые пресеты: Aikar's recipe, GTNH-класс, ZGC для больших хипов и другие."
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

    // --- 2FA (TOTP) — #159 ---
    override val auth2faTitle           = "Двухфакторная аутентификация"
    override val auth2faPrompt          = "Введите 6-значный код из приложения-аутентификатора, чтобы завершить вход."
    override val auth2faPlaceholder     = "000000"
    override val auth2faSubmit          = "Подтвердить"
    override val auth2faCancel          = "Отмена"
    override val auth2faInvalid         = "Неверный код. Попробуйте снова."
    override val auth2faExpired         = "Сессия 2FA истекла. Пожалуйста, войдите заново."

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

    override val settingsSectionDataDir       = "Каталог данных"
    override val settingsDataDirCurrent       = "Текущий путь:"
    override val settingsDataDirMove          = "Переместить..."
    override val settingsDataDirPickerTitle   = "Выбери новое место для данных Aura"
    override val settingsDataDirConfirmTitle  = "Переместить каталог данных?"
    override fun settingsDataDirConfirmBody(source: String, target: String) =
        "Aura перенесёт данные:\nиз: $source\nв:  $target\n\nПеремещение применится при перезапуске лаунчера."
    override val settingsDataDirRestartRequired = "Требуется перезапуск — Aura применит перемещение при следующем старте"
    override val settingsDataDirQuitNow         = "Выйти сейчас"
    override val settingsDataDirErrorSamePath   = "Это и есть текущий каталог — выбери другую папку"
    override val settingsDataDirErrorNotEmpty   = "Целевая папка не пуста — выбери пустую папку или удали её содержимое"

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
    override fun jvmPreviewFlagsCount(n: Int) = "Превью ($n флагов)"

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
    override val jvmJfrSettingsProfileHint  = "Method-level профайлинг. ~5% overhead."
    override val jvmJfrOutputPathLabel      = "Путь к выходному .jfr (опционально)"

    override val jvmCustomHeader = "Свои флаги"
    override val jvmCustomIntro  = "Дополнительные флаги добавляются как есть. Для одноразовых экспериментов или vendor-флагов которые мы ещё не вывели в UI. Через пробел."
    override val jvmCustomLabel  = "Дополнительные аргументы"
}
