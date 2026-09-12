package hivens.ui.i18n

import hivens.core.data.PackAuthRequirement
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Japanese strings.
 *
 * Two things differ structurally from the other locales rather than being a
 * matter of wording. Japanese has a single plural form, so nothing here calls a
 * count selector: where English picks between "file" and "files", this file
 * writes the counter once. And a placeholder is braced wherever the text runs
 * straight into it, because Kotlin accepts CJK characters inside an identifier
 * and would otherwise read the whole of "$n分前" as a variable name.
 */
object JapaneseStrings : AppStrings {

    // App
    override val appName = "Nexira"

    // Login
    override val loginTitle = "Nexira"
    override val loginUsername = "ユーザー名"
    override val loginPassword = "パスワード"
    override val loginRemember = "パスワードを保存"
    override val loginButton = "ログイン"
    override val loginErrorEmpty = "ユーザー名とパスワードを入力してください"
    override val loginErrorGeneric = "ログインエラー"
    override val loginRegister = "アカウントを作成"
    override val loginPlayOffline = "オフラインでプレイ"
    override val loginMicrosoft = "Microsoft でサインイン"
    override val msaTitle = "Microsoft でサインイン"
    override val msaInstruction = "このページを開いてコードを入力してください:"
    override val msaCopyCode = "コードをコピー"
    override val msaOpenBrowser = "ページを開く"
    override val msaWaiting = "確認を待っています..."

    // Navigation
    override val navLogout = "ログアウト"
    override val navBack = "戻る"
    override val navForward = "進む"

    // Dashboard
    override fun dashboardWelcome(name: String) = "おかえりなさい、${name}"
    override val dashboardServers = "利用可能なサーバー"
    override val dashboardServersEmpty = "サーバーが見つかりません"
    override val dashboardLoginRequiredTitle = "サインインしてサーバーを表示"
    override val dashboardLoginRequiredHint = "SmartyCraft のサーバー一覧は認証の内側にあります。プロフィールからサインインしてください。"

    // Launch Control
    override val launchReady = "プレイ準備完了"
    override val launchButton = "プレイ"
    override val launchAbort = "キャンセル"
    override val launchRunning = "ゲーム実行中"
    override val launchStop = "停止"
    override val launchDownloading = "ダウンロード中:"
    override val launchPreparing = "準備中"
    override val launchFailed = "起動に失敗しました"

    // Launcher States
    override val stateInit = "初期化中..."
    override val stateAuth = "認証中..."
    override val stateAuthFail = "認証エラー (オフライン?)"
    override val stateNoPassword = "パスワードが見つからないため、現在のセッションを使用します。"
    override val stateSync = "ファイルを同期中..."
    override val stateJvm = "JVM を準備中..."
    override val stateLaunching = "プロセスを開始中..."
    override fun stateExitCode(code: Int) = "ゲームが終了コード $code で終了しました"
    override fun stateError(msg: String) = "エラー: ${msg}"
    override fun stateHelperUnavailable(mcVersion: String) =
        "Minecraft $mcVersion 向けの open-smrt ヘルパーがありません。独自の Smarty Mod を動かさないよう起動を中止しました。それでも使う場合は、設定でヘルパーの置き換えを無効にしてください。"
    override fun stateAuthlibUnavailable(mcVersion: String) =
        "Minecraft $mcVersion 向けの SmartyCraft authlib を取得できませんでした。参加が拒否されるため起動を中止しました。接続と SmartyCraft へのサインインを確認して、もう一度お試しください。"
    override fun stateMissingAuthProvider(providerKey: String) = when (providerKey) {
        PackAuthRequirement.SmartyCraft.PROVIDER_KEY ->
            "このパックには SmartyCraft のアカウントが必要です。サインインしてください。"
        else ->
            "このパックは '$providerKey' でのサインインが必要です。"
    }
    override fun authSuccess(uuid: String) = "ログインに成功しました。UUID: ${uuid}"

    // Profile
    override val profileTitle = "プロフィール"
    override val profileStatusLabel = "状態"
    override val profileStatusOnline = "認証済み"
    override val profileStatusOffline = "オフライン"
    override val profileBalance = "残高"
    override val profileTopUp = "残高をチャージ"
    override val profileUploadSkin = "スキンをアップロード"
    override val profileUploadSkinLoading = "アップロード中..."
    override val profileSkinLoading = "スキンを読み込み中..."
    override val profileRefresh = "更新"
    override val profileUploadSuccess = "スキンをアップロードしました"
    override fun profileUploadError(msg: String) = "アップロードエラー: ${msg}"

    // Settings
    override val settingsTitle = "全体設定"
    override val settingsSectionUI = "インターフェース"
    override val settingsSectionBehavior = "動作"
    override val settingsThemePicker = "テーマを選択"
    override val settingsThemePickerSub = "配色をカスタマイズ"
    override val settingsDarkTheme = "ダークテーマ"
    override val settingsDarkThemeDesc = "暗いインターフェーステーマ"
    override val settingsThemeModeTitle = "テーマの取得元"
    override val settingsThemeModeManual = "手動"
    override val settingsThemeModeSystem = "システム"
    override val settingsThemeModeWallpaper = "壁紙"
    override val settingsThemeModeSystemUnavailable = "この環境ではシステムの配色を利用できません"
    override val settingsPaletteFromWallpaper = "壁紙から配色を生成"
    override val settingsPaletteFromWallpaperDesc = "オフの場合、テーマは自身の配色を保ちます"
    override val settingsSurfaceBlur = "パネル背面をぼかす"
    override val settingsSurfaceBlurDesc = "毎フレームわずかに負荷がかかります。オフでもパネルの形と不透明度は変わりません"
    override val settingsCustomChrome = "アプリ内タイトルバー"
    override val settingsCustomChromeDesc = "ウィンドウのタイトルバーをアプリ独自の上部バーに置き換えます。次回の起動から有効になります。"
    override val settingsCustomChromeTiling = "お使いのウィンドウマネージャーはタイトルバーを描画しないため、ここでは何も変わりません。"
    override val settingsCloseAfterLaunch = "ゲーム開始後にランチャーを隠す"
    override val settingsCloseAfterLaunchDesc = "ゲームが始まるとランチャーをトレイに隠します。トレイがない環境ではウィンドウを最小化します。"
    override val settingsSaved = "設定を保存しました"
    override val settingsLanguage = "言語"

    // Theme Picker
    override val themePickerTitle = "テーマを選択"
    override val themePickerApply = "適用"
    override val themePickerPreview = "プレビュー"
    override val themePickerSelected = "選択中"
    override val themePickerColorPrimary = "プライマリ"
    override val themePickerColorSecondary = "セカンダリ"
    override val themePickerColorBackground = "背景"
    override val themePickerColorSurface = "サーフェス"
    override val themePickerColorAccent = "アクセント"
    override val themePickerColorSuccess = "成功"
    override val themePickerColorError = "エラー"
    override val themePickerBtnSample = "サンプルボタン"
    override val themePickerBtnOutlined = "枠線ボタン"

    // News
    override val newsTitle = "プロジェクトのお知らせ"
    override val newsEmpty = "まだお知らせはありません..."
    override val newsFilterPlaceholder = "お知らせを絞り込む"
    override val newsAltNoAddress = "フィードのアドレスが未設定です"
    override val settingsSectionNews = "お知らせ"
    override val settingsAltNewsFeed = "代替フィード"
    override val settingsAltNewsFeedDesc =
        "ニュースウィジェットの第 2 チャンネル用の RSS または Atom のアドレス。行は元のページを開かず、テキストのみを取得します。"
    override val newsFilterClear = "絞り込みを解除"
    override val railCollapse = "パネルを折りたたむ"
    override val railExpand = "パネルを展開"
    override val windowMinimize = "最小化"
    override val windowMaximize = "最大化"
    override val windowRestore = "元に戻す"
    override val windowClose = "閉じる"
    override val crumbHome = "ホーム"
    override val crumbLoading = "読み込み中…"
    override val paginationPrev = "前のページ"
    override val paginationNext = "次のページ"

    // Server Detail
    override val serverDetailTitle = "サーバー情報"
    override val serverDetailNoImage = "画像なし"
    override val serverDetailNoImageHint = "banner.png"
    override val serverDetailMissingTitle = "情報がありません"
    override fun serverDetailMissingPath(file: String) = "次の場所に $file を作成してください:"

    // Server Settings
    override val serverSettingsSubtitle = "起動設定"
    override val serverSettingsSectionSystem = "システム"
    override val serverSettingsSectionMods = "Mod"
    override val serverSettingsRam = "メモリ"
    override fun serverSettingsRamValue(mb: Int) = "メモリ: $mb MB"
    override val serverSettingsJava = "Java バージョン"
    override fun serverSettingsJavaAuto(version: String) = "自動 ($version)"
    override val serverSettingsJavaHint = "空欄にすると同梱の Java を使用します"
    override val serverSettingsOpenFolder = "フォルダーを開く"
    override val serverSettingsReset = "クライアントをリセット"

    override val serverSettingsResetConfirmTitle = "このクライアントをリセットしますか?"
    override val serverSettingsResetConfirmBody = "このサーバーのクライアントについて、ダウンロード済みのファイルがすべて完全に削除されます。元に戻せません。"
    override val backgroundResetConfirmTitle = "背景をリセットしますか?"
    override val backgroundResetConfirmBody = "カスタム背景の設定がすべて既定値に戻ります。"
    override val logoutConfirmTitle = "ログアウトしますか?"
    override val logoutConfirmBody = "保存されたサインイン情報がこの端末から削除されます。再度ログインするには認証情報の入力が必要です。"

    override val serverSettingsNoMods = "任意 Mod はありません"
    override val serverSettingsPickJava = "Java を選択"

    // Update
    override val updateTitle = "更新があります"
    override val updateTitleCritical = "重要な更新"
    override val updateTitleMandatory = "必須の更新"
    override val updateCriticalBanner = "この更新には重要なセキュリティ修正が含まれています。"
    override val updateMandatoryBanner =
        "古いバージョンではサーバー側との互換性が失われました。この更新なしにランチャーを続けられません。"
    override fun updateMandatoryBannerWithReason(reason: String) =
        "上流のプロトコルによる要求: $reason"
    override val updateChangelog = "変更履歴の全文"
    override val updateHighlights = "新着情報"
    override val updateNoChangelog = "このリリースには説明がありません。"
    override val updateViewOnGitHub = "GitHub で見る"
    override val updateLater = "あとで"
    override val updateExit = "終了"
    override val updateDownload = "ダウンロードしてインストール"
    override val updateDownloadNow = "今すぐダウンロード"
    override val updateDownloading = "ダウンロード中..."
    override val updateInstall = "インストールして再起動"
    override val updateRetry = "再試行"
    override val updateErrorTitle = "ダウンロードエラー"
    override val updateErrorUnknown = "不明なエラー"
    override val updateScheduleFailed = "更新を予約できませんでした"
    override fun updateVersion(version: String) = "バージョン ${version}"
    override val updateDetails = "詳細"

    // Desktop entry install (Advanced)
    override val updateManagerInstallDesktop = ".desktop エントリをインストール"
    override val updateManagerDesktopDone = "デスクトップエントリをインストールしました"

