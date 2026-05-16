package hivens.launcher.security

import hivens.core.security.IKeyringStorage
import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle

/**
 * macOS-side [IKeyringStorage] backed by the **Keychain Services** API
 * (the modern `SecItem*` surface introduced in 10.6, NOT the legacy
 * `SecKeychain*` calls which are deprecated since 10.10). Implemented with
 * **Project Panama** ([java.lang.foreign], JEP 454) -- same approach as the
 * Linux libsecret peer in [LinuxLibsecretKeyringStorage] and the Windows
 * Credential Manager peer in [WindowsCredentialManagerKeyringStorage].
 *
 * Backing store: `~/Library/Keychains/login.keychain-db` (the user's login
 * Keychain). Items are encrypted with the user's login password by the
 * Keychain daemon `securityd`. Tied to the Apple Silicon Secure Enclave
 * where present; otherwise to the user's login session.
 *
 * Wire-level surface (5 sync calls, no async, no Cocoa runtime):
 *
 * ```c
 * OSStatus SecItemAdd          (CFDictionaryRef attrs,        CFTypeRef *result);
 * OSStatus SecItemCopyMatching (CFDictionaryRef query,        CFTypeRef *result);
 * OSStatus SecItemUpdate       (CFDictionaryRef query,        CFDictionaryRef attrsToUpdate);
 * OSStatus SecItemDelete       (CFDictionaryRef query);
 * void     CFRelease           (CFTypeRef cf);
 * ```
 *
 * All input dictionaries are built ad-hoc per call. The dictionary keys are
 * `extern const CFStringRef` symbols exported by Security.framework
 * (`kSecClass`, `kSecAttrService`, `kSecAttrAccount`, `kSecValueData`,
 * `kSecReturnData`); the values are CFString / CFData / CFBoolean built via
 * CoreFoundation. Every CF object we create must be CFRelease'd by us;
 * `result` returned from CopyMatching is also caller-owned.
 *
 * Platform-untested at write time -- this implementation lands without a
 * physical Mac for end-to-end validation. The Panama bridge layout follows
 * Apple's documented C ABI, the call shapes match the public framework
 * headers, and the Linux/Windows peers serve as the cross-implementation
 * reference. First user with a real Mac who hits a [retrieve]/[store]
 * miscompare should treat this as a bug report against this file.
 */
internal class MacOSKeychainStorage : IKeyringStorage {

    private val log = LoggerFactory.getLogger(MacOSKeychainStorage::class.java)

    private companion object {
        // System framework absolute paths -- `dlopen` accepts these directly,
        // `SymbolLookup.libraryLookup` chains through. Bare names like
        // "Security" don't resolve on macOS without DYLD search paths set.
        const val SECURITY_FRAMEWORK = "/System/Library/Frameworks/Security.framework/Security"
        const val CORE_FOUNDATION    = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"

        // OSStatus values that have meaning to us. errSecItemNotFound is the
        // expected result for a retrieve-not-present, and errSecDuplicateItem
        // signals "use SecItemUpdate instead of SecItemAdd". The rest fall
        // through to a generic warn-and-return-null/false.
        const val errSecSuccess: Int       = 0
        const val errSecItemNotFound: Int  = -25300
        const val errSecDuplicateItem: Int = -25299

        // CFStringEncoding constant for UTF-8 -- value defined in
        // CFStringEncodingExt.h; not exported as a symbol so we hard-code it.
        const val kCFStringEncodingUTF8: Int = 0x08000100

        /** Probe service for [isAvailable] -- same shape as the Linux/Windows peers. */
        const val PROBE_SERVICE = "io.github.kitty_hivens.AuraLauncher.probe"
        const val PROBE_ACCOUNT = "isAvailable"
    }

    private val arena: Arena = Arena.ofShared()

    private val securityLookup: SymbolLookup? = runCatching {
        SymbolLookup.libraryLookup(SECURITY_FRAMEWORK, arena)
    }.onFailure {
        log.info("Security.framework not loadable: {}", it.message ?: it.javaClass.simpleName)
    }.getOrNull()

    private val cfLookup: SymbolLookup? = runCatching {
        SymbolLookup.libraryLookup(CORE_FOUNDATION, arena)
    }.onFailure {
        log.info("CoreFoundation not loadable: {}", it.message ?: it.javaClass.simpleName)
    }.getOrNull()

    private val linker: Linker = Linker.nativeLinker()

