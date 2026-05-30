package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Auth provider a pack needs the user to be signed in with before
 * the game process is spawned. Carried on
 * [CachedManifestSnapshot.authRequirement] -- null means the pack
 * launches without an auth precondition (vanilla, future offline-only
 * packs).
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
 * Today the launcher only knows [SmartyCraft]. Additional providers
 * (Mojang, Elyby, OneOf, ...) land alongside the auth SPI
 * extraction; existing serialized snapshots without `authRequirement`
 * stay valid (the field is nullable and defaults to null).
 */
@Serializable
sealed interface PackAuthRequirement {
    /**
     * Pack joins a SmartyCraft game server identified by [serverId].
     * The launcher re-runs `authService.login(player, pass, serverId)`
     * before spawn -- mirrors the SC server-list launch path.
     */
    @Serializable
    data class SmartyCraft(val serverId: String) : PackAuthRequirement {
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
}