    // Console
    override val consoleTitle = "デバッグコンソール"
    override val consoleEmptyHint = "静かです。パックを起動するとログがここに流れます。"
    override fun consoleHeaderCount(filtered: Int, total: Int) = "ゲームの出力 ($filtered/$total)"
    override val consoleCopyAll = "すべてコピー"
    override val consoleClear = "クリア"
    override val consoleWrap = "折り返し"
    override val consoleSaveToFile = "ファイルに保存"
    override val consoleSearchPlaceholder = "検索…"
    override val consoleCopied = "コピーしました"
    override val consoleCommandPlaceholder = "ゲームへのコマンド (Enter、↑↓ で履歴、Esc)"
    override val consoleMenuCopyLine = "行をコピー"
    override val consoleMenuCopySelection = "選択範囲をコピー"
    override val consoleSelectAll = "すべて選択"
    override val consoleSettingsLabel = "コンソール設定"
    override val consoleShowGutter = "重要度の帯を表示"
    override val consoleHideGutter = "重要度の帯を隠す"
    override val consoleShowTimestamps = "タイムスタンプを表示"
    override val consoleHideTimestamps = "タイムスタンプを隠す"
    override val consoleStatusFollow = "追従"
    override val consoleStatusPaused = "一時停止"
    override fun consoleStatusLines(filtered: Int, total: Int) = "行: $filtered/${total}"
    override fun consoleStatusLinesWithHistory(filtered: Int, total: Int, history: Int) =
        "行: $filtered/$total  履歴に +$history"
    override fun consoleStatusFiltered(warn: Int, error: Int) = "警告 $warn  エラー ${error}"
    override fun consoleStatusMatch(current: Int, total: Int) = "一致 $current/${total}"

    // Tray
    override val trayConsole = "コンソールを開く"
    override val trayExit = "終了"

    // Settings: Diagnostics
    override val settingsSectionDiagnostics = "診断"
    override val settingsOpenLogs = "ログフォルダーを開く"
    override val settingsOpenCrashReports = "クラッシュレポート"
    override val settingsCreateDiagnosticBundle = "診断バンドルを作成"
    override val settingsDiagnosticBundleHint = "匿名化したログ、クラッシュレポート、操作履歴、システム情報を 1 つの ZIP にまとめてサポートに渡せます。"
    override val settingsReportOnGithub = "バンドルを添えて GitHub に報告"

    override val reportDescribeHeading = "説明"
    override val reportCrashHint = "ランチャーがクラッシュしたとき、何をしていましたか?"
    override val reportBundleHint = "問題の内容を書いてください。"
    override val reportLanguageNudge = "可能であれば英語で書いてください。"
    override val reportBundleCreated =
        $$"診断バンドル `$bundle` をランチャーのデータディレクトリに作成しました。完全なパスはクリップボードに入っています。"
    override val reportBundleAttach = "**送信前に ZIP をこのウィンドウにドラッグしてください** (GitHub はドラッグ＆ドロップに対応しています)。"

    // File Manager
    override fun fileDownloading(n: Int) = "更新をダウンロード中 ($n ファイル)..."

    // --- Settings: Offline Mode ---
    override val settingsOfflineMode = "オフラインモード"
    override val settingsOfflineModeDesc = "認証せずに起動します。ファイルは同期されません。"

    // --- Launcher States: Offline ---
    override val stateOfflineSkipAuth = "オフラインモード — 認証を省略しました"
    override val stateOfflineSkipSync = "オフラインモード — ファイル同期を省略し、ローカルのファイルを使用します"
    override fun stateForeignContentRemoved(count: Int, names: String) =
        "パックに含まれないファイルを $count 件削除しました: $names"
    override val stateContentChanged = "パックが変更されたため起動を中止しました。パックのファイルは変更しないでください。"
    override val stateOfflineNoClient = "クライアントのファイルが見つかりません。先にオンラインでダウンロードしてください。"
    override val stateOfflineNoManifest = "このサーバーのマニフェストがキャッシュにありません。オフラインで起動する前に、一度オンラインでログインしてください。"

    // --- Server Settings: Extended ---
    override val serverSettingsJvmArgs = "JVM 引数"
    override val serverSettingsJvmArgsHint = "-XX:+UseZGC -Dfoo=bar"
    override val serverSettingsJvmBuildArgs = "引数を組み立てる"
    override val serverSettingsResolution = "ウィンドウサイズ"
    override val serverSettingsWidth = "幅"
    override val serverSettingsHeight = "高さ"
    override val serverSettingsFullscreen = "フルスクリーン"
    override val serverSettingsAutoConnect = "サーバーに自動接続"

    // --- Server Settings: Icon Upload ---
    override val serverSettingsPickIcon = "サーバーアイコンを選択"

    // =========================================================================
    // RAM Selector
    // =========================================================================
    override val ramCustomInputLabel = "任意の値:"
    override fun ramSystemHint(systemRam: String, recommended: String) =
        "システム: $systemRam • 推奨の上限: $recommended"
    override fun ramAutoLabel(resolved: String) = "自動 · 約 ${resolved}"

    // =========================================================================
    // Mod cards
    // =========================================================================
    override fun modConflictWarning(ids: String) = "次と競合します: ${ids}"
    override fun modIncompatibleHint(ids: String) = "次と互換性がありません: ${ids}"

    // =========================================================================
    // Server grid
    // =========================================================================
    override val serversFavorites = "★ お気に入り"

    // =========================================================================
    // Custom Background
    // =========================================================================
    override val backgroundTitle = "外観"
    override val backgroundSubtitle = "ランチャーの壁紙、テーマ、配色"
    override val backgroundEnable = "有効にする"
    override val backgroundSectionImage = "画像または動画"
    override val backgroundPickFile = "背景にする画像か動画を選んでください"
    override val backgroundPickButton = "ファイルを選択"
    override val backgroundCancelOptimize = "動画の準備を中止"
    override val backgroundSectionScale = "拡大縮小"
    override val backgroundScaleCover = "全体を覆う"
    override val backgroundScaleContain = "収める"
    override val backgroundScaleStretch = "引き伸ばす"
    override val backgroundScaleOriginal = "原寸"
    override val backgroundScaleTile = "敷き詰める"
    override val backgroundSectionPosition = "位置"
    override val backgroundAlignX = "水平"
    override val backgroundAlignY = "垂直"
    override val backgroundSectionEffects = "効果"
    override val backgroundBlur = "ぼかし"
    override val backgroundDarken = "暗くする"
    override val backgroundOpacity = "不透明度"
    override val backgroundSaturation = "彩度"
    override val backgroundParallax = "視差"
    override val backgroundVignette = "ビネット"
    override val backgroundAnimationSpeed = "アニメーション速度"
    override val backgroundSectionTint = "色味"
    override val backgroundTintNone = "なし"
    override val backgroundTintNavy = "濃紺"
    override val backgroundTintViolet = "紫"
    override val backgroundTintEmerald = "エメラルド"
    override val backgroundTintBordeaux = "ボルドー"
    override val backgroundTintSteel = "スチール"
    override val backgroundTintIntensity = "強さ"
    override val backgroundReset = "既定値に戻す"
    override val backgroundPreview = "プレビュー"
    override val backgroundPreviewServer = "サーバーの例"
    override val settingsBackground = "カスタム背景"
    override val settingsBackgroundSub = "写真や GIF をランチャーの壁紙にします"

    // =========================================================================
    // About Screen
    // =========================================================================
    override val aboutTitle = "このアプリについて"
    override fun aboutDescription(branding: String) = "$branding の非公式ランチャー"
    override val locale: Locale = Locale.JAPANESE
    // The SI-style abbreviations are what Japanese interfaces use as well.
    override val byteUnits = listOf("B", "KB", "MB", "GB", "TB")
    override fun aboutBuildDate(date: String) = "ビルド日: ${date}"
    override val aboutRenderer = "レンダラー"
    override val aboutSectionCreator = "制作"
    override val aboutSectionTechnologies = "使用技術"
    override val aboutSectionLicense = "ライセンス"
    override val aboutLicenseText = "GPLv3 — 自由なオープンソースソフトウェア"
    override val aboutSectionUpdates = "更新"
    override val aboutCurrentVersion = "現在のバージョン"
    override val aboutCheckUpdates = "更新を確認"
    override val aboutChecking = "確認中..."
    override fun aboutUpdateAvailable(version: String) = "バージョン $version があります"
    override val aboutCriticalUpdate = "重要な更新"
    override val aboutSectionSystem = "システム"
    override val aboutOs = "OS"
    override val aboutSectionLinks = "リンク"
    override val aboutLinkGithub = "GitHub"
    override val aboutLinkBugReport = "不具合を報告"
    override val aboutLinkReleases = "リリース"
    override val settingsSectionAbout = "このアプリについて"

    // Tech stack descriptions
    override val techKotlinDesc = "主要言語"
    override val techComposeDesc = "UI フレームワーク"
    override val techKtorDesc = "HTTP クライアント"
    override val techKoinDesc = "依存性注入"
    override val techSkiaDesc = "グラフィックスレンダラー"
    override val techCoilDesc = "画像読み込み"

    // --- Spawn Reset ---
    override val spawnResetButton = "スポーンに戻る"
    override val spawnResetLoading = "リセット中..."
    override val spawnResetSuccess = "完了しました。再接続すると反映されます"
    override val spawnResetError = "サーバーエラー"

    // --- Tray ---
    override val trayStatusIdle = "待機中"
    override val trayStatusRunning = "ゲーム実行中"
    override val trayShow = "ランチャーを表示"
    override val trayHintTitle = "Nexira はまだ動いています"
    override val trayHintBody = "ウィンドウはトレイに隠れています。トレイのアイコンをクリックすると戻ります。"
    override val trayHintShow = "ウィンドウを表示"