    // ── SecItem* function handles ─────────────────────────────────────────
    private val secItemAddHandle: MethodHandle? = securityLookup?.downcall(
        "SecItemAdd",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val secItemCopyMatchingHandle: MethodHandle? = securityLookup?.downcall(
        "SecItemCopyMatching",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val secItemUpdateHandle: MethodHandle? = securityLookup?.downcall(
        "SecItemUpdate",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    private val secItemDeleteHandle: MethodHandle? = securityLookup?.downcall(
        "SecItemDelete",
        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS),
    )

    // ── CoreFoundation function handles ───────────────────────────────────
    private val cfReleaseHandle: MethodHandle? = cfLookup?.downcall(
        "CFRelease", FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
    )
    // CFString CFStringCreateWithBytes(allocator, bytes, len:CFIndex, enc, isExternalRep:Boolean)
    private val cfStringCreateHandle: MethodHandle? = cfLookup?.downcall(
        "CFStringCreateWithBytes",
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,    // CFStringRef
            ValueLayout.ADDRESS,    // allocator (NULL -> default)
            ValueLayout.ADDRESS,    // bytes
            ValueLayout.JAVA_LONG,  // CFIndex (signed long)
            ValueLayout.JAVA_INT,   // CFStringEncoding
            ValueLayout.JAVA_BYTE,  // Boolean
        ),
    )
    // CFData CFDataCreate(allocator, bytes, length:CFIndex)
    private val cfDataCreateHandle: MethodHandle? = cfLookup?.downcall(
        "CFDataCreate",
        FunctionDescriptor.of(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
        ),
    )
    // CFDictionary CFDictionaryCreate(allocator, keys**, values**, count, keyCB*, valueCB*)
    private val cfDictionaryCreateHandle: MethodHandle? = cfLookup?.downcall(
        "CFDictionaryCreate",
        FunctionDescriptor.of(
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
        ),
    )
    // const UInt8 *CFDataGetBytePtr(CFDataRef)
    private val cfDataGetBytePtrHandle: MethodHandle? = cfLookup?.downcall(
        "CFDataGetBytePtr",
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS),
    )
    // CFIndex CFDataGetLength(CFDataRef)
    private val cfDataGetLengthHandle: MethodHandle? = cfLookup?.downcall(
        "CFDataGetLength",
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS),
    )

    // ── Dereferenced kSec / kCFBoolean constants ──────────────────────────
    // Each is `extern const CFTypeRef kFoo;` -- the symbol's address is the
    // storage location of a pointer; deref once to get the actual CFTypeRef
    // we'd pass as a value. The kCFType*CallBacks are STRUCTS (not pointers),
    // so we keep their raw address -- passing &kCFTypeDictionaryKeyCallBacks
    // is what the C call expects.
    private val kSecClass                = derefCFConstant(securityLookup, "kSecClass")
    private val kSecClassGenericPassword = derefCFConstant(securityLookup, "kSecClassGenericPassword")
    private val kSecAttrService          = derefCFConstant(securityLookup, "kSecAttrService")
    private val kSecAttrAccount          = derefCFConstant(securityLookup, "kSecAttrAccount")
    private val kSecValueData            = derefCFConstant(securityLookup, "kSecValueData")
    private val kSecReturnData           = derefCFConstant(securityLookup, "kSecReturnData")
    private val kCFBooleanTrue           = derefCFConstant(cfLookup,        "kCFBooleanTrue")
    private val kCFTypeDictionaryKeyCallBacks   = cfLookup?.find("kCFTypeDictionaryKeyCallBacks")?.orElse(null)
    private val kCFTypeDictionaryValueCallBacks = cfLookup?.find("kCFTypeDictionaryValueCallBacks")?.orElse(null)

    /** True when every symbol we depend on resolved at construction time. */
    private val symbolsReady: Boolean =
        secItemAddHandle != null && secItemCopyMatchingHandle != null &&
            secItemUpdateHandle != null && secItemDeleteHandle != null &&
            cfReleaseHandle != null && cfStringCreateHandle != null &&
            cfDataCreateHandle != null && cfDictionaryCreateHandle != null &&
            cfDataGetBytePtrHandle != null && cfDataGetLengthHandle != null &&
            kSecClass != null && kSecClassGenericPassword != null &&
            kSecAttrService != null && kSecAttrAccount != null &&
            kSecValueData != null && kSecReturnData != null && kCFBooleanTrue != null &&
            kCFTypeDictionaryKeyCallBacks != null && kCFTypeDictionaryValueCallBacks != null

    override fun isAvailable(): Boolean {
        if (!symbolsReady) return false
        return runCatching {
            val ok = store(PROBE_SERVICE, PROBE_ACCOUNT, "ok")
            if (ok) clear(PROBE_SERVICE, PROBE_ACCOUNT)
            ok
        }.getOrDefault(false)
    }

    override fun store(service: String, account: String, secret: String): Boolean {
        require(service.isNotBlank() && account.isNotBlank()) { "service and account must be non-blank" }
        if (!symbolsReady) return false
        return Arena.ofConfined().use { call ->
            try {
                val refs = mutableListOf<MemorySegment>()
                val serviceCF = createCFString(call, service).also { refs += it }
                val accountCF = createCFString(call, account).also { refs += it }
                val dataCF    = createCFData(call, secret.toByteArray(Charsets.UTF_8)).also { refs += it }

                // Build the {Class, Service, Account, Value} dictionary that
                // SecItemAdd takes for the create path.
                val addQuery = createCFDictionary(call,
                    keys = arrayOf(kSecClass!!, kSecAttrService!!, kSecAttrAccount!!, kSecValueData!!),
                    values = arrayOf(kSecClassGenericPassword!!, serviceCF, accountCF, dataCF),
                ).also { refs += it }

                var status = (secItemAddHandle!!.invokeExact(addQuery, MemorySegment.NULL) as Int)
                if (status == errSecDuplicateItem) {
                    // Item already exists -- switch to update. SecItemUpdate
                    // wants the locator dict (no kSecValueData) and a separate
                    // attrs-to-update dict carrying just the new value.
                    val locator = createCFDictionary(call,
                        keys = arrayOf(kSecClass, kSecAttrService, kSecAttrAccount),
                        values = arrayOf(kSecClassGenericPassword, serviceCF, accountCF),
                    ).also { refs += it }
                    val updates = createCFDictionary(call,
                        keys = arrayOf(kSecValueData),
                        values = arrayOf(dataCF),
                    ).also { refs += it }
                    status = (secItemUpdateHandle!!.invokeExact(locator, updates) as Int)
                }
                cfReleaseAll(refs)

                if (status != errSecSuccess) {
                    log.warn("SecItemAdd/Update failed for {}/{}: OSStatus={}", service, account, status)
                    return@use false
                }
                true
            } catch (t: Throwable) {
                log.warn("SecItemAdd/Update threw for {}/{}: {}", service, account, t.message)
                false
            }
        }
    }

    override fun retrieve(service: String, account: String): String? {
        require(service.isNotBlank() && account.isNotBlank()) { "service and account must be non-blank" }
        if (!symbolsReady) return null
        return Arena.ofConfined().use { call ->
            try {
                val refs = mutableListOf<MemorySegment>()
                val serviceCF = createCFString(call, service).also { refs += it }
                val accountCF = createCFString(call, account).also { refs += it }

                val query = createCFDictionary(call,
                    keys = arrayOf(kSecClass!!, kSecAttrService!!, kSecAttrAccount!!, kSecReturnData!!),
                    values = arrayOf(kSecClassGenericPassword!!, serviceCF, accountCF, kCFBooleanTrue!!),
                ).also { refs += it }

                val outPtr = call.allocate(ValueLayout.ADDRESS)
                val status = (secItemCopyMatchingHandle!!.invokeExact(query, outPtr) as Int)

                if (status == errSecItemNotFound) {
                    cfReleaseAll(refs)
                    return@use null
                }
                if (status != errSecSuccess) {
                    cfReleaseAll(refs)
                    log.warn("SecItemCopyMatching failed for {}/{}: OSStatus={}", service, account, status)
                    return@use null
                }

                val cfData = outPtr.get(ValueLayout.ADDRESS, 0)
                if (cfData.address() == 0L) {
                    cfReleaseAll(refs)
                    return@use null
                }
                val length = cfDataGetLengthHandle!!.invokeExact(cfData) as Long
                val ptr    = cfDataGetBytePtrHandle!!.invokeExact(cfData) as MemorySegment
                val bytes = if (length > 0 && ptr.address() != 0L) {
                    ptr.reinterpret(length).toArray(ValueLayout.JAVA_BYTE)
                } else ByteArray(0)

                // Caller owns the result returned from CopyMatching as well.
                cfReleaseHandle!!.invokeExact(cfData) as Unit
                cfReleaseAll(refs)
                String(bytes, Charsets.UTF_8)
            } catch (t: Throwable) {
                log.warn("SecItemCopyMatching threw for {}/{}: {}", service, account, t.message)
                null
            }
        }
    }

    override fun clear(service: String, account: String): Boolean {
        require(service.isNotBlank() && account.isNotBlank()) { "service and account must be non-blank" }
        if (!symbolsReady) return false
        return Arena.ofConfined().use { call ->
            try {
                val refs = mutableListOf<MemorySegment>()
                val serviceCF = createCFString(call, service).also { refs += it }
                val accountCF = createCFString(call, account).also { refs += it }

                val query = createCFDictionary(call,
                    keys = arrayOf(kSecClass!!, kSecAttrService!!, kSecAttrAccount!!),
                    values = arrayOf(kSecClassGenericPassword!!, serviceCF, accountCF),
                ).also { refs += it }

                val status = (secItemDeleteHandle!!.invokeExact(query) as Int)
                cfReleaseAll(refs)

                // Idempotent per the contract: "no item to delete" is success.
                status == errSecSuccess || status == errSecItemNotFound
            } catch (t: Throwable) {
                log.warn("SecItemDelete threw for {}/{}: {}", service, account, t.message)
                false
            }
        }
    }

    // ── CoreFoundation marshaling helpers ─────────────────────────────────

    private fun createCFString(call: Arena, s: String): MemorySegment {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val buf = call.allocate(bytes.size.toLong().coerceAtLeast(1))
        if (bytes.isNotEmpty()) {
            MemorySegment.copy(bytes, 0, buf, ValueLayout.JAVA_BYTE, 0, bytes.size)
        }
        return cfStringCreateHandle!!.invokeExact(
            MemorySegment.NULL, buf, bytes.size.toLong(), kCFStringEncodingUTF8, 0.toByte(),
        ) as MemorySegment
    }

    private fun createCFData(call: Arena, bytes: ByteArray): MemorySegment {
        val buf = call.allocate(bytes.size.toLong().coerceAtLeast(1))
        if (bytes.isNotEmpty()) {
            MemorySegment.copy(bytes, 0, buf, ValueLayout.JAVA_BYTE, 0, bytes.size)
        }
        return cfDataCreateHandle!!.invokeExact(
            MemorySegment.NULL, buf, bytes.size.toLong(),
        ) as MemorySegment
    }

    /**
     * Build a CFDictionary from parallel key/value arrays. Both arrays are
     * marshaled into ADDRESS arrays in [call]'s arena; CFDictionaryCreate
     * copies the values out, so the arrays don't need to outlive this call.
     */
    private fun createCFDictionary(
        call: Arena,
        keys: Array<MemorySegment>,
        values: Array<MemorySegment>,
    ): MemorySegment {
        require(keys.size == values.size)
        val n = keys.size
        val keysBuf   = call.allocate(ValueLayout.ADDRESS, n.toLong())
        val valuesBuf = call.allocate(ValueLayout.ADDRESS, n.toLong())
        for (i in 0 until n) {
            keysBuf.setAtIndex(ValueLayout.ADDRESS,   i.toLong(), keys[i])
            valuesBuf.setAtIndex(ValueLayout.ADDRESS, i.toLong(), values[i])
        }
        return cfDictionaryCreateHandle!!.invokeExact(
            MemorySegment.NULL, keysBuf, valuesBuf, n.toLong(),
            kCFTypeDictionaryKeyCallBacks!!, kCFTypeDictionaryValueCallBacks!!,
        ) as MemorySegment
    }

    /** CFRelease every CF object we allocated, swallowing per-call failures. */
    private fun cfReleaseAll(refs: List<MemorySegment>) {
        val release = cfReleaseHandle ?: return
        for (ref in refs) {
            if (ref.address() == 0L) continue
            runCatching { release.invokeExact(ref) as Unit }
        }
    }
}

/**
 * Read a `extern const CFTypeRef kFoo;` constant: the lookup symbol gives
 * the storage address; deref once to recover the CFTypeRef value the C
 * caller would write `kFoo` for. Returns null if the symbol can't be found
 * (e.g. running on a stripped Security.framework variant).
 */
private fun derefCFConstant(lookup: SymbolLookup?, name: String): MemorySegment? {
    val storage = lookup?.find(name)?.orElse(null) ?: return null
    return storage.reinterpret(8).get(ValueLayout.ADDRESS, 0)
        .takeIf { it.address() != 0L }
}

/**
 * Convenience: turn a `find(name)` Optional<MemorySegment> into a
 * non-variadic downcall MethodHandle, returning null when absent.
 * Mirrors the helper in [WindowsCredentialManagerKeyringStorage].
 */
private fun SymbolLookup.downcall(name: String, descriptor: FunctionDescriptor): MethodHandle? {
    val symbol = find(name).orElse(null) ?: return null
    return Linker.nativeLinker().downcallHandle(symbol, descriptor)
}
