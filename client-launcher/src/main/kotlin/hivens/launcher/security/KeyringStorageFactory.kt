package hivens.launcher.security

import hivens.core.security.IKeyringStorage
import org.slf4j.LoggerFactory

/**
 * Picks an [IKeyringStorage] impl for the current platform, probing
 * for runtime availability. Returns [NoOpKeyringStorage] when no
 * native store is reachable; `CredentialsManager` then falls back to
 * its AES-GCM file.
 *
 * Probing: instantiate the candidate, call `isAvailable()`, accept if
 * true. The probe MUST NOT throw -- `isAvailable()` is contractually
 * non-throwing, but JNA library loading inside the impl constructor
 * can throw `UnsatisfiedLinkError` before `isAvailable()` is reached,
 * so we wrap in [runCatching] anyway.
 *
 * Selection is one-shot per JVM: chosen impl stays until launcher
 * restart. Re-probing on every call would mask "user logged out of
 * keyring mid-session" cases unhelpfully -- a mid-session outage
 * falls back to the file by design.
 */
object KeyringStorageFactory {
    private val log = LoggerFactory.getLogger(KeyringStorageFactory::class.java)

    fun system(): IKeyringStorage {
        val osName = System.getProperty("os.name", "").lowercase()
        val candidate: IKeyringStorage? = when {
            // BSDs ship the same Secret Service / libsecret stack as
            // Linux desktops (FreeBSD ports: security/libsecret +
            // gnome-keyring; OpenBSD equivalent). Same JNI symbol
            // resolution flow on ELF, same DBus protocol. The "Linux"
            // name in the class is historical.
            osName.contains("linux") || osName.contains("bsd") ->
                tryProbe("LinuxLibsecret") { LinuxLibsecretKeyringStorage() }
            osName.contains("windows") ->
                tryProbe("WindowsCredentialManager") { WindowsCredentialManagerKeyringStorage() }
            osName.contains("mac") || osName.contains("darwin") ->
                tryProbe("MacOSKeychain") { MacOSKeychainStorage() }
            else -> null
        }
        return when {
            candidate != null -> {
                log.info("Keyring storage: {}", candidate.javaClass.simpleName)
                candidate
            }
            else -> {
                log.info("Keyring storage: NoOp (no native store on os={}, falling back to AES-GCM file)", osName)
                NoOpKeyringStorage
            }
        }
    }

    private inline fun tryProbe(label: String, factory: () -> IKeyringStorage): IKeyringStorage? {
        return runCatching {
            val impl = factory()
            if (impl.isAvailable()) impl else null
        }.onFailure {
            // Native lib missing (UnsatisfiedLinkError) or DBus daemon
            // down. INFO not WARN -- expected outcome on headless CI /
            // minimal Linux.
            log.info("Keyring probe {} unavailable: {}", label, it.message ?: it.javaClass.simpleName)
        }.getOrNull()
    }
}