    // --- Settings: Advanced (updates, launch, data directory) ---
    override val settingsSectionUpdates = "更新"
    override val settingsSectionLaunch = "起動"
    override val settingsPreReleases = "プレリリース版の更新"
    override val settingsPreReleasesDesc = "安定版に昇格する前のベータ版を受け取ります。"
    override val settingsMandatoryUpdates = "必須の更新"
    override val settingsMandatoryUpdatesDesc = "上流のプロトコルが壊れた場合に、重要な更新を入れるまで起動を止めます。既定はオフです。この下限は自分の起動も止めうるため、従うかどうかは意識して選ぶ設定です。"
    override val settingsAutoSyncAllPacks = "起動時に SmartyCraft クライアントを自動同期"
    override val settingsAutoSyncAllPacksDesc = "ランチャーの起動時に、導入済みの SmartyCraft クライアントを背景で再同期します。二段階認証を使っている場合はログインしません。ログインするとコードで解除したセッションが無効になるためです。したがって、以前の手動サインインがキャッシュしたマニフェストだけを使い、それがないサーバーは飛ばします。SmartyCraft 経路は 2.5.0 で廃止予定で、不具合も修正しません。ミラーのパックが推奨経路です。背景で通信量を消費します。"
    override val settingsAutoUpdatePacks = "導入済みインスタンスを自動更新"
    override val settingsAutoUpdatePacksDesc = "導入済みのパックを最新ビルドに保ちます。安全な更新は背景で適用し、Minecraft やローダーが変わる場合は下の方針に従います。手動で更新したい場合はオフにしてください。"
    override val settingsAmberPolicy = "ビルドで Minecraft かローダーが変わるとき"
    override val settingsAmberPolicyDesc = "保留中のビルドを、導入済みのものと Minecraft のバージョン、ローダーの系統、ローダーのバージョンで比べます。ローダーのバージョンだけが新しい場合はそのまま再同期します。Minecraft のバージョンが違う場合やローダーの系統が違う場合は、ワールド、設定、Mod の状態が壊れることがあるため、この設定でどうするかを決めます。適用の前には復元ポイントを取り、現在のビルドを保つ選択をすれば通知も止まります。対象はミラーのインスタンスだけで、SmartyCraft クライアントはこの判定を受けません。"
    override val settingsAmberPolicyAsk = "都度たずねる"
    override val settingsAmberPolicyApply = "自動で適用"
    override val settingsAmberPolicyHold = "現在のビルドを保つ"
    override val settingsJvmBuilder = "JVM 引数のビジュアル作成"
    override val settingsJvmBuilderDesc = "サーバーごとの設定に「引数を組み立てる」ボタンを表示します。GC アルゴリズムを選び、ヒープ領域を調整し、AppCDS や JFR を有効にできます。フラグを覚える必要はありません。Aikar のレシピ、GTNH 級の重量 Mod 環境、巨大ヒープ向けの ZGC などの既製プリセットが揃っています。"
    override val settingsAdaptiveMemory = "適応メモリ"
    override val settingsAdaptiveMemoryDesc = "機械の性能から決めた自動の基準値に加えて、数回のセッションの実使用量から各インスタンスのヒープを調整します。特定のメモリ量を固定するとそのインスタンスは対象外になります。学習せず自動の基準値だけを使いたい場合はオフにしてください。"
    override val settingsMimicVersion = "擬装するランチャーのバージョン"
    override val settingsMimicVersionDesc = "ハンドシェイクと User-Agent で上流に送るバージョン文字列を固定します。空欄なら同梱の既定値を使います。上流が Nexira のリリース周期より速くバージョンを上げた場合にだけ設定してください。保存後、次のプロトコル呼び出しから有効になります。再起動は不要です。"
    override fun settingsMimicVersionPlaceholder(default: String) = "既定: ${default}"
    override fun dashboardAutoSyncProgress(serverName: String, current: Int, total: Int) =
        "$serverName を同期中 ($current/$total)"
    override fun dashboardAutoSyncBytes(readMB: Long, totalMB: Long) = "$readMB / $totalMB MB"
    override val widgetProgressTitle = "背景の処理"
    override val widgetProgressIdle = "いまダウンロード中のものはありません。"
    override fun widgetTabDefaultLabel(index: Int) = "タブ ${index}"

    // April Fools
    override fun aprilCloseTitle(escapes: Int) = when {
        escapes == 0 -> "ちょっと待ってください..."
        escapes < 3  -> "本当によろしいですか?"
        escapes < 6  -> "お願いします... 楽しかったのに"
        escapes < 8  -> "これはお互いに気まずい"
        else         -> "わかりました。あきらめます。"
    }

    override fun aprilCloseBody(escapes: Int) = when {
        escapes == 0 -> "ランチャーは今日とてもがんばりました。本当に見捨てるのですか?"
        escapes < 3  -> "必要なものは全部ここにあります。ボタンはただ... 緊張しているだけです。"
        escapes < 6  -> "逃げた回数: $escapes。ボタンも永遠には走れません。"
        escapes < 8  -> "たいへん粘り強い。ボタンは疲れてきました。もう少しです..."
        else         -> "あなたの勝ちです。とんでもなく粘り強い人間ですね。"
    }

    override val aprilCloseStay = "残る"
    override val aprilCloseClose = "閉じる"
    override val aprilCloseSurrender = "閉じる (ついに)"
    override val aprilCloseHideTray = "トレイに隠す"
    override fun aprilCloseEscapeCount(current: Int, max: Int) =
        "閉じるボタンは $current / $max 回逃げました"

    // --- 2FA (TOTP) — #159 ---
    override val auth2faTitle = "二段階認証"
    override val auth2faPrompt = "認証アプリの 6 桁のコードを入力してサインインを完了してください。"
    override val auth2faPlaceholder = "000000"
    override val auth2faSubmit = "確認"
    override val auth2faCancel = "キャンセル"
    override val auth2faInvalid = "コードが違います。もう一度お試しください。"
    override val auth2faExpired = "二段階認証のセッションが期限切れです。もう一度サインインしてください。"

    override val auth2faUnsupportedTitle = "残念ながら、ここでは二段階認証を利用できません"
    override val auth2faUnsupportedBody = "申し訳ありません。ここで二段階認証を十分に支えることができません。私たちのプロトコルは Smartycraft のものと差があり、二段階認証のログイン自体は通っても、その後にゲーム側がエラーを返してしまいます。ウェブサイトのアカウント設定で二段階認証を無効にしてください。"
    override val auth2faUnsupportedDismiss = "了解"

    // --- SSL Warning ---
    override val sslWarningTitle = "サーバー証明書の期限切れ"
    override val sslWarningBody = "サーバーの SSL 証明書が期限切れです。接続が安全でない可能性があり、サーバーの身元を確認できません。自己責任で続行しますか?"
    override val sslWarningConnectAnyway = "このまま接続"
    override val sslWarningCancel = "キャンセル"
    override val sslWarningTrustPrompt = "このホストを信頼する期間:"
    override val sslWarningTrustHour = "1 時間"
    override val sslWarningTrust30Days = "30 日"
    override val sslWarningTrustAlways = "常に"

    override val settingsSectionNetwork = "ネットワーク"
    override val sslBypassListTitle = "有効な SSL 例外"
    override val sslBypassNoEntries = "有効な例外はありません"
    override val sslBypassRevoke = "取り消す"
    override fun sslBypassExpiresAt(formatted: String) = "期限: ${formatted}"

    override val settingsSectionSmarty = "Smarty サーバー"
    override val settingsOpenSmrtHelperTitle = "代替の smrt ネットワークヘルパーを使う"
    override val settingsOpenSmrtHelperDesc = "Smarty サーバーで、上流の Smarty Mod を私たちのオープンソースのヘルパーに置き換えます。ネットワーク機能は同じで、監視の仕組みはありません。そのゲームバージョン向けの代替がない場合は、元の Mod を動かすのではなく起動を中止します。"
    override val settingsStrictModCheckTitle = "Mod の厳密な検証"
    override val settingsStrictModCheckDesc = "同期のあと、サーバーが要求していないものを mods フォルダーからすべて削除します。導入は綺麗に保たれますが、自分で追加した Mod も消えます。"
    override val settingsNetworkAgentTitle = "ネットワーク支援エージェントを使う"
    override val settingsNetworkAgentDesc = "ゲームの起動時にログイン先を SmartyCraft に向けます。対象はゲーム内の参加処理とスキンの確認です。SmartyCraft が改変したログインライブラリを差し込まなくても参加時の認証が通り、スキンも読み込まれます。SmartyCraft のサーバーに参加するには必要です。"
    override val settingsSmartyAuthLibTitle = "SmartyCraft のログインライブラリを使う"
    override val settingsSmartyAuthLibDesc = "従来の方式です。SmartyCraft のクライアントから改変済みのログインライブラリを取り出し、元のものの代わりにパックへ置きます。上のネットワークエージェントに置き換えられ、予備として残しています。ファイルを取得できない場合は起動を中止します。既定はオフです。"

    override val settingsSectionDataDir = "データディレクトリ"
    override val settingsDataDirCurrent = "現在のパス:"
    override val settingsDataDirMove = "移動..."
    override val settingsDataDirPickerTitle = "Nexira のデータの新しい場所を選択"
    override val settingsDataDirConfirmTitle = "データディレクトリを移動しますか?"
    override fun settingsDataDirConfirmBody(source: String, target: String) =
        "Nexira はデータを移動します:\n移動元: $source\n移動先: $target\n\n移動は次回のランチャー起動時に適用されます。"
    override val settingsDataDirRestartRequired = "再起動が必要です — 次回の起動時に移動を適用します"
    override val settingsDataDirQuitNow = "今すぐ終了"
    override val settingsDataDirErrorSamePath = "すでに現在のディレクトリです。別のフォルダーを選んでください"
    override val settingsDataDirErrorNotEmpty = "移動先のフォルダーが空ではありません。空のフォルダーを選ぶか、中身を削除してください"
    override fun settingsDataDirErrorPickerFailed(reason: String) =
        "フォルダーの選択画面を開けませんでした: $reason"

    // ── JVM Args Builder ────────────────────────────────────────────────
    override val jvmTitle = "JVM 引数ビルダー"
    override val jvmSubtitle = "プリセットを選ぶか、手でフラグを組み立てます。結果は jvmArgs に入ります。"
    override val jvmPresetsHeader = "プリセット"
    override val jvmTabGc = "GC"
    override val jvmTabTuning = "G1 / Z / Shenandoah"
    override val jvmTabCds = "AppCDS"
    override val jvmTabJit = "JIT"
    override val jvmTabPerf = "性能"
    override val jvmTabJfr = "JFR"
    override val jvmTabCustom = "自由入力"
    override val jvmCancel = "キャンセル"
    override val jvmApply = "jvmArgs に適用"
    override fun jvmPreviewFlagsCount(n: Int) = "プレビュー (フラグ $n 個)"

    override val jvmGcHeader = "ガベージコレクター"
    override val jvmGcG1Hint = "Mod 入り Minecraft 向けの推奨。ヒープ 4〜32 GB。"
    override val jvmGcZHint = "停止時間はミリ秒未満。Java 17 以降、ヒープ 16 GB 以上。Java 21 以降では世代別。"
    override val jvmGcShenandoahHint = "OpenJDK / Liberica の低停止時間の並行 GC。Java 17 以降。"
    override val jvmGcParallelHint = "スループット優先。停止時間が長くなります。選ぶ理由はほとんどありません。"
    override val jvmGcSerialHint = "単一スレッド。1 GB 未満の小さなヒープ専用。"

    override val jvmG1Header = "G1GC の調整"
    override val jvmG1MaxPauseMillisHint = "目標の最大停止時間。小さくすると回収の回数が増えます。"
    override val jvmG1RegionSizeHint = "リージョンのサイズ (MB)。大きいほどリージョン数とメタデータが減ります。"
    override val jvmG1NewSizePercentHint = "ヒープに対する若い世代の最小割合 (%)。Aikar は 30。"
    override val jvmG1MaxNewSizePercentHint = "ヒープに対する若い世代の最大割合 (%)。Aikar は 40。"
    override val jvmG1IhopHint = "混合 GC を始める時点。Aikar は 15 (早め)、標準は 45。"
    override val jvmG1ParallelRefProcHint = "参照を並列で処理します。マルチコアなら得しかありません。"
    override val jvmG1PerfDisableSharedMemHint = "/tmp/hsperfdata を作りません。VisualVM は使えなくなりますが、ディスクは綺麗になります。"

    override val jvmZHeader = "ZGC の調整"
    override val jvmZGenerationalHint = "Java 21 以降のみ。ヒープを若い世代と古い世代に分けます。世代別でないものより明確に優れます。"

    override val jvmShenandoahHeader = "Shenandoah のヒューリスティック"
    override val jvmShenandoahAdaptiveHint = "既定。停止時間とスループットの釣り合いを取ります。"
    override val jvmShenandoahStaticHint = "固定のしきい値で回収を始めます。"
    override val jvmShenandoahCompactHint = "積極的に圧縮します。メモリの回収に優れます。"
    override val jvmShenandoahAggressiveHint = "常時回収します。スループットの代償が大きいです。"

    override fun jvmTuningNotApplicable(gcName: String) =
        "$gcName に調整できる項目はありません。GC タブで G1、Z、Shenandoah のいずれかに切り替えてください。"

