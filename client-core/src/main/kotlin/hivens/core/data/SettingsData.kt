package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Which source drives the dark/light choice. Exactly one is active:
 *
 * - [Manual] -- the user's own day/night toggle; [SettingsData.isDarkTheme] as set.
 * - [System] -- follow the OS colour scheme (XDG portal / registry / defaults).
 * - [Wallpaper] -- follow the wallpaper's average brightness.
 *
 * Flipping the day/night toggle while in [System]/[Wallpaper] drops back to
 * [Manual] -- an explicit choice always wins over an automatic source.
 */
@Serializable
enum class ThemeMode { Manual, System, Wallpaper }

/**
 * What the auto-updater does when a pending pack update is graded amber (a
 * Minecraft or loader change that could invalidate worlds/configs). Green
 * updates always apply automatically; this governs only the risky ones.
 *
 * - [Ask] -- do not auto-apply; surface it so the user applies it deliberately.
 * - [SnapshotThenApply] -- apply automatically (a snapshot is always taken first).
 * - [Hold] -- never auto-apply amber; leave the instance on its current build.
 */
@Serializable
enum class AmberUpdatePolicy { Ask, SnapshotThenApply, Hold }

/**
 * The theme mode a fresh session starts in. Migrates the pre-mode opt-in: a
 * settings file that enabled [SettingsData.themeFromWallpaper] before
 * [SettingsData.themeMode] existed decodes with the field's default ([ThemeMode.System]),
 * so the legacy flag promotes exactly that default to [ThemeMode.Wallpaper] -- a
 * non-default stored mode is an explicit choice and wins over the flag.
 */
fun resolveInitialThemeMode(s: SettingsData): ThemeMode =
    if (s.themeMode == ThemeMode.System && s.themeFromWallpaper) ThemeMode.Wallpaper else s.themeMode

/**
 * Folds the retired experimental master into the knobs it used to suppress.
 *
 * That master was read at several launch-path sites, so a user who switched it
 * off was switching off mandatory-update enforcement, the auto-update pass and
 * adaptive heap sizing -- whatever those knobs stored individually. Removing the
 * gate without this would turn them all back on at the next start, silently and
 * on someone who had deliberately turned them off.
 *
 * Applied once on load and cleared, so the fold cannot re-fire against knobs the
 * user re-enables afterwards.
 *
 * The JVM-args builder and the mimic-version override are deliberately not
 * folded: the gate only ever greyed out their rows, while the builder's own
 * gate and `SettingsRestoreHook` read the stored values directly. They were
 * live with the master off, so switching the master off never expressed an
 * intent to disable them.
 */
fun foldLegacyExperimentalGate(s: SettingsData): SettingsData =
    if (s.experimentalFeaturesEnabled) s else s.copy(
        experimentalFeaturesEnabled = true,
        mandatoryUpdatesEnabled     = false,
        autoUpdatePacks             = false,
        adaptiveMemoryEnabled       = false,
    )

/**
 * Wallpapers below this luminance drive the dark theme.
 *
 * The comparison is strict, so exactly mid-grey resolves to the light theme.
 * Nothing rides on which side takes the tie -- it is stated only because a
 * threshold nobody wrote down is a threshold somebody later flips by accident.
 */
const val WALLPAPER_DARK_THRESHOLD = 0.5f

/**
 * The dark flag an automatic theme source wants, or null when nothing should
 * change.
 *
 * Three sources write one boolean -- the manual toggle, the OS scheme, and the
 * wallpaper's brightness -- and each had its own effect deciding when to fire,
 * with the persist repeated alongside. Stated once here: the mode picks which
 * source is listened to at all, a source with nothing to say (no wallpaper
 * decoded yet, no readable OS scheme) says nothing, and a source that agrees
 * with the current value asks for no write.
 *
 * Returning null rather than the unchanged value is the point: every caller
 * both sets state and persists, and a settings file rewritten on every
 * wallpaper tick is a write per frame during a crossfade.
 */
