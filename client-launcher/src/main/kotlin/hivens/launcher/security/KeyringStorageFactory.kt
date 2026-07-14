package hivens.launcher.security

import hivens.core.security.IKeyringStorage
import org.slf4j.LoggerFactory
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

    /**
     * libsecret / Keychain / WinCred probes write+read a test secret -- milliseconds on a
     * healthy store. But a LOCKED Secret Service with no prompt agent (common on minimal
     * Wayland WMs: Hyprland / sway / i3) makes the synchronous native call block forever,
     * and system() is resolved on the AWT thread during first composition -- so an unbounded
     * probe freezes startup with no window ever appearing. Cap it: past the deadline the
     * backend counts as unavailable and CredentialsManager falls back to its AES-GCM file.
     */
    private const val PROBE_TIMEOUT_MS = 1500L

    private fun tryProbe(label: String, factory: () -> IKeyringStorage): IKeyringStorage? {
        return runCatching {
            probeWithTimeout(PROBE_TIMEOUT_MS) { factory().takeIf { it.isAvailable() } }
        }.onFailure {
            // Native lib missing (UnsatisfiedLinkError) or DBus daemon down. INFO not WARN
            // -- expected outcome on headless CI / minimal Linux.
            log.info("Keyring probe {} unavailable: {}", label, it.message ?: it.javaClass.simpleName)
        }.getOrNull()
    }

    /**
     * Runs [block] on a throwaway daemon thread, waiting at most [timeoutMs]. Returns the
     * result, or null if it does not finish in time -- the backstop against a native keyring
     * call that never returns. The stranded worker is a daemon (native FFI ignores interrupt
     * so it cannot be force-cancelled, but it dies with the JVM). Re-throws the block's own
     * exception so the caller's runCatching still sees missing-library failures.
     */
    internal fun <T> probeWithTimeout(timeoutMs: Long, block: () -> T): T? {
        val exec = Executors.newSingleThreadExecutor { r ->
            Thread(r, "keyring-probe").apply { isDaemon = true }
        }
        return try {
            exec.submit(Callable { block() }).get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            log.warn("Keyring probe exceeded {}ms -- backend likely locked with no prompter; using fallback", timeoutMs)
            null
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } finally {
            exec.shutdownNow()
        }
    }
}