    override val jvmCdsHeader = "アプリケーションクラスデータ共有"
    override val jvmCdsIntro = "読み込んだクラスのメタデータを起動をまたいでキャッシュします。200 以上の Mod のパックなら、初回以降のコールドスタートごとに 1〜3 秒短縮できます。"
    override val jvmCdsModeDisabledLabel = "無効"
    override val jvmCdsModeDisabledHint = "CDS を使いません。既定です。"
    override val jvmCdsModeAutoLabel = "自動アーカイブ (Java 19 以降)"
    override val jvmCdsModeAutoHint = "終了時に JVM がアーカイブを自動管理します。パスの指定は不要です。"
    override val jvmCdsModeArchiveLabel = "終了時にアーカイブ"
    override val jvmCdsModeArchiveHint = "終了時に指定したパスへアーカイブを書き出します。"
    override val jvmCdsModeUseLabel = "既存のアーカイブを使う"
    override val jvmCdsModeUseHint = "指定したパスから作成済みのアーカイブを読み込みます。"
    override val jvmCdsArchivePathLabel = "アーカイブのパス"

    override val jvmJitHeader = "JIT コンパイラ"
    override val jvmJitTieredHint = "オン: インタープリタ、C1、C2 の順に暖機します (既定)。オフ: C2 のみで、起動が遅くなります。"
    override val jvmJitCodeCacheHint = "JIT が生成したコードのキャッシュサイズ。JVM の既定は 240。Mod 入り Minecraft では 512 以上が有効なことがあります。"

    override val jvmPerfHeader = "性能と OS 寄りのフラグ"
    override val jvmPerfAlwaysPreTouchHint = "起動時にヒープの全ページに触れます。起動は遅くなりますが、動作中の揺れが減ります。"
    override val jvmPerfDisableExplicitGcHint = "System.gc() を無効化します。古い Mod が濫用することがあります。ほぼ常に得です。"
    override val jvmPerfUseLargePagesHint = "sysctl で hugepages を事前に確保しておく必要があります。設定できれば 2〜5% ほど速くなります。"
    override val jvmPerfTransparentHugePagesHint = "UseLargePages より手軽です。デフラグ中に遅延の山が出ます。一長一短です。"
    override val jvmPerfNumaHint = "NUMA を意識した割り当て。複数ソケットの機械でのみ有効です。"
    override val jvmPerfHeapDumpHint = "メモリ不足のときにヒープダンプを書き出します。原因究明に不可欠です。"
    override val jvmPerfExitOnOomHint = "メモリ不足のとき、粘らずに終了します。ゲームが半死の状態で残るのを防ぎます。"

    override val jvmJfrHeader = "Java Flight Recorder"
    override val jvmJfrIntro = "JVM の内部 (割り当て、GC、スレッド、ロック) を記録します。できた .jfr は JDK Mission Control か IntelliJ で開いて解析します。"
    override val jvmJfrEnableLabel = "JFR の記録を有効にする"
    override val jvmJfrEnableHint = "既定の設定でおよそ 1% の負荷。profile 設定では約 5% で、メソッド単位まで記録します。"
    override val jvmJfrDurationLabel = "記録時間 (分)"
    override val jvmJfrSettingsHeader = "設定プリセット"
    override val jvmJfrSettingsDefaultHint = "負荷が小さく、通常のプレイに向きます。"
    override val jvmJfrSettingsProfileHint = "メソッド単位のプロファイリング。負荷は約 5%。"
    override val jvmJfrOutputPathLabel = "出力する .jfr のパス (任意)"

    override val jvmCustomHeader = "自由入力の受け渡し"
    override val jvmCustomIntro = "追加のフラグをそのまま末尾に付けます。一度きりの実験や、まだ UI に出していないベンダー固有の設定に使ってください。空白区切りです。"
    override val jvmCustomLabel = "追加の引数"

    // --- Data dir migration UI ---
    override val migrationWelcome = "Nexira へようこそ"
    override val migrationDescription = "Aura は Nexira になりました。ランチャーを始める前に、既存のデータを新しい場所へ複製する必要があります。古いフォルダーは控えとしてそのまま残ります。すべて問題なく動いたら手動で削除してください。"
    override val migrationFromHeader = "移動元"
    override val migrationToHeader = "移動先"
    override fun migrationSize(megabytes: Int, files: Int) =
        "$megabytes MB、$files ファイル"
    override val migrationStart = "今すぐ移行"
    override val migrationInProgress = "Nexira へ移行しています"
    override fun migrationCurrentFile(file: String) = "$file をコピー中"
    override fun migrationProgressBytes(doneMb: Int, totalMb: Int) = "$doneMb MB / $totalMb MB"
    override val migrationCompletedTitle = "移行が完了しました"
    override val migrationCompletedBody = "移行したデータを使うには Nexira を再起動してください。"
    override val migrationFailedTitle = "移行に失敗しました"
    override fun migrationFailedBody(error: String) = "一部のファイルをコピーできませんでした: ${error}"
    override val migrationRetry = "再試行"
    override val migrationQuit = "Nexira を終了"

    override val placeholderNotImplemented = "まだ実装されていません..."
    override val placeholderHint = "この画面は Atelier の作業が入るまでの予約です。"

    override val navLibrary = "ライブラリ"
    override val navBrowse = "探す"

    override val settingsHomeViewTitle = "ホームの表示"
    override val settingsHomeViewSub = "既定はモダンなホームです。従来のダッシュボードも切り替えひとつで使えます。"
    override val settingsHomeViewClassic = "クラシック"
    override val settingsHomeViewNew = "モダン"

    // --- Left-rail selection style ---
    override val navSelectionTitle = "選択中の項目の見せ方"
    override val navSelectionSub = "左のレールで現在の項目をどう強調するか"
    override val navStylePill = "ピル"
    override val navStyleSquare = "四角"
    override val navStyleCircle = "円"
    override val navStyleBar = "バー"
    override val navStyleDot = "ドット"
    override val navStyleNone = "なし"
    override val navSelectionOutlineIcons = "未選択のアイコンを線画にする"
    override val navSelectionAccent = "強調色"
    override val navHoverHighlight = "ホバーで強調"

    override val settingsCategoryAppearance = "外観"
    override val settingsCategoryNetwork = "ネットワーク"
    override val settingsCategorySmarty = "Smarty"
    override val settingsCategoryAdvanced = "詳細"
    override val settingsCategoryDiagnostics = "診断"
    override val settingsCategoryConsole = "コンソール"
    override val consoleSecDisplay = "表示"
    override val consoleSecColors = "重要度の色"
    override val consoleSecFontSize = "文字サイズ"
    override val consoleSecWrap = "行を折り返す"
    override val consoleSecGutter = "重要度の帯"
    override val consoleSecTimestamps = "タイムスタンプ"
    override val consoleSecBuffer = "行バッファ"
    override val consoleSecColorInfo = "情報"
    override val consoleSecColorWarn = "警告"
    override val consoleSecColorError = "エラー"
    override val consoleSecColorAuto = "自動"
    override val consoleSecApplyNote = "変更は次にコンソールを開いたときに反映されます。"
    override val consoleSecHighlightRules = "強調のルール"
    override val consoleSecFilterRules = "絞り込みと非表示"
    override val consoleSecAddRule = "ルールを追加"
    override val consoleSecRulePattern = "パターン"
    override val consoleSecRegex = "正規表現"
    override val consoleSecBold = "太字"
    override val consoleSecRulesEmpty = "ルールはまだありません。"
    override val consoleSecArt = "空のコンソールの絵"
    override val consoleSecArtAdd = "絵を追加"
    override val consoleSecArtPaste = "ASCII か点字のアートを貼り付け"
    override val consoleSecArtEmpty = "カスタムの絵はまだありません。"

    override val profileCategoryAccount = "アカウント"
    override val profileCategorySignIn = "サインイン"
    override val profileCategorySecurity = "セキュリティ"
    override val profileForgetSavedSignIn = "保存したサインイン情報を消す"
    override val profileSecurityHint = "サインイン情報は自動ログインのためにこの端末に保存されています。"
    override val accountsTitle = "アカウント"
    override val accountRemove = "削除"
    override val accountFaceLabel = "表示名"
    override val accountFaceAuto = "自動"
    override val profileSignOutSmartycraft = "SmartyCraft からサインアウト"
    override val profileSignOutMicrosoft = "Microsoft からサインアウト"
    override val wardrobeTitle = "ワードローブ"
    override val wardrobeSignedOut = "スキンとマントを管理するにはサインインしてください。"
    override val wardrobeUpload = "アップロード"
    override val wardrobeApplySmartycraft = "適用 (SmartyCraft)"
    override val wardrobeEmpty = "ライブラリは空です。まずはスキンの PNG をアップロードしてください。"
    override val wardrobeSaved = "保存しました"
    override val wardrobeCapes = "マント"
    override val wardrobeApplyCape = "クランのマントを設定"
    override val wardrobeCapeClanHint = "マントはクラン共通です。設定できるのはクランのリーダーだけです。"
    override val wardrobeDefaults = "既定のスキン"
    override val wardrobeDeleteTitle = "ライブラリから削除しますか?"
    override val wardrobeDeleteBody = "ファイルはこの端末から削除されます。サーバーに適用済みのものはそのまま残ります。"
    override val wardrobePoseStand = "立つ"
    override val wardrobePoseWave = "手を振る"
    override val wardrobePoseSit = "座る"
    override val wardrobePoseFaceCover = "顔を隠す"
    override val wardrobePoseWalk = "歩く"

    override val backgroundLoopMode = "ループ"
    override val backgroundLoopUseCodec = "コーデックに任せる"
    override val backgroundLoopForever = "ずっと"
    override val backgroundLoopOnce = "一度だけ"

    override val customizationAccentClear = "上書きを解除"
    override val customizationSectionVisual = "見た目"
    override val customizationSectionColors = "色の上書き"
    override val customizationHexInvalid = "16 進数が不正です"
    override val themePickerAccentOverride = "アクセントの上書き (即時)"

    override val browseTitle = "探す"
    override val browseSearchPlaceholder = "パックを検索"
    override val browseImport = "ファイルを取り込む"
    override val libraryAddAction = "パックを追加"
    override val libraryNewLocalPack = "新しいローカルパック"
    override val libraryImportPack = "パックを取り込む"
    override val createPackName = "名前"
    override val createPackMc = "Minecraft のバージョン"
    override val createPackLoader = "ローダー"
    override val createPackLoaderVersion = "ローダーのバージョン (任意)"
    override val createPackConfirm = "作成"
    override val createPackCancel = "キャンセル"
    override val createPackShowSnapshots = "スナップショットを表示"
    override val createPackHideSnapshots = "スナップショットを隠す"
    override val browseEmptyTitle = "カタログが空です"
    override val browseEmptyMessage = "ミラーには繋がりますが、まだパックが公開されていません。しばらくしてからもう一度ご覧ください。"
    override val browseErrorTitle = "ミラーに接続できません"
    override val browseErrorMessage = "ミラーに接続できませんでした。接続を確認して再試行してください。"
    override val browseRetry = "再試行"
    override fun modrinthCategory(id: String) = when (id) {
        "adventure"    -> "冒険"
        "challenging"  -> "高難度"
        "combat"       -> "戦闘"
        "kitchen-sink" -> "全部入り"
        "lightweight"  -> "軽量"
        "magic"        -> "魔法"
        "multiplayer"  -> "マルチプレイ"
        "optimization" -> "最適化"
        "quests"       -> "クエスト"
        "technology"   -> "技術"
        else           -> humanizeCategory(id)
    }

