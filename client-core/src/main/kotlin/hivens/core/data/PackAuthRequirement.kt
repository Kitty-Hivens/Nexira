package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Auth provider a pack needs the user to be signed in with before
 * the game process is spawned. Carried on
 * [CachedManifestSnapshot.authRequirement] -- null means the pack
 * launches without an auth precondition (vanilla, offline-only packs).
 *
 * The launcher uses this for two things on a Play click:
 *   1. Precondition gate -- if the user is not signed in with the
 *      required provider, the spawn is refused with a friendly
 *      surface ("this pack needs <provider>") instead of starting the
 *      game and failing later with a stale-token error.
 *   2. Session refresh -- when the provider IS available, the
 *      launcher re-authenticates right before spawn so the game's
 *      first server join sees a fresh token (a cold mod-load can
 *      take minutes; server-side sessions age out in the meantime).
 *
 * [SmartyCraft] is satisfiable today. [Microsoft] / [Both] route to a
 * Microsoft account whose provider lands in a later phase; until it is
 * registered the launcher treats a Microsoft requirement as advisory
 * (content still launches) rather than blocking. Existing serialized
 * snapshots without `authRequirement` stay valid (nullable, defaults null).
 */
@Serializable
sealed interface PackAuthRequirement {
    /**
     * The SmartyCraft game-server id this content joins, or null for content
     * that needs no SC join (Microsoft-only). Lets the SC-binding path read one
     * accessor across [SmartyCraft] and [Both] rather than matching each variant.
     */
    val scServerId: String?

    /**
     * Pack joins a SmartyCraft game server identified by [serverId].
     * The launcher re-runs `authService.login(player, pass, serverId)`
     * before spawn -- mirrors the SC server-list launch path.
     */
    @Serializable
    data class SmartyCraft(val serverId: String) : PackAuthRequirement {
        override val scServerId: String get() = serverId

        companion object {
            /**
             * Stable provider identifier shared by the launcher and
             * the UI. Carried on `LaunchError.MissingAuthProvider`;
             * each locale's `stateMissingAuthProvider(providerKey)`
             * and `notifReasonMissingAuthProvider(providerKey)` keys
             * the localized string off this constant. Lives here so
             * the three sites (launcher controller, UI rendering,
             * tests) reference one source of truth instead of
             * trading bare strings.
             */
            const val PROVIDER_KEY: String = "smartycraft"
        }
    }

    /**
     * Licensed play via a Microsoft account (vanilla, Modrinth, CurseForge).
     * No SmartyCraft join, so [scServerId] is null.
     */
    @Serializable
    data object Microsoft : PackAuthRequirement {
        override val scServerId: String? get() = null

        const val PROVIDER_KEY: String = "microsoft"
    }

    /**
     * Hivens content that needs both a Microsoft account and a SmartyCraft
     * join at [serverId] -- the SC half drives the same authlib/helper binding
     * as [SmartyCraft].
     */
    @Serializable
    data class Both(val serverId: String) : PackAuthRequirement {
        override val scServerId: String get() = serverId
    }
}