fun darkThemeFor(
    mode: ThemeMode,
    current: Boolean,
    wallpaperLuminance: Float? = null,
    systemDark: Boolean? = null,
): Boolean? {
    val wanted = when (mode) {
        // The user said so; nothing automatic overrides that until they
        // choose another mode.
        ThemeMode.Manual -> null
        ThemeMode.System -> systemDark
        ThemeMode.Wallpaper -> wallpaperLuminance?.let { it < WALLPAPER_DARK_THRESHOLD }
    }
    return wanted?.takeIf { it != current }
}

@Serializable
data class SettingsData(
    val javaPath: String? = null,
    val isDarkTheme: Boolean = true,
    /**
     * Derive the colour palette from the wallpaper (Material You / Monet): the
     * dominant colour of the background seeds tinted tonal surfaces, so planes
     * differ by colour, not just lightness. On by default. Off -> the fixed
     * Celestia palette (and manual theme overrides) apply as before.
     */
    val paletteFromWallpaper: Boolean = true,
    /**
     * Legacy mirror of `themeMode == Wallpaper`, kept so a downgrade to a build
     * that predates [themeMode] still honours the wallpaper opt-in. New code
     * reads [themeMode] and only writes this field in step with it.
     */
    val themeFromWallpaper: Boolean = false,
    /**
     * Which source drives dark/light -- see [ThemeMode]. [isDarkTheme] stays the
     * resolved value the automatic sources write through, so everything downstream
     * (and older builds) keeps reading one boolean. Defaults to following the OS:
     * where the scheme is unreadable the automatic source just never fires, so a
     * fresh install falls back to [isDarkTheme]'s own default.
     */
    val themeMode: ThemeMode = ThemeMode.System,
    /**
     * Replace the OS title bar with the in-app top bar (undecorated window +
     * custom caption buttons / drag / resize). On by default. Escape hatch: if a
     * window manager -- or a future native-Wayland JVM, where client-side window
     * control differs from today's XWayland path -- misbehaves with the custom
     * chrome, turning this off restores the OS-decorated window. Applies at the
     * next launch, since `undecorated` is fixed when the window is created.
     */
    val useCustomChrome: Boolean = true,
    /**
     * Hide launcher window to tray after Play. Off by default -- most
     * users want the launcher visible after launch (switch servers,
     * open console). Opt-in for users who want out-of-sight behavior.
     */
    val closeAfterStart: Boolean = false,
    /** Whether a successful sign-in stores the account. Seeds the login panel's
     *  remember-me box and is written back when it is flipped. */
    val saveCredentials: Boolean = true,
    /** BCP-47 language tag: "ru", "en", "de". */
    val locale: String = "en",
    /** Offline mode: skip authentication, play with an offline identity. */
    val isOfflineMode: Boolean = false,
    /**
     * The offline-play name chosen via "Play offline". Drives the offline UUID
     * (vanilla OfflinePlayer:<name>) and lets a restart restore the offline
     * identity without re-typing. Null = none chosen yet; auto-login then falls
     * back to the last signed-in name.
     */
    val offlinePlayerName: String? = null,

    // ── Updates, launch and protocol ─────────────────────────────────────

    /**
     * Legacy master that used to gate the settings section these knobs lived in.
     * Read only by [foldLegacyExperimentalGate], which folds a stored `false`
     * into the knobs it actually suppressed and then clears itself; nothing else
     * consults it. Kept as a field so that fold has something to read on a file
     * written by an older build.
     */
    val experimentalFeaturesEnabled: Boolean = true,

    /**
     * Block startup when installed version < `mandatory_min_version` from
     * `meta/update-channel.json`. Opt-in (default OFF): the floor is an
     * emergency lever for a broken upstream protocol, but honouring it can
     * force an update and block the user's own startup, so enforcement is a
     * conscious choice rather than the default posture.
     */
    val mandatoryUpdatesEnabled: Boolean = false,

    /**
     * Update channel the user follows (Release / Beta / Alpha / Dev / Git).
     * Release/Beta/Alpha pick a GitHub release; Dev/Git build from source.
     * Defaults to [ReleaseChannel.Release]. The settings surface offers the
     * pre-releases toggle, which maps onto Release / Beta; the other channels
     * are reached by editing the file.
     */
    val updateChannel: ReleaseChannel = ReleaseChannel.Release,

    /**
     * Opt into nightly prereleases on top of pre-releases being enabled. Config-only,
     * no UI -- editing the file, or running a nightly build (which classifies as
     * [ReleaseChannel.Nightly] and enables this implicitly), is the opt-in. Nightlies
     * are raw bleeding-dev, deliberately not offered to the pre-releases-toggle audience.
     */
    val nightlyChannel: Boolean = false,

    /**
     * Auto-update installed mirror packs to the latest build in the background.
     * A green (safe re-sync) update applies silently; an amber (MC/loader change)
     * update follows [amberUpdatePolicy]. On by default -- a stale pack desyncs
     * from the live server. Per-instance opt-out is `PackInstance.followLatest`.
     */
    val autoUpdatePacks: Boolean = true,

    /**
     * How the unattended pass treats a pending update that changes Minecraft or the
     * loader family, as graded by `classifyCompat` against the installed manifest.
     * See [AmberUpdatePolicy].
     */
    val amberUpdatePolicy: AmberUpdatePolicy = AmberUpdatePolicy.Ask,

    /**
     * Reveals the visual JVM-args builder in the per-server constructor.
     * `InstanceProfile.jvmArgs` is free-text, which requires knowing
     * what `-XX:+UseG1GC -XX:MaxGCPauseMillis=200` means;
     * `JvmArgsBuilderDialog` presents a curated preset picker + GC tabs +
     * per-knob explanations, output still writes back to
     * `InstanceProfile.jvmArgs` unchanged. Off by default -- power-user
     * surface; the free-text field is enough for users who know what
     * they want.
     */
    val jvmBuilderEnabled: Boolean = false,

    /**
     * Adaptive memory: let the profiler agent size each instance's heap from its
     * observed live-set + peak instead of the static per-instance heap. On by
     * default, and the switch that governs EVERY instance -- this is the normal
     * path a heap is decided by, not an alternative one. An instance opts out
     * only by pinning a specific RAM value (`fixedMemory` on `InstanceProfile` /
     * `InstanceRuntime`); turning this off forces every instance back to the
     * machine-derived baseline.
     */
    val adaptiveMemoryEnabled: Boolean = true,

    /**
     * Override for the version string sent in the dashboard handshake,
     * the User-Agent header, and `-Dminecraft.launcher.version`. Null /
     * blank uses `Protocol.DEFAULT_MIMIC_LAUNCHER_VERSION`. Persisted
     * (rather than relying on the `-Dsmrt.mimic.version` CLI flag) so
     * the override survives launcher restart; aimed at reacting to an
     * upstream version pin faster than the Nexira release cycle.
     */
    val mimicVersionOverride: String? = null,

    /**
     * "Do not disturb": mute the live top-right notification popups. Events are
     * still recorded to the history log (the notification-history widget keeps
     * filling) and still auto-dismiss; only the toast rendering is suppressed.
     * Off by default; toggled from the history widget's mute button.
     */
    val doNotDisturb: Boolean = false,

    // ── Audio ────────────────────────────────────────────────────────────

    /**
     * Playback loudness, 0..1, as the player widgets last left it.
     *
     * Stored because loudness belongs to the listener rather than to the track:
     * it used to be full on every launch, so someone who keeps it at a tenth got
     * the first second of the next session at ten times what they set. Written
     * once a drag settles rather than on every frame of it -- see AudioPlayer.
     */
    val audioVolume: Float = 1.0f,

    /**
     * The queue as it stood, and which entry of it was loaded.
     *
     * Kept for the same reason the loudness is: what somebody was listening to is
     * theirs rather than the session's, and a player that comes back empty every
     * launch asks them to find the folder again before it is a player at all.
     *
     * Paths as text, because a queue is a list of files on this machine and
     * nothing here needs to be portable. An entry that has since been moved or
     * deleted is dropped on the way back in rather than kept as a row that cannot
     * play.
     */
    val audioQueue: List<String> = emptyList(),

    /** Which entry of [audioQueue] was loaded, or -1 for none. */
    val audioQueueIndex: Int = -1,

    // ── News ───────────────────────────────────────────────────────

    /**
     * RSS or Atom address for the news widget's alternate channel.
     *
     * Null by default and nothing reads it until it is set: no default feed
     * ships, and the launcher makes no request for this channel while the field
     * is empty. Only http and https are honoured -- the field is hand-editable,
     * and a local-file address in it would turn a news rail into a disk reader.
     *
     * The channel it feeds is deliberately narrow: its rows do not open at their
     * source and only their text is fetched. See NewsChannelPolicy.
     */
    val altNewsFeedUrl: String? = null,

    // ── Smarty server controls ───────────────────────────────────────────

    /**
     * Attach the network-support `-javaagent` when launching an SC-bound pack.
     * The agent redirects the game's authlib endpoints to SmartyCraft at
     * class-load -- the in-game join and the skin/texture whitelist -- so the
     * join authenticates against SC and skins still load, WITHOUT shipping or
     * swapping SC's patched authlib jar. On by default: this is how an SC-bound
     * pack reaches the server. No effect on non-SC packs. Turning it off while
     * [useSmartycraftAuthLib] is also off leaves an SC join with the vanilla
     * authlib, which the server rejects.
     */
    val useNetworkAgent: Boolean = true,

    /**
     * Source SmartyCraft's own patched `authlib` from the SC client distribution
     * and swap it onto the pack's classpath instead of the vanilla one. The older
     * mechanism, superseded by [useNetworkAgent] and kept as an opt-in fallback;
     * off by default. No effect on non-SC packs. When on, the patched jar is
     * mandatory -- the launch is blocked if it cannot be sourced, since vanilla
     * authlib is a guaranteed rejection.
     */
    val useSmartycraftAuthLib: Boolean = false,

    // ── Onboarding state (not a user-facing toggle) ──────────────────────

    /**
     * True once the one-time "still running in the tray" OS notification has
     * been shown. The first time the launcher hides its window to the tray we
     * post a system notification (visible while the window is gone) so the user
     * doesn't think the app vanished; this flag suppresses it on every
     * subsequent hide. Internal onboarding state -- no Settings UI surfaces it.
     */
    val trayHintShown: Boolean = false,

    /**
     * Which signed-in account fronts the shell -- the provider id of the chosen
     * "face", or null for automatic licence-priority (the Microsoft account
     * before SmartyCraft). With several accounts active at once, this pins whose
     * name and skin the shell shows; the launch still routes per content.
     */
    val preferredFaceProvider: String? = null,

    // -- Boot recovery state (not a user-facing toggle) -------------------

    /**
     * Modules boot recovery has disabled -- [ModuleId] ids the launcher skips at
     * startup (tray, notify, skinema, keyring). NOT a normal settings toggle:
     * written only from the recovery surface, effective on the next boot. Stored
     * as stable string ids so an id a build does not recognise is ignored rather
     * than resetting the file. Empty = everything on.
     */
    val disabledModules: Set<String> = emptySet(),
)

/**
 * Drops the face choice when [providerKey] is the provider it names.
 *
 * The choice outlives the account that carried it otherwise: nothing shows the
 * setting once a single account is left, so a preference made months ago sits
 * unreachable on disk and re-decides the shell's face the moment that provider
 * is signed into again. A choice the user cannot see is not a choice they can
 * be held to.
 */
fun SettingsData.releasingFace(providerKey: String): SettingsData =
    if (preferredFaceProvider == providerKey) copy(preferredFaceProvider = null) else this