    override val browseDetailTabDescription = "説明"
    override val browseDetailTabGallery = "ギャラリー"
    override val browseDetailNoDescription = "このパックについては何も書かれていません。"
    override val browseDetailErrorTitle = "パックを読み込めませんでした"
    override val browseDetailErrorMessage = "マニフェストを取得できませんでした。接続を確認して再試行してください。"
    override val browseDetailInstallButton = "インストール"
    override val contentInstallRetry = "再試行"
    override val contentInstallFailed = "ダウンロードが完了しませんでした"
    override fun browseDetailAbout(mods: Int, assets: Int) =
        "このパックには Mod $mods 個とアセット $assets 個が入っています。"

    override fun browseDetailInstallProgress(filename: String, current: Int, total: Int) =
        "$filename  ($current / $total)"
    override val browseDetailInstallFailedGeneric = "不明な理由でインストールに失敗しました。"

    override val fileBrowserNoRoot = "このインスタンスにはまだディスク上のファイルがありません。"
    override val fileBrowserPickAFile = "左でファイルを選ぶとプレビューします。"
    override val fileBrowserBinaryHint = "バイナリファイルです。プレビューできません。"
    override val fileBrowserOpenExternally = "外部で開く"
    override fun fileBrowserTextTruncated(maxKb: Long) =
        "プレビューは先頭 $maxKb KB までです。全体を見るには外部で開いてください。"
    override val fileBrowserEmptyFolder = "(空)"

    override val contentTabUnsupportedOrigin = "内容の表示は、今のところミラーで公開されたパックだけに対応しています。ほかの入手元は後の更新で揃えます。"
    override val contentAddFiles = "ファイルを追加"
    override val contentFindProjects = "プロジェクトを探す"
    override val contentSearchPlaceholder = "内容を検索..."
    override val contentEmpty = "見つかりません"
    override val contentFilterAll = "すべて"
    override val contentFilterMods = "Mod"
    override val contentFilterResourcePacks = "リソースパック"
    override val contentFilterShaderPacks = "シェーダー"
    override val contentFiltersTitle = "絞り込み"
    override val contentFiltersReset = "解除"
    override fun contentFiltersShown(shown: Int, total: Int) = "$total 件中 $shown 件を表示"
    override val contentFilterGroupCurated = "パックの内容"
    override val contentFilterGroupStatus = "状態"
    override val contentFilterGroupOwner = "追加者"
    override val contentFilterAny = "指定なし"
    override val contentFilterEnabled = "有効"
    override val contentFilterDisabled = "無効"
    override val contentFilterOwnerPack = "パック"
    override val contentFilterOwnerUser = "あなた"
    override val contentFilterOptionalOnly = "任意のみ"
    override val contentFilterOptionalOnlyHint = "パックがあなたに委ねている部分"
    override val contentDeleteTitle = "ファイルを削除しますか?"
    override val contentDeleteBody = "ファイルはディスクから完全に削除されます。"
    override fun contentBulkDeleteBody(count: Int) = "$count 件のファイルをディスクから完全に削除します。"
    override val selectionEnable = "有効にする"
    override val selectionDisable = "無効にする"
    override val selectionDelete = "削除"
    override fun selectionCount(count: Int) = "$count 件を選択中"
    override val selectionClear = "選択を解除"
    override fun selectionBlockedByPack(count: Int) = "このうち $count 件はパックのものです。管理するにはインスタンスを切り離してください。"
    override val contentActionDetails = "詳細"
    override val contentActionOpenPage = "ページを開く"
    override val contentDetailAuthors = "作者"
    override val contentDetailSize = "サイズ"
    override val contentTabFetchErrorTitle = "パックの内容を読み込めませんでした"
    override val contentTabFetchErrorGeneric = "ミラーのマニフェストを読み込めませんでした。"
    override val contentTabRetry = "再試行"
    override val modBrowserErrorTitle = "検索に失敗しました"
    override val modBrowserErrorMessage = "Modrinth に接続できませんでした。接続を確認して再試行してください。"
    override val contentTabRoleSection = "役割の枠"
    override fun contentTabOptionalSection(count: Int) = "任意の Mod ($count)"
    override fun contentTabIncompatibleWith(name: String) = "$name と互換性がありません"
    override fun contentTabModsSection(count: Int) = "Mod ($count)"
    override fun contentTabAssetsSection(count: Int) = "アセット ($count)"
    override val contentTabResolverIssuesTitle = "マニフェストに問題が見つかりました"
    // Japanese has one plural form, so the count selector the other locales
    // need does not appear anywhere in this file.
    override fun contentTabResolverMissing(count: Int) =
        "$count 件の依存関係が、このパックにない Mod を参照しています。"
    override fun contentTabResolverCycles(count: Int) =
        "依存関係の循環が $count 件見つかりました。パックの作者は requires の関係を見直してください。"
    override val contentTabRoleRecipeViewer = "レシピ閲覧"
    override val contentTabRoleMinimap = "ミニマップ"
    override val contentTabRoleBlockInfo = "ブロック情報"
    override val contentTabRolePerformance = "性能"
    override val contentTabRoleInventorySearch = "インベントリ検索"
    override fun contentTabRoleAltCount(count: Int) =
        if (count == 0) "選択肢は 1 つ" else "代替 $count 件"
    override val contentTabRoleAlternativesHeader = "このパック内の代替"
    override val contentTabModNoDescription = "マニフェストにはまだ説明がありません。"
    override fun contentTabModLicensePrefix(license: String) = "ライセンス: ${license}"
    override val contentTabModUrlLabel = "Mod のページ"
    override fun contentTabModSizeLabel(kb: Long) = "$kb KB"
    override fun contentTabModDependencies(count: Int) = "依存関係 ($count)"
    override fun contentTabModMissingCount(count: Int) = "$count 件が不足"
    override val contentTabDepOptional = "任意"
    override val contentTabDepMissing = "不足"
    override val contentTabModOptional = "任意"
    override fun contentTabLibrariesSection(count: Int) = "ライブラリ ($count)"
    override fun contentTabResourcePacksSection(count: Int) = "リソースパック ($count)"
    override fun contentTabShaderPacksSection(count: Int) = "シェーダーパック ($count)"
    override fun contentTabConfigsSection(count: Int) = "設定ファイル ($count)"
    override fun contentTabOtherAssetsSection(count: Int) = "その他のファイル ($count)"
    override fun contentTabAssetSizeLabel(kb: Long) = "$kb KB"
    override val contentTabAssetOptional = "任意"
    override val contentTabAssetNoDescription = "マニフェストにはまだ説明がありません。"

    override fun worldsTabLocalSection(count: Int) = "ローカルのワールド ($count)"
    override val worldsTabLocalEmpty = "保存されたワールドはまだありません。ゲーム内でシングルプレイのワールドを作るとここに出ます。"
    override fun worldsTabServersSection(count: Int) = "履歴のサーバー ($count)"
    override val worldsTabServersEmpty = "このインスタンスのマルチプレイ履歴にはまだサーバーがありません。"
    override val worldsTabErrorTitle = "ワールドを読み取れませんでした"
    override val worldsTabErrorMessage = "このインスタンスのセーブかサーバー一覧を読み取れませんでした。ファイルが壊れているか、読み取れない可能性があります。"
    override fun worldsTabLastPlayed(rel: String) = "最終プレイ: ${rel}"
    override val worldsTabServerHiddenLabel = "ゲーム内の一覧から隠されています"
    override val worldsTabGameSurvival = "サバイバル"
    override val worldsTabGameCreative = "クリエイティブ"
    override val worldsTabGameAdventure = "アドベンチャー"
    override val worldsTabGameSpectator = "スペクテイター"
    override val worldsTabGameUnknown = "不明なモード"
    override val worldsTabDimOverworld = "オーバーワールド"
    override val worldsTabDimNether = "ネザー"
    override val worldsTabDimEnd = "エンド"
    override val worldsTabDimOther = "その他"

    override val packDetailTabContent = "内容"
    override val packDetailTabFiles = "ファイル"
    override val packDetailTabWorlds = "ワールド"
    override val packDetailTabLogs = "ログ"
    override val packDetailTabSettings = "設定"
    override val packVersionSection = "バージョンと更新"
    override val packVersionInstalled = "導入済みのビルド"
    override val packVersionCheck = "確認"
    override val packVersionUpToDate = "最新のビルドです"
    override fun packVersionAvailable(version: String) = "ビルド $version があります"
    override val packVersionSafe = "安全な更新"
    override val packVersionNeedsCare = "Minecraft かローダーが変わります。先に控えを取ります"
    override val packVersionUpdateNow = "今すぐ更新"
    override val packVersionFollowLatest = "最新に追従"
    override val packVersionFollowLatestDesc = "このパックを最新のビルドへ自動更新します。"
    override fun packVersionLatestBuilt(version: String, publishedAt: String) = "最新のビルド: ${version}、公開 ${publishedAt}"
    override val packVersionSwitch = "切り替え"
    override val packVersionCurrentTag = "現在"
    override val packVersionUpdateBadge = "更新"
    override val packVersionRollbackBadge = "巻き戻し"
    override fun packVersionRolledBack(version: String) = "ミラーがパックを $version に巻き戻しました"
    override val packVersionSwitchNow = "切り替え"
    override val packVersionCheckFailed = "更新を確認できませんでした"

    override val versionPickerInstallTitle = "パックをインストール"
    override val versionPickerChangeTitle = "パックのバージョンを変更"
    override val versionPickerSearch = "バージョンを探す"
    override fun versionPickerCount(n: Int) = "$n 個のバージョン"
    override val versionPickerEmpty = "バージョンが選ばれていません"
    override val versionPickerNoChangelog = "このバージョンには変更履歴がありません"
    override val versionPickerWarning = "バージョンを変えるとパックのファイルが書き換わります。適用の前に復元ポイントを取ります。"
    override fun versionPickerInstall(version: String) = "$version をインストール"
    override fun versionPickerUpgrade(version: String) = "$version に更新"
    override fun versionPickerRollback(version: String) = "$version に巻き戻す"
    override fun versionPickerSwitch(version: String) = "$version に切り替える"

