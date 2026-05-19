package hivens.launcher.security

import hivens.core.security.IKeyringStorage
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * Windows-side [IKeyringStorage] backed by **Windows Credential Manager**
 * (the system service that has shipped with Windows since Vista, 2007).
 * Implemented with **Project Panama** ([java.lang.foreign], JEP 454),
 * matching the Linux libsecret peer in [LinuxLibsecretKeyringStorage].
 *
 * Under the hood Credential Manager stores each entry encrypted via
 * **DPAPI** (Data Protection API). The encryption key is derived from
 * the user's Windows login session -- credentials are automatically
 * available while the user is logged in, and copying `credentials.json`
 * to another machine doesn't yield anything readable because DPAPI is
 * machine-bound (and TPM-bound where available).
 *
 * advapi32.dll API surface (4 sync calls, no async, no COM):
 *
 * ```c
 * BOOL CredWriteW (PCREDENTIALW Credential, DWORD Flags);
 * BOOL CredReadW  (LPCWSTR TargetName, DWORD Type, DWORD Flags, PCREDENTIALW *Credential);
 * BOOL CredDeleteW(LPCWSTR TargetName, DWORD Type, DWORD Flags);
 * void CredFree   (PVOID Buffer);
 * ```
 *
 * All strings are wide (UTF-16LE) with a `\0\0` terminator. The
 * CREDENTIAL struct is allocated by us for writes; for reads, Windows
 * allocates the result buffer and we must release it via [CredFree]
 * after copying out the bytes.
 *
 * `TargetName` carries our `service/account` tuple as a slash-joined
 * string -- Windows uses TargetName as the unique key, so encoding both
 * parts there is the conventional approach (same as Git Credential
 * Manager's `git:https://github.com` pattern).
 *
 * Persistence: [CRED_PERSIST_LOCAL_MACHINE] (2) -- survives reboots,
 * tied to this Windows user on this machine. Not roaming (which would
 * sync via Active Directory across machines -- undesirable for
 * launcher creds).
 */
internal class WindowsCredentialManagerKeyringStorage : IKeyringStorage {

    private val log = LoggerFactory.getLogger(WindowsCredentialManagerKeyringStorage::class.java)

    private companion object {
        /** CRED_TYPE_GENERIC from wincred.h -- non-domain credential. */
        const val CRED_TYPE_GENERIC: Int = 1

        /**
         * CRED_PERSIST_LOCAL_MACHINE from wincred.h -- credential survives
         * reboots, tied to this user on this machine. Don't use
         * CRED_PERSIST_SESSION (gone on logoff) or CRED_PERSIST_ENTERPRISE
         * (roams via AD).
         */
        const val CRED_PERSIST_LOCAL_MACHINE: Int = 2

        /** CRED_FLAGS_NONE -- no special handling. */
        const val FLAGS_NONE: Int = 0

        /** Library name. JDK resolves `Advapi32` -> `Advapi32.dll` automatically on Windows. */
        const val ADVAPI32 = "Advapi32"
    }

    private val arena: Arena = Arena.ofShared()

    private val lookup: SymbolLookup? = runCatching {
        SymbolLookup.libraryLookup(ADVAPI32, arena)
    }.onFailure {
        log.info("Advapi32 not loadable on this system: {}", it.message ?: it.javaClass.simpleName)
    }.getOrNull()

    /**
     * CREDENTIALW layout per Win SDK wincred.h. Field offsets (x86_64):
     *
     * ```
     *  0: DWORD Flags                    (4)
     *  4: DWORD Type                     (4)
     *  8: LPWSTR TargetName              (8)
     * 16: LPWSTR Comment                 (8)
     * 24: FILETIME LastWritten           (8 = 2 DWORDs)
     * 32: DWORD CredentialBlobSize       (4)
     * 36: (padding to align next pointer to 8)
     * 40: LPBYTE CredentialBlob          (8)
     * 48: DWORD Persist                  (4)
     * 52: DWORD AttributeCount           (4)
     * 56: PCREDENTIAL_ATTRIBUTE Attribs  (8)
     * 64: LPWSTR TargetAlias             (8)
     * 72: LPWSTR UserName                (8)
     * 80: total
     * ```
     */
    private val credentialLayout: MemoryLayout = MemoryLayout.structLayout(
        ValueLayout.JAVA_INT.withName("Flags"),
        ValueLayout.JAVA_INT.withName("Type"),
        ValueLayout.ADDRESS.withName("TargetName"),
        ValueLayout.ADDRESS.withName("Comment"),
        ValueLayout.JAVA_INT.withName("LastWritten_Low"),
        ValueLayout.JAVA_INT.withName("LastWritten_High"),
        ValueLayout.JAVA_INT.withName("CredentialBlobSize"),
        MemoryLayout.paddingLayout(4),
        ValueLayout.ADDRESS.withName("CredentialBlob"),
        ValueLayout.JAVA_INT.withName("Persist"),
        ValueLayout.JAVA_INT.withName("AttributeCount"),
        ValueLayout.ADDRESS.withName("Attributes"),
        ValueLayout.ADDRESS.withName("TargetAlias"),
        ValueLayout.ADDRESS.withName("UserName"),
    )

    private val credWriteHandle: MethodHandle? = lookup?.downcall(
        "CredWriteW",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,  // BOOL (nonzero = success)
            ValueLayout.ADDRESS,   // PCREDENTIALW
            ValueLayout.JAVA_INT,  // DWORD Flags
        ),
    )

    private val credReadHandle: MethodHandle? = lookup?.downcall(
        "CredReadW",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,  // BOOL
            ValueLayout.ADDRESS,   // LPCWSTR TargetName
            ValueLayout.JAVA_INT,  // DWORD Type
            ValueLayout.JAVA_INT,  // DWORD Flags
            ValueLayout.ADDRESS,   // PCREDENTIALW *Credential (out)
        ),
    )

    private val credDeleteHandle: MethodHandle? = lookup?.downcall(
        "CredDeleteW",
        FunctionDescriptor.of(
            ValueLayout.JAVA_INT,  // BOOL
            ValueLayout.ADDRESS,   // LPCWSTR TargetName
            ValueLayout.JAVA_INT,  // DWORD Type
            ValueLayout.JAVA_INT,  // DWORD Flags
        ),
    )

    private val credFreeHandle: MethodHandle? = lookup?.downcall(
        "CredFree",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
    )

    override fun isAvailable(): Boolean {
        if (lookup == null || credWriteHandle == null || credDeleteHandle == null) return false
        // Probe via write+delete on a tagged probe entry -- same strategy
        // as the libsecret peer. CredDeleteW returns FALSE for "no match"
        // (ERROR_NOT_FOUND), so a bare delete alone can't distinguish
        // "service alive" from "service dead".
        return runCatching {
            val ok = store("io.github.kitty_hivens.Nexira.probe", "isAvailable", "ok")
            if (ok) clear("io.github.kitty_hivens.Nexira.probe", "isAvailable")
            ok
        }.getOrDefault(false)
    }

    override fun store(service: String, account: String, secret: String): Boolean {
        require(service.isNotBlank() && account.isNotBlank()) { "service and account must be non-blank" }
        val handle = credWriteHandle ?: return false
        return Arena.ofConfined().use { call ->
            try {
                val target = call.allocateUtf16LE(targetName(service, account))
                val userName = call.allocateUtf16LE("") // empty for generic credentials
                // CredentialBlob is raw bytes -- we marshal the secret as UTF-16LE
                // (without trailing null) and pass the byte length explicitly via
                // CredentialBlobSize. This matches what other tools (Git CM, etc.)
                // do for non-ASCII secrets.
                val secretBytes = secret.toByteArray(Charsets.UTF_16LE)
                val secretSegment = call.allocate(secretBytes.size.toLong())
                MemorySegment.copy(secretBytes, 0, secretSegment, ValueLayout.JAVA_BYTE, 0, secretBytes.size)

                val cred = call.allocate(credentialLayout)
                cred.set(ValueLayout.JAVA_INT, 0,  FLAGS_NONE)
                cred.set(ValueLayout.JAVA_INT, 4,  CRED_TYPE_GENERIC)
                cred.set(ValueLayout.ADDRESS,  8,  target)
                cred.set(ValueLayout.ADDRESS,  16, MemorySegment.NULL)         // Comment
                cred.set(ValueLayout.JAVA_INT, 24, 0)                           // LastWritten.Low
                cred.set(ValueLayout.JAVA_INT, 28, 0)                           // LastWritten.High
                cred.set(ValueLayout.JAVA_INT, 32, secretBytes.size)            // CredentialBlobSize
                cred.set(ValueLayout.ADDRESS,  40, secretSegment)               // CredentialBlob
                cred.set(ValueLayout.JAVA_INT, 48, CRED_PERSIST_LOCAL_MACHINE)
                cred.set(ValueLayout.JAVA_INT, 52, 0)                           // AttributeCount
                cred.set(ValueLayout.ADDRESS,  56, MemorySegment.NULL)          // Attributes
                cred.set(ValueLayout.ADDRESS,  64, MemorySegment.NULL)          // TargetAlias
                cred.set(ValueLayout.ADDRESS,  72, userName)                    // UserName

                val result = handle.invokeExact(cred, FLAGS_NONE) as Int
                result != 0
            } catch (t: Throwable) {
                log.warn("CredWriteW failed for {}/{}: {}", service, account, t.message)
                false
            }
        }
    }

    override fun retrieve(service: String, account: String): String? {
        require(service.isNotBlank() && account.isNotBlank()) { "service and account must be non-blank" }
        val handle = credReadHandle ?: return null
        val freeHandle = credFreeHandle ?: return null
        return Arena.ofConfined().use { call ->
            try {
                val target = call.allocateUtf16LE(targetName(service, account))
                // CredReadW writes a pointer-to-CREDENTIALW into our out-param.
                val outPtr = call.allocate(ValueLayout.ADDRESS)

                val result = handle.invokeExact(target, CRED_TYPE_GENERIC, FLAGS_NONE, outPtr) as Int
                if (result == 0) {
                    // GetLastError would give ERROR_NOT_FOUND (1168) typically,
                    // but null returns are unambiguous enough for our caller.
                    return@use null
                }

                val credAddress = outPtr.get(ValueLayout.ADDRESS, 0)
                if (credAddress.address() == 0L) return@use null

                val cred = credAddress.reinterpret(credentialLayout.byteSize())
                val blobSize = cred.get(ValueLayout.JAVA_INT, 32)
                val blobPtr = cred.get(ValueLayout.ADDRESS, 40)

                val value = if (blobSize > 0 && blobPtr.address() != 0L) {
                    val blob = blobPtr.reinterpret(blobSize.toLong())
                    val bytes = blob.toArray(ValueLayout.JAVA_BYTE)
                    String(bytes, Charsets.UTF_16LE)
                } else {
                    ""
                }

                // CredFree owns the buffer and any embedded pointers (TargetName,
                // CredentialBlob, etc). One call frees the whole graph.
                freeHandle.invokeExact(credAddress)
                value
            } catch (t: Throwable) {
                log.warn("CredReadW failed for {}/{}: {}", service, account, t.message)
                null
            }
        }
    }

    override fun clear(service: String, account: String): Boolean {
        require(service.isNotBlank() && account.isNotBlank()) { "service and account must be non-blank" }
        val handle = credDeleteHandle ?: return false
        return Arena.ofConfined().use { call ->
            try {
                val target = call.allocateUtf16LE(targetName(service, account))
                val result = handle.invokeExact(target, CRED_TYPE_GENERIC, FLAGS_NONE) as Int
                result != 0
            } catch (t: Throwable) {
                log.warn("CredDeleteW failed for {}/{}: {}", service, account, t.message)
                false
            }
        }
    }

    private fun targetName(service: String, account: String) = "$service/$account"
}

/**
 * Allocate a UTF-16LE null-terminated string in this arena. Windows
 * wide-char APIs (`LPWSTR`, `LPCWSTR`) all want exactly this format
 * with a trailing `\0\0`.
 */
private fun Arena.allocateUtf16LE(s: String): MemorySegment {
    val bytes = s.toByteArray(Charsets.UTF_16LE)
    val segment = allocate((bytes.size + 2).toLong())
    MemorySegment.copy(bytes, 0, segment, ValueLayout.JAVA_BYTE, 0, bytes.size)
    segment.set(ValueLayout.JAVA_BYTE, bytes.size.toLong(), 0)
    segment.set(ValueLayout.JAVA_BYTE, (bytes.size + 1).toLong(), 0)
    return segment
}

/**
 * Convenience: turn a `find(name)` Optional<MemorySegment> into a
 * non-variadic downcall MethodHandle, returning null when absent.
 */
private fun SymbolLookup.downcall(name: String, descriptor: FunctionDescriptor): MethodHandle? {
    val symbol = find(name).orElse(null) ?: return null
    return Linker.nativeLinker().downcallHandle(symbol, descriptor)
}
