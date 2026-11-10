package dev.hivens.nexira

import android.content.Context
import android.util.Log
import dev.hivens.libvault.VaultConfig
import dev.hivens.libvault.android.AndroidVault
import hivens.auth.CredentialsManager
import hivens.auth.LazySecretVault
import hivens.core.data.SessionData
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Saves an account through the shared credential store onto the Android Keystore,
 * reads it back, and checks that the token never reached the file in the clear.
 * Nothing secret is logged: only whether the values match.
 */
object VaultProbe {

    private const val TAG = "NexiraProbe"

    fun run(context: Context) {
        val dir = File(context.filesDir, "vault-probe").apply { deleteRecursively(); mkdirs() }
        val vault = LazySecretVault {
            AndroidVault.open(context, VaultConfig(namespace = "dev.hivens.nexira.probe"))
        }
        Log.i(TAG, "vault tier=${vault.tier} backend=${vault.backend}")

        val store = CredentialsManager(
            workDir = dir.toPath(),
            json = Json { ignoreUnknownKeys = true },
            vault = vault,
            legacyProvider = { error("there is no older store on Android") },
        )
        val token = "probe-token-" + System.nanoTime()
        store.saveAccount(
            SessionData(playerName = "ProbePlayer", uuid = "00000000000000000000000000000001", uid = "42", accessToken = token, cachedPassword = "probe-password"),
            providerId = "smartycraft",
            makeActive = true,
        )
        val back = store.load()
        Log.i(TAG, "round trip: player=${back?.playerName} tokenMatches=${back?.accessToken == token} passwordMatches=${back?.cachedPassword == "probe-password"} uidMatches=${back?.uid == "42"}")

        val text = File(dir, "credentials.json").readText()
        Log.i(TAG, "credentials.json holds the token=${text.contains(token)} holds the password=${text.contains("probe-password")}")

        // A second store over the same directory and vault is what a restart looks like.
        val reopened = CredentialsManager(dir.toPath(), Json { ignoreUnknownKeys = true }, vault) { error("no legacy") }
        Log.i(TAG, "after reopen: tokenMatches=${reopened.load()?.accessToken == token}")
        vault.close()
    }
}