    override val packVersionsTitle = "パックのバージョン"
    override val packVersionsAllVersions = "すべてのバージョン"
    override val packVersionsLatestTag = "最新"
    override fun packVersionsRebuilds(n: Int) = "変更のない再ビルド +$n 件"
    override val packVersionsChannelRelease = "リリース"
    override val packVersionsChannelBeta = "ベータ"
    override val packVersionsChannelAlpha = "アルファ"
    override fun packVersionsCounts(mods: Int, assets: Int) =
        "Mod $mods 個、アセット $assets 個"
    override val packVersionsDiffVsPrevious = "前のビルドとの比較"
    override val packVersionsDiffVsInstalled = "導入済みとの比較"
    override val packVersionsIdentical = "ファイルの変更なし。名前だけ変えた再ビルドです"
    override val packVersionsFirstBuild = "このパックの最初のビルドで、比べる相手がありません"
    override val packVersionsNoDiffSource = "この入手元は、導入するまでビルドの内容を示しません"
    override fun packVersionsAdded(n: Int) = "追加 ($n)"
    override fun packVersionsUpdated(n: Int) = "更新 ($n)"
    override fun packVersionsRemoved(n: Int) = "削除 ($n)"
    override val packVersionsSectionMods = "Mod"
    override val packVersionsSectionAssets = "パックのファイル"
    override val packVersionsSectionPack = "設定項目"
    override val packVersionsNotes = "リリースノート"
    override val packVersionsSwitchTo = "このビルドに切り替える"
    override val packVersionsConfirmTitle = "バージョンを切り替えますか?"
    override fun packVersionsConfirmBody(from: String, to: String) =
        "インスタンスは $from から $to へ移ります。先に復元ポイントを取ります。"
    override fun packVersionsPlanCounts(add: Int, update: Int, remove: Int) = "変更: +$add, ~$update, -${remove}"
    override fun packVersionsConflicts(n: Int) =
        "あなたの変更と $n 件衝突します。パックのファイルは隣に .new として置かれます"
    override fun packVersionsApplying(current: Int, total: Int, name: String) = "適用中 $current/$total: ${name}"
    override fun packVersionsApplied(version: String) = "完了しました。現在のビルドは $version です"
    override fun packVersionsFailed(reason: String) = "失敗しました: ${reason}"
    override val packVersionsRetry = "再試行"
    override val packVersionsLoadError = "ミラーに接続できず、バージョン一覧を読み込めませんでした"

    override val packSettingsTitle = "パックの設定"
    override val packSettingsClose = "閉じる"
    override val packSettingsCategoryGeneral = "全般"
    override val packSettingsCategoryRuntime = "起動"
    override val packSettingsCategoryVersion = "バージョン"
    override val packSettingsCategoryContent = "内容"
    override val packSettingsCategoryData = "データ"
    override val packSettingsIdentity = "識別情報"
    override val packSettingsName = "名前"
    override val packSettingsNamePlaceholder = "パックの名前"
    override val packSettingsNotes = "メモ"
    override val packSettingsNotesPlaceholder = "自分用のメモ"
    override val packSettingsSource = "入手元"
    override fun packSettingsForkedFrom(name: String) = "$name から派生"
    override val packSettingsPackId = "パック ID"
    override val packSettingsMemory = "メモリ"
    override val packSettingsEnvironment = "実行環境"
    override val packSettingsJava = "Java"
    override fun packSettingsJavaManaged(major: Int) = "管理下 — Java ${major}"
    override val packSettingsJavaCustom = "Java のパスを指定"
    override val packSettingsJavaPathPlaceholder = "/path/to/bin/java"
    override val packSettingsJavaReset = "管理下のものを使う"
    override val packSettingsJvmArgs = "JVM 引数"
    override val packSettingsJvmArgsDefault = "既定"
    override val packSettingsJvmArgsEdit = "編集"
    override val packSettingsWindow = "ゲームのウィンドウ"
    override val packSettingsWindowOverride = "ウィンドウサイズを指定"
    override val packSettingsWindowOverrideDesc = "指定しない場合、クライアントが記憶したサイズを使います"
    override val packSettingsWidth = "幅"
    override val packSettingsHeight = "高さ"
    override val packSettingsFullscreen = "フルスクリーン"
    override val packSettingsOptional = "任意の内容"
    override val packSettingsOptionalNone = "このパックに選べる項目はありません"
    override val packContentPresenceClient = "クライアントのみ"
    override val packContentPresenceServer = "サーバーのみ"
    override val packContentPresenceBoth = "クライアントとサーバー"
    override val packContentPresenceCoremod = "コア Mod"
    override val packSettingsDependencies = "依存関係"
    override val packSettingsDependenciesNone = "不足はありません"
    override fun packSettingsMissing(name: String) = "不足: ${name}"
    override val packSettingsContentUnavailable = "マニフェストがないため内容の一覧を出せません"
    override val packSettingsContentLoading = "読み込み中"
    override val packSettingsStorage = "保存場所"
    override val packSettingsFolder = "パックのフォルダー"
    override val packSettingsOpenFolder = "開く"
    override val packSettingsSizeComputing = "サイズを計算中"
    override val packSettingsDetach = "ローカルに切り離す"
    override val packSettingsDetachDesc = "自分専用の複製になります。由来は残ります"
    override val packSettingsDetachAction = "切り離す"
    override val packSettingsRepair = "ファイルを検証して修復"
    override val packSettingsRepairDesc = "すべてのファイルを調べ、壊れているものだけを戻します"
    override val packSettingsRepairAction = "修復"

    override val packBusyRunningTitle = "このパックは実行中です"
    override val packBusyRunningBody = "いまファイルを変えると、ゲームが開いている Mod や設定を書き換えることになります。クラッシュしたり、ワールドが途中で保存されたりして、セッションはまず無事に終わりません。できればゲームを先に閉じてください。"
    override val packBusyRunningConfirm = "それでも実行"
    override fun packSettingsRepairDone(checked: Int, repaired: Int) =
        if (repaired == 0) "$checked 件を確認、すべて健全です" else "$checked 件を確認、$repaired 件を復元しました"
    override fun packSettingsRepairProgress(current: Int, total: Int, name: String) = "確認中 $current/$total: ${name}"
    override val packSettingsDangerZone = "取り扱い注意"
    override val packSettingsDelete = "パックを削除"
    override val packSettingsDeleteDesc = "インスタンスのファイルは完全に消えます"
    override val packVersionSnapshots = "復元ポイント"
    override val packVersionRestore = "復元"
    override val packVersionSnapshotsHint = "復元ポイントは自分の変更を保ちます。構造が変わる更新の前に自動で取られます"
    override val consoleSessionLive = "全体"
    override fun consoleSessionPickerLabel(current: String) = "ログ: ${current}"

    override val packDetailReadyTitle = "プレイ準備完了"
    override fun packDetailInstanceDirHint(dirName: String) = "インスタンスのフォルダー: instances/${dirName}"
    override val packDetailPlay = "プレイ"
    override val packDetailPlayLoginRequired = "プレイするにはサインイン"
    override val packPlayWait = "お待ちください"
    override val packPlayExit = "終了"
    override val packDetailNotFoundTitle = "インスタンスが見つかりません"
    override val packDetailNotFoundHint = "別のウィンドウで削除された可能性があります。"
    override val packDetailNotFoundBack = "ライブラリに戻る"

    // --- Notification subsystem ---
    override val notificationExpandHistory = "通知の履歴を開く"
    override val notificationCollapseHistory = "通知の履歴を閉じる"
    override val notificationDismiss = "通知を閉じる"
    override val notifHistoryEmpty = "まだ通知はありません"
    override val notifHistoryClear = "消去"
    override val notifDoNotDisturb = "通知を止める"
    override fun notifGroupCount(count: Int) = "×${count}"
    override fun notifCountTitle(count: Int) = "$count 件のメッセージ"
    override fun notificationShowMore(count: Int) = "ほか $count 件"
    override fun notificationAbsoluteTime(instant: java.time.Instant): String =
        japaneseNotificationTime.format(instant)

    override fun notifPackPreparing(packName: String) = "$packName を準備中"
    override fun notifPackStage(stage: String) = "段階: ${stage}"
    override fun notifPackSyncing(packName: String) = "$packName を同期中"
    override fun notifPackSyncBody(current: Int, total: Int, pctLabel: String) =
        "$current/$total ファイル、$pctLabel"
    override val notifPackSyncIndeterminate = "ダウンロード中..."
    override fun notifPackSyncPercent(pct: Int) = "$pct%"
    override fun notifPackRunning(packName: String) = "$packName は実行中です"
    override fun notifPackFailed(packName: String) = "$packName の起動に失敗しました"
    override fun notifPackSessionEnded(packName: String) = "$packName のセッションが終了しました"
    override fun notifInstallSyncing(packName: String) = "$packName をインストール中"
    override fun notifInstallDone(packName: String) = "$packName をインストールしました"
    override fun notifPackUpdatePending(packName: String, version: String) = "$packName: ビルド $version があります"
    override fun notifPackUpdated(packName: String, version: String) = "$packName を $version に更新しました"
    override fun notifPackUpdateFailed(packName: String) = "$packName: 更新に失敗しました"
    override val notifActionOpenVersions = "バージョンを開く"
    override fun notifInstallFailed(packName: String) = "$packName のインストールに失敗しました"
    override fun notifInstallCancelled(packName: String) = "$packName のインストールを中止しました"
    override val editorSurfOverlay = "浮動レイヤー"
    override val editorSurfShortOverlay = "浮動"

    override val activityPillExpand = "すべて表示"
    override fun activityPillMore(count: Int) = "+${count}"

    override val activityPillDismiss = "閉じる"
    override val activityPillCancel = "キャンセル"
    override val activityPillPause = "一時停止"
    override fun activityPillMeasure(done: Long, total: Long) = "$total 中 ${done}"

    override val notifActionCancel = "キャンセル"
    override val notifActionShowConsole = "コンソールを表示"
    override val notifActionStop = "停止"
    override val notifActionPlayOffline = "オフラインでプレイ"
    override fun notifReasonExitCode(code: Int) = "ゲームが終了コード $code で終了しました"
    override val notifReasonInternal = "内部エラー"
    override fun notifReasonInternalDetail(detail: String) = detail
    override val notifReasonAuthFail = "認証に失敗しました"
    override fun notifReasonAuthFailDetail(detail: String) = detail
    override val notifReasonOfflineNoClient = "パックのファイルがディスクにありません"
    override val notifReasonOfflineNoManifest = "キャッシュしたマニフェストがありません。一度オンラインで同期してください"
    override val notifReasonTwoFactorExpired = "認証情報を更新するため、もう一度サインインしてください"
    override val notifSessionStaleTitle = "セッションを更新できませんでした"
    override val notifSessionStaleRejected = "認証サーバーがサインインを拒否しました。ゲームは古いセッションで起動しますが、サーバーへの参加はまず失敗します。もう一度サインインしてください。"
    override val notifSessionStaleUnreachable = "認証サーバーに接続できませんでした。ゲームは古いセッションで起動します。サーバーに断られた場合は、しばらくしてからお試しください。"
    override val notifSessionStaleUnknown = "セッションを更新できませんでした。ゲームは古いセッションで起動します。"
    override val notifSessionStaleNoPassword = "保存されたパスワードがないため、セッションは更新されません。期限が切れるとサーバーに入れなくなります。もう一度サインインしてください。"
    override fun notifForeignContentRemovedTitle(count: Int) = "パックにないファイルを $count 件削除しました"
    override val notifInstanceUnverifiedTitle = "パックの内容が未検証です"
    override val notifInstanceUnverifiedBody = "このパックが何で構成されているかを示すものがディスクにないため、ゲームはサインインなしで起動し、サーバーに参加できません。パックを同期して (設定 → ファイルを検証して修復) もう一度起動してください。"
    override fun notifReasonMissingAuthProvider(providerKey: String) = when (providerKey) {
        PackAuthRequirement.SmartyCraft.PROVIDER_KEY -> "このパックをプレイするには SmartyCraft にサインインしてください"
        else                                         -> "このパックをプレイするには '$providerKey' でサインインしてください"
    }

    override val notifTimeNow = "いま"
    override fun notifTimeSeconds(seconds: Long) = "${seconds}秒"
    override fun notifTimeMinutes(minutes: Long) = "${minutes}分"
    override fun notifTimeHours(hours: Long) = "${hours}時間"
    override fun notifTimeDays(days: Long) = "${days}日"

