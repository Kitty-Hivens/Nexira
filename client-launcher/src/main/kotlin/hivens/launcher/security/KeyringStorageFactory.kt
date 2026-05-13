package hivens.launcher.security

import hivens.core.security.IKeyringStorage
import org.slf4j.LoggerFactory

/**
 * Picks an [IKeyringStorage] impl for the current platform, probing for
 * runtime availability. Returns [NoOpKeyringStorage] when no native store
 * is reachable — `CredentialsManager` then falls back to its AES-GCM file.
 *
 * Probing strategy: instantiate the candidate, call `isAvailable()`, accept
 * if true. The probe must NOT throw — `isAvailable()` is contractually
 * non-throwing, but we wrap in [runCatching] anyway because JNA library
 * loading inside the impl's constructor can throw `UnsatisfiedLinkError`
 * before `isAvailable()` is even reached.
 *
 * Selection is one-shot per JVM: once chosen, the same impl is used until
 * the launcher restarts. Re-probing on every call would mask "user logged
 * out of keyring mid-session" cases unhelpfully — by design, a mid-session
 * keyring outage just falls back to the file.
 */
object KeyringStorageFactory {
    private val log = LoggerFactory.getLogger(KeyringStorageFactory::class.java)

    fun system(): IKeyringStorage {
        val osName = System.getProperty("os.name", "").lowercase()
        val candidate: IKeyringStorage? = when {
            osName.contains("linux") -> tryProbe("LinuxLibsecret") { LinuxLibsecretKeyringStorage() }
            // Windows + macOS impls land in follow-up PRs (Vault sub-chunks).
            // Until then, those platforms get the file fallback via NoOp.
            else -> null
        }
        return when {
            candidate != null -> {
                log.info("Keyring storage: {}", candidate.javaClass.simpleName)
                candidate
            }
            else -> {
                log.info("Keyring storage: NoOp (no native store available on os={}, falling back to AES-GCM file)", osName)
                NoOpKeyringStorage
            }
        }
    }

    private inline fun tryProbe(label: String, factory: () -> IKeyringStorage): IKeyringStorage? {
        return runCatching {
            val impl = factory()
            if (impl.isAvailable()) impl else null
        }.onFailure {
            // Native lib missing (UnsatisfiedLinkError) or DBus daemon down.
            // Either way, factory falls back; log at INFO not WARN because
            // this is an expected outcome on headless CI / minimal Linux.
            log.info("Keyring probe {} unavailable: {}", label, it.message ?: it.javaClass.simpleName)
        }.getOrNull()
    }
}