    // --- Home (new) + launch tiles ---
    override val homeRecentTitle = "あなたのパック"
    override val homeNoPacksTitle = "パックがまだありません"
    override val homeNoPacksBody = "「探す」から何か入れると、ここに並びます。"
    override val browseOpen = "「探す」を開く"
    override val homeQuickContinue = "続ける"
    override val homeQuickStart = "起動"
    override val homeQuickButton = "プレイ"
    override fun homeHeroPlaytime(hours: Long) = "プレイ時間 $hours 時間"
    override val launchTileReady = "起動"
    override val launchTileBlocked = "まだプレイできません"

    // --- Library widgets ---
    override val libraryEmptyTitle = "いまは空です"
    override val libraryEmptyBody = "「探す」からパックを入れると、ここに並びます。"
    override val libraryHeaderTitle = "ライブラリ"
    override val libraryHeaderSubtitle = "導入済みのパック"

    // --- Customization widget labels ---

    // --- Layout editor: common actions ---
    override val editorClose = "閉じる"
    override val editorEnterLayout = "レイアウトを編集"
    override val editorCancel = "キャンセル"
    override val editorDelete = "削除"
    override val editorReset = "初期化"
    override val editorUnsupportedWidget = "対応していないウィジェット"
    override val editorResetAll = "すべて初期化"
    override val editorToFront = "最前面へ"
    override val editorToBack = "最背面へ"
    override val widgetLabels: Map<String, String> = mapOf(
        "widget.about.credits" to "クレジットと技術",
        "widget.about.credits.title" to "見出し (クレジット)",
        "widget.about.links.card" to "リンク",
        "widget.about.links.card.title" to "見出し",
        "widget.about.logo" to "ロゴとバージョン",
        "widget.about.logo.title" to "見出し",
        "widget.about.logo.showVersion" to "バージョンを表示",
        "widget.about.logo.showBuildDate" to "ビルド日を表示",
        "widget.about.logo.showTagline" to "キャッチコピーを表示",
        "widget.about.system.card" to "システム",
        "widget.about.system.card.title" to "見出し",
        "widget.about.update.panel" to "更新",
        "widget.about.update.panel.title" to "見出し",
        "widget.appshell.region.center" to "主要な内容",
        "widget.appshell.region.collapsed" to "折りたたみ",
        "widget.appshell.region.swipeToCollapse" to "スワイプで折りたたむ",
        "widget.appshell.region.opacityPct" to "不透明度、%",
        "widget.appshell.region.blurDp" to "ぼかし",
        "widget.appshell.region.left" to "左レール",
        "widget.appshell.region.top" to "タイトルバー",
        "widget.appshell.region.body" to "主要領域",
        "widget.appshell.topbar.breadcrumb" to "パンくず",
        "widget.appshell.topbar.heightDp" to "高さ",
        "widget.appshell.topbar.cornerStyle" to "角の形",
        "widget.appshell.topbar.groupStyle" to "まとめ方",
        "widget.appshell.topbar.opacityPct" to "不透明度、%",
        "widget.appshell.topbar.blurDp" to "ぼかし",
        "widget.appshell.topbar.controls" to "ウィンドウ操作",
        "widget.appshell.region.right" to "右のパネル",
        "widget.appshell.region.showDivider" to "区切り線",
        "widget.appshell.region.widthDp" to "幅 (0 は可変)",
        "widget.appshell.rightrail.compactnews" to "お知らせ",
        "widget.appshell.rightrail.compactnews.maxItems" to "最大件数 (0 はすべて)",
        "widget.appshell.rightrail.compactnews.showTitle" to "見出しを表示",
        "widget.appshell.rightrail.compactnews.imageSource" to "画像の取得元",
        "widget.appshell.rightrail.compactnews.channel" to "チャンネル",
        "widget.bg.enable.toggle" to "背景のオンオフ",
        "widget.bg.fx.animspeed" to "アニメーション速度",
        "widget.bg.fx.blur" to "ぼかし",
        "widget.bg.fx.darken" to "暗くする",
        "widget.bg.fx.opacity" to "不透明度",
        "widget.bg.fx.parallax" to "視差",
        "widget.bg.fx.saturation" to "彩度",
        "widget.bg.fx.vignette" to "ビネット",
        "widget.bg.image.picker" to "背景の画像",
        "widget.bg.loop.mode" to "再生のループ",
        "widget.bg.position.x" to "位置 X",
        "widget.bg.position.y" to "位置 Y",
        "widget.bg.preview" to "プレビュー",
        "widget.bg.reset" to "背景を初期化",
        "widget.bg.scale.mode" to "拡大縮小",
        "widget.bg.tint" to "色味",
        "widget.container.group" to "グループ",
        "widget.checklist" to "チェックリスト",
        "widget.checklist.add" to "項目を追加...",
        "widget.checklist.empty" to "項目がありません",
        "widget.checklist.hideCompleted" to "完了を隠す",
        "widget.checklist.title" to "タイトル",
        "widget.container.tabs" to "タブ",
        "widget.container.tabs.label1" to "タブ 1",
        "widget.container.tabs.label2" to "タブ 2",
        "widget.container.tabs.label3" to "タブ 3",
        "widget.container.tabs.tabCount" to "タブ数",
        "widget.home.classic.content" to "クラシックのダッシュボード",
        "widget.home.new.clock" to "時計",
        "widget.home.new.clock.accent" to "強調色",
        "widget.home.new.clock.faceSize" to "文字盤の大きさ",
        "widget.home.new.clock.format24h" to "24 時間表記",
        "widget.home.new.clock.mode" to "表示方式",
        "widget.home.new.clock.showSeconds" to "秒",
        "widget.home.new.clock.title" to "見出し",
        "widget.home.new.hero" to "パックの大判カード",
        "widget.home.new.hero.height" to "高さ",
        "widget.home.new.hero.showMeta" to "付随情報",
        "widget.home.new.launchbutton" to "起動ボタン",
        "widget.home.new.launchbutton.label" to "ラベル",
        "widget.home.new.music" to "音楽プレーヤー",
        "widget.home.new.music.title" to "見出し",
        "widget.home.new.playback.mini" to "ミニプレーヤー",
        "widget.home.new.player.timeline" to "プレイヤー: カード全体が進行バー",
        "widget.home.new.player.timeline.fill" to "再生済みの塗り",
        "widget.home.new.player.readout" to "プレイヤー: 時刻表示",
        "widget.home.new.player.readout.showTotal" to "全体の長さを表示",
        "widget.home.new.player.seeded" to "プレイヤー: ジャケットの色",
        "widget.home.new.player.seeded.showAlbum" to "アルバムを表示",
        "widget.home.new.player.seeded.tint" to "ジャケットの混色",
        "widget.home.new.player.cover" to "ジャケット付きプレーヤー",
        "widget.home.new.player.cover.showAlbum" to "アルバムを表示",
        "widget.home.new.progress" to "背景の処理",
        "widget.home.new.progress.idleText" to "待機中の文",
        "widget.home.new.progress.title" to "見出し",
        "widget.home.new.quicklaunch" to "クイック起動",
        "widget.home.new.quicklaunch.buttonLabel" to "ボタンのラベル",
        "widget.home.new.recent" to "パックのタイル",
        "widget.home.new.recent.maxTiles" to "タイルの数",
        "widget.home.new.recent.title" to "見出し",
        "widget.home.new.spacer" to "余白",
        "widget.home.new.spacer.height" to "高さ",
        "widget.home.new.video" to "動画プレーヤー",
        "widget.home.new.video.url" to "動画の URL",
        "widget.home.new.welcome" to "ようこその見出し",
        "widget.home.new.welcome.customGreeting" to "あいさつ文を指定",
        "widget.home.new.welcome.showSubtitle" to "副題を表示",
        "widget.library.body" to "ライブラリ本体",
        "widget.library.body.emptyText" to "空のときの文",
        "widget.library.body.emptyTitle" to "空のときの見出し",
        "widget.library.header" to "ライブラリの見出し",
        "widget.library.header.subtitle" to "副題",
        "widget.library.header.title" to "見出し",
        "widget.library.header.show" to "見出しを表示",
        "widget.nav.entry" to "ナビ項目",
        "widget.notes.scratch" to "メモ",
        "widget.notes.scratch.placeholder" to "何か書いてください...",
        "widget.notes.scratch.title" to "タイトル",
        "widget.notifications.history" to "通知の履歴",
        "widget.activity.pill" to "処理状況",
        "widget.activity.pill.progress" to "進み具合",
        "widget.activity.pill.anchor" to "位置",
        "widget.activity.pill.heightDp" to "高さ",
        "widget.activity.pill.showActions" to "操作を表示",
        "widget.notifications.history.expandUp" to "上に展開",
        "widget.notifications.history.clock12h" to "12 時間表記 (午前/午後)",
        "widget.notifications.history.verticalTime" to "時刻を縦積み",
        "widget.profile.account.section" to "SmartyCraft",
        "widget.profile.signin" to "Microsoft",
        "widget.profile.nav" to "プロフィールのナビ",
        "widget.profile.skin.section" to "スキン",
        "widget.profile.skin.section.previewHeight" to "プレビューの高さ",
        "widget.server.details.banner" to "サーバーのバナー",
        "widget.server.details.banner.cornerRadius" to "角の丸み",
        "widget.server.details.description" to "サーバーの説明",
        "widget.server.details.tagbar" to "サーバーのタグ",
        "widget.server.details.title" to "サーバーの名前",
        "widget.theme.picker.grid" to "テーマの一覧",
        "widget.theme.picker.preview" to "テーマのプレビュー",
    )
    override val recoverySafeModeTitle = "インターフェースを復旧できません"
    override val recoverySafeModeBody = "インターフェースが連続して落ちました。クラッシュレポートをディスクに保存しました。ランチャーを再起動してください。"
    override val recoverySafeModeQuit = "終了"

    override val recoveryTitle = "復旧モード"
    override val recoveryBody = "モジュールを無効にするか、壊れた状態を初期化してから続けてください。変更はランチャーの再起動時に反映されます。"
    override val recoveryModulesHeading = "モジュールを無効にする"
    override val recoveryModuleTray = "システムトレイ"
    override val recoveryModuleNotify = "通知"
    override val recoveryModuleSkinema = "映像の背景"
    override val recoveryModuleKeyring = "システムのキーリング"
    override val recoveryResetsHeading = "初期化"
    override val recoveryResetLayout = "ウィジェットのレイアウト"
    override val recoveryResetCustomization = "外観"
    override val recoveryResetWidgetState = "ウィジェットの内容"
    override val recoveryResetSettings = "設定"
    override fun recoveryResetConfirmTitle(name: String) = "$name を初期化しますか?"
    override val recoveryResetConfirm = "初期化"
    override val recoveryResetLayoutBody = "すべてのウィジェットが同梱のレイアウトに戻ります。自分で追加したウィジェットと、その配置は失われます。"
    override val recoveryResetCustomizationBody = "テーマ、壁紙、コンソールの設定が既定値に戻ります。"
    override val recoveryResetWidgetStateBody = "ウィジェットに書き込んだ内容を削除します。メモ、チェックリスト、そのほかウィジェットが預かっていたものすべてです。控えはどこにもありません。"
    override val recoveryResetSettingsBody = "すべての設定が新規導入時の状態に戻ります。ここで無効にしたモジュールは無効のままです。"
    override val recoveryContinue = "通常の起動に進む"
    override val recoveryRelaunchFailed = "自動で再起動できませんでした。ランチャーを開き直してください。"
    override val recoveryRestartInApp = "復旧モードで再起動"
    override val thresholdStageFiles = "ファイルを確認"
    override val thresholdStageNetwork = "ネットワークの状態"
    override val thresholdStageMigration = "移行の確認"
    override val thresholdStageModules = "モジュールを起動"
    override val thresholdErrorTitle = "起動に失敗"
    override val thresholdOpenLogs = "ログフォルダーを開く"
    override val thresholdQuit = "終了"
    override val recoveryReloadedNotice = "エラーのあとインターフェースを再読み込みしました"
    override val editorSave = "保存"
    override val editorApply = "適用"
    override val editorExport = "書き出し"
    override val editorWidgets = "ウィジェット"

    // --- Layout editor: slot orientation ---
    override val editorSlotStack = "重ね"
    override val editorSlotRow = "横並び"
    override val editorSlotGrid = "グリッド"
    override val editorSlotCanvas = "キャンバス"
    override val editorSlotCubeGrid = "キューブグリッド"
    override val editorSlotLayoutMenuTitle = "レイアウト"
    override val editorSlotGridColumns = "列数"
    override val editorSlotGridColumnsDecrease = "列を減らす"
    override val editorSlotGridColumnsIncrease = "列を増やす"
    override val editorSlotLayoutHandle = "枠のレイアウト"

    // --- Layout editor: prop panel ---
    override val editorResetToDefault = "既定に戻す"
    override val editorBackingTitle = "下地"
    override val editorSurfaceNone = "このウィジェットは面を描きません。追加すると背後に面が入り、形を整えられます。"
    override val editorSurfaceAdd = "面を追加"
    override val editorSurfaceOwn = "このウィジェットは自分で面を描くため、ここに設定するものはありません。形は状態に応じて変わり、保存された記録では表せません。"
    override val editorSurfaceSettings = "設定"
    override val editorBackingGlass = "ガラスの不透明度"
    override val editorBackingCorner = "角"
    override val editorBackingPadding = "余白 (全方向)"
    override val editorBackingPaddingTop = "余白 上"
    override val editorBackingPaddingEnd = "余白 右"
    override val editorBackingPaddingBottom = "余白 下"
    override val editorBackingPaddingStart = "余白 左"
    override val editorBackingNoGlassHint = "ガラスがないと下地は見えません。角と余白はウィジェットには効きます。"
    override val editorSurfaceFill = "塗り"
    override val editorSurfaceOpacity = "不透明度"
    override val editorSurfaceBlur = "ぼかし"
    override val editorSurfaceBorder = "枠線"
    override val editorSurfaceShadow = "影"
    override val editorSurfaceFillHint = "空欄はテーマに従います。段の名前 (base、raised、floating、sunken) は配色に追従し、#RRGGBB や #AARRGGBB は追従しません。"
    override val editorSurfaceShapeKind = "形"
    override val editorSurfaceSmoothing = "なめらかさ"
    override val editorSurfaceCornerTopStart = "角 左上"
    override val editorSurfaceCornerTopEnd = "角 右上"
    override val editorSurfaceCornerBottomEnd = "角 右下"
    override val editorSurfaceCornerBottomStart = "角 左下"
    override val editorSurfaceBorderColor = "枠線の色"
    override val editorSurfaceBorderOpacity = "枠線の不透明度"
    override val editorSurfaceMore = "その他"
    override val editorSurfaceShapeKindHint = "空欄か round は下の角を使います。rect、circle、pill、star、polygon は無視します。"
    override val editorSurfaceShapePoints = "頂点の数"
    override val editorSurfaceShapeInnerRadius = "切り込みの深さ"
    override val editorSurfaceShapePointRounding = "頂点の丸み"

    // --- Layout editor: presets ---
    override val editorPresetsTitle = "プリセット"
    override val editorPresetsIntro = "レイアウト、テーマ、スタイルの記録です。いま保存して、いつでも読み込めます。"
    override val editorPresetNamePlaceholder = "プリセットの名前..."
    override fun editorPresetsSaved(count: Int) = "保存済み ($count)"
    override val editorPresetsEmpty = "空です。いまのレイアウトを最初のプリセットとして保存してください。"

    // --- Layout editor: palette ---
    override val editorPaletteHide = "パレットを隠す"
    override val editorPaletteHint = "枠にドラッグ"
    override val editorPaletteEmpty = "ウィジェットの登録が空です (ビルドの問題)。"
    override val editorPaletteSearch = "ウィジェットを検索…"
    override val editorPaletteNoMatch = "一致するものがありません"

    // --- Layout editor: empty slot + chrome ---
    override val editorDragWidgetHere = "ここにウィジェットをドラッグ"
    override val editorDragReorder = "ドラッグで並べ替え"
    override val editorConfigure = "設定"
    override val editorForceRemove = "強制的に削除"
    override val editorForceRemoveTitle = "ウィジェットを強制的に削除しますか?"
    override fun editorForceRemoveBody(name: String) =
        "「$name」は削除できない指定になっています。こうしたウィジェットは、操作の導線が失われないよう普段はそのまま残ります。ここでは不要だと判断したなら、いま削除できます。うまくいかなくなった場合は、面のチップの隣のメニューから面を既定に戻してください。"

    // --- Layout editor: host (reset / pill / fab) ---
    override val editorResetSurfaceTitle = "面を既定に戻しますか?"
    override fun editorResetSurfaceBody(name: String) =
        "「$name」は同梱の既定レイアウトのウィジェット配置に戻ります。この面での変更 (追加したウィジェット、並べ替え、削除) はすべて失われます。ほかの面はそのままです。"
    override val editorPreview = "プレビュー"
    override val editorPreviewHidden = "非表示"
    override val editorPaletteToggleHide = "隠す"
    override val editorEscHint = "Esc で終了"
    override val editorFabEdit = "レイアウトを編集"
    override val editorFabDone = "編集を終える"

    // --- Layout editor: surface short names ---
    override val editorSurfShortHome = "ホーム"
    override val editorSurfShortLibrary = "ライブラリ"
    override val editorSurfShortLeftRail = "左レール"
    override val editorSurfShortRightRail = "右レール"
    override val editorSurfShortAbout = "情報"
    override val editorSurfShortBg = "背景"
    override val editorSurfShortProfile = "プロフィール"
    override val editorSurfShortServer = "サーバー"
    override val editorSurfShortTheme = "テーマ"
    override val editorSurfShortShell = "シェル"
    override val editorSurfShortTopBar = "上部"
    override val editorSurfShortBody = "本体"

    // --- Layout editor: surface long names ---
    override val editorSurfHomeClassic = "ホーム (クラシック)"
    override val editorSurfHomeNew = "ホーム (新)"
    override val editorSurfLibrary = "ライブラリ"
    override val editorSurfLeftRail = "横のパネル"
    override val editorSurfRightRail = "右のパネル"
    override val editorSurfAbout = "このアプリについて"
    override val editorSurfBg = "背景の設定"
    override val editorSurfProfile = "プロフィール"
    override val editorSurfServer = "サーバーの詳細"
    override val editorSurfTheme = "テーマの選択"
    override val editorSurfShell = "アプリの外枠"
    override val editorSurfTopBar = "上部バー"
    override val editorSurfBody = "主要領域"

    // --- Music player widgets ---
    override val musicPlayerTitle = "音楽プレーヤー"
    override val audioPlay = "再生"
    override val audioPause = "一時停止"
    override val audioStop = "停止"
    override val audioOpenFile = "ファイルを開く"
    override val audioPickTrack = "曲を選ぶ"
    override val audioVolume = "音量"
    override val audioRepeat = "繰り返し"
    override val audioRepeatOff = "オフ"
    override val audioRepeatOne = "1 曲"
    override val audioRepeatQueue = "キュー全体"
    override val audioPlaybackOptions = "再生"
    override val audioSkipNext = "次の曲"
    override val audioSkipPrevious = "前の曲"
    override val audioNoFile = "ファイルなし"
    override val audioStatusReady = "準備完了"
    override val audioStatusPlaying = "再生中"
    override val audioStatusPaused = "一時停止中"
    override val audioFormatHint = "MP3、FLAC、OGG、WAV など。"
    override val audioNoPlayerHere = "このレイアウトにプレーヤーがありません"
    override val audioAddMusicPlayer = "音楽プレーヤーを追加"
    override val audioErrorUnsupported = "対応していない、または読めないファイルです。"
    override val audioErrorOpenFailed = "ファイルを開けませんでした"
    override val audioErrorDeviceBusy = "音声デバイスが使用中です"
    override val audioErrorPlaybackFailed = "再生に失敗しました"

    // --- Video player ---
    override val videoFullscreen = "フルスクリーン"
    override val videoExitFullscreen = "フルスクリーンを終了"
    override val videoMute = "消音"
    override val videoUnmute = "消音を解除"
    override val videoReplay = "もう一度再生"
    override val videoError = "この動画を再生できませんでした"
    override val videoLoading = "動画を読み込み中…"
    override val videoOpenInBrowser = "ブラウザーで開く"
    override val videoSkipBack = "10 秒戻る"
    override val videoSkipForward = "10 秒進む"
    override val videoWidgetEmpty = "ウィジェットの設定で動画の URL を指定してください"
    override val readOnlyDataTitle = "いま加えた変更は保存されません"
    override fun readOnlyDataBody(stores: String) =
        "新しいビルドのランチャーが書き込んでいます: $stores。読み取り専用で開いているため、このセッションでは書き戻せません。" +
            "変更はランチャーを閉じると失われます。編集するには更新してください。"
    override val readOnlyDataLibrary = "パックのライブラリ"
    override val readOnlyDataLayout = "レイアウト"
    override val videoFetchingTool = "ダウンローダーを取得中"
    override val videoResolvingPage = "ページを読み取り中"
    override val videoDownloading = "ダウンロード中"
    override val videoCancelDownload = "ダウンロードを中止"
    override val videoCancelled = "ダウンロードを中止しました"
    override val videoRetry = "もう一度試す"

    // --- Library pack card ---
    override val packCardPlay = "プレイ"
    override val packCardSettings = "設定"
    override val packCardMore = "その他"
    override val packCardDeleteTitle = "インスタンスを削除しますか?"
    override val packCardDeleteBody = "インスタンスとそのすべてのファイル (ワールド、設定、Mod) が完全に削除されます。元に戻せません。"
    override val packCardNeverPlayed = "未プレイ"
    override val packCardPlayedJustNow = "たったいま"
    override fun packCardPlayedMinutesAgo(n: Long) = "${n}分前"
    override fun packCardPlayedHoursAgo(n: Long) = "${n}時間前"
    override fun packCardPlayedDaysAgo(n: Long) = "${n}日前"
    override val packCardPlayedLongAgo = "かなり前"

    // --- Session chip + about logo a11y ---
    override val sessionsActiveTitle = "実行中のセッション"
    override val aboutLogoDesc = "アプリのロゴ"

}

/**
 * Built once rather than per notification: the English file caches its
 * formatters behind a private helper this file cannot reach, and the Russian one
 * rebuilds the formatter on every call.
 *
 * The pattern is the ordinary Japanese one, year and month and day each closed
 * by their own character, so 2026年9月8日 21:15:30.
 */
private val japaneseNotificationTime: DateTimeFormatter =
    DateTimeFormatter
        .ofPattern("yyyy年M月d日 HH:mm:ss", Locale.JAPANESE)
        .withZone(ZoneId.systemDefault())
