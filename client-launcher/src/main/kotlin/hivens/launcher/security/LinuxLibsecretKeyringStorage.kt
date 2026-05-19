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
 * Linux-side [IKeyringStorage] backed by libsecret over the Freedesktop
 * Secret Service DBus protocol. Implemented with **Project Panama**
 * ([java.lang.foreign], JEP 454 finalized in Java 22) -- no JNA, no
 * generated bindings, just the native foreign-function API that ships
 * with the JDK.
 *
 * Works on any compositor with a Secret Service provider running:
 *   - GNOME / Cinnamon / Budgie / generic GTK desktops -> gnome-keyring
 *   - KDE Plasma -> kwallet5/6 with kwallet-secret-service bridge
 *   - Hyprland / Sway / standalone -> user-installed gnome-keyring or
 *     kwalletd as a userspace service
 *
 * If `libsecret-1.so.0` is not loadable or no Secret Service daemon
 * is running, [isAvailable] returns false and the launcher falls
 * back to its AES-GCM file (CredentialsManager file path stays the
 * forever-fallback by design).
 *
 * libsecret-1 API used (3 sync calls, no async):
 *
 * ```c
 * gboolean secret_password_store_sync (
 *     const SecretSchema *schema,
 *     const gchar *collection,           // NULL = default keyring
 *     const gchar *label,
 *     const gchar *password,
 *     GCancellable *cancellable,         // NULL = none
 *     GError **error,
 *     ...                                // NULL-terminated attribute pairs
 * );
 * gchar *secret_password_lookup_sync (...);    // returns NULL if absent
 * gboolean secret_password_clear_sync (...);   // FALSE on no-match
 * void secret_password_free (gchar *);         // free lookup result
 * ```
 *
 * Schema is built once at object construction in a global Arena (lives
 * for JVM lifetime). Per-call Arenas hold transient strings during one
 * libsecret round-trip. Both keep memory deterministic -- no GC-tied
 * native lifetime as JNA had.
 *
 * Concurrency: all three sync calls block the calling thread for the
 * DBus round-trip (~5–50ms typical, longer if the keyring needs to
 * prompt the user for unlock). NOT safe for the UI thread on first
 * login of a freshly-booted machine.
 *
 * Native access: requires `--enable-native-access=ALL-UNNAMED` (or a
 * specific module name) on the JVM command line. JDK 25 currently
 * downgrades the missing-flag case to a warning, but JEP 472 will
 * promote it to a hard failure in a future release. The launcher's
 * test task and AppImage AppRun both pass the flag.
 */
internal class LinuxLibsecretKeyringStorage : IKeyringStorage {

    private val log = LoggerFactory.getLogger(LinuxLibsecretKeyringStorage::class.java)

    private companion object {
        /** SECRET_SCHEMA_DONT_MATCH_NAME = 1<<1 -- ignore schema name on lookup/clear. */
        const val SCHEMA_FLAG_DONT_MATCH_NAME = 0x2
        /** SECRET_SCHEMA_ATTRIBUTE_STRING = 0. */
        const val ATTR_TYPE_STRING = 0
        const val SCHEMA_NAME = "io.github.kitty_hivens.AuraLauncher"
        const val ATTR_SERVICE = "service"
        const val ATTR_ACCOUNT = "account"
        const val LIBSECRET_SONAME = "libsecret-1.so.0"

        // Layout of SecretSchemaAttribute { const char *name; int type; }.
        // Aligned to pointer (8 bytes on x86-64), so int gets 4 bytes of
        // tail padding. Total 16 bytes per element.
        val ATTR_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("name"),
            ValueLayout.JAVA_INT.withName("type"),
            MemoryLayout.paddingLayout(4),
        )

        // Layout of SecretSchema {
        //   const char *name;
        //   guint flags;            // 4 bytes + 4 padding
        //   SecretSchemaAttribute attributes[32];
        //   gint reserved;          // 4 bytes + 4 padding
        //   gpointer reserved1..7;  // 7 * 8 bytes
        // } -- total 8+8 + 32*16 + 8 + 7*8 = 592 bytes.
        val SCHEMA_LAYOUT: MemoryLayout = MemoryLayout.structLayout(
            ValueLayout.ADDRESS.withName("name"),
            ValueLayout.JAVA_INT.withName("flags"),
            MemoryLayout.paddingLayout(4),
            MemoryLayout.sequenceLayout(32, ATTR_LAYOUT).withName("attributes"),
            ValueLayout.JAVA_INT.withName("reserved"),
            MemoryLayout.paddingLayout(4),
            MemoryLayout.sequenceLayout(7, ValueLayout.ADDRESS).withName("reserved_ptrs"),
        )
    }

    private val arena: Arena = Arena.ofShared()

    /** Loaded libsecret-1.so.0, or null if unavailable on this host. */
    private val lookup: SymbolLookup? = runCatching {
        SymbolLookup.libraryLookup(LIBSECRET_SONAME, arena)
    }.onFailure {
        log.info("libsecret not loadable: {}", it.message ?: it.javaClass.simpleName)
    }.getOrNull()

    /**
     * Pre-built downcall handles for the three libsecret entry points
     * we need. FunctionDescriptors lock in exactly two attribute pairs
     * + the trailing NULL sentinel -- matches our IKeyringStorage shape.
     */
    private val storeHandle: MethodHandle? = lookup?.downcallOrNull(
        "secret_password_store_sync",
        FunctionDescriptor.of(
            ValueLayout.JAVA_BOOLEAN, // return: gboolean
            ValueLayout.ADDRESS,      // schema*
            ValueLayout.ADDRESS,      // collection (NULL)
            ValueLayout.ADDRESS,      // label
            ValueLayout.ADDRESS,      // password
            ValueLayout.ADDRESS,      // cancellable (NULL)
            ValueLayout.ADDRESS,      // GError**
            // varargs from here:
            ValueLayout.ADDRESS,      // attr1 name
            ValueLayout.ADDRESS,      // attr1 value
            ValueLayout.ADDRESS,      // attr2 name
            ValueLayout.ADDRESS,      // attr2 value
            ValueLayout.ADDRESS,      // NULL terminator
        ),
        firstVariadic = 6,
    )

    private val lookupHandle: MethodHandle? = lookup?.downcallOrNull(
        "secret_password_lookup_sync",
        FunctionDescriptor.of(
            ValueLayout.ADDRESS,      // return: gchar* (NULL on miss)
            ValueLayout.ADDRESS,      // schema*
            ValueLayout.ADDRESS,      // cancellable (NULL)
            ValueLayout.ADDRESS,      // GError**
            ValueLayout.ADDRESS,      // attr1 name
            ValueLayout.ADDRESS,      // attr1 value
            ValueLayout.ADDRESS,      // attr2 name
            ValueLayout.ADDRESS,      // attr2 value
            ValueLayout.ADDRESS,      // NULL terminator
        ),
        firstVariadic = 3,
    )

    private val clearHandle: MethodHandle? = lookup?.downcallOrNull(
        "secret_password_clear_sync",
        FunctionDescriptor.of(
            ValueLayout.JAVA_BOOLEAN, // return: gboolean
            ValueLayout.ADDRESS,      // schema*
            ValueLayout.ADDRESS,      // cancellable (NULL)
            ValueLayout.ADDRESS,      // GError**
            ValueLayout.ADDRESS,      // attr1 name
            ValueLayout.ADDRESS,      // attr1 value
            ValueLayout.ADDRESS,      // attr2 name
            ValueLayout.ADDRESS,      // attr2 value
            ValueLayout.ADDRESS,      // NULL terminator
        ),
        firstVariadic = 3,
    )

    private val freeHandle: MethodHandle? = lookup?.downcallOrNull(
        "secret_password_free",
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
        firstVariadic = -1, // no varargs
    )

    /** Pre-built schema struct in the global arena. Lives forever. */
    private val schemaPtr: MemorySegment? = if (lookup == null) null else buildSchema()

    override fun isAvailable(): Boolean {
        if (storeHandle == null || clearHandle == null || schemaPtr == null) return false
        // Probe with a write+clear round-trip -- only signal that
        // distinguishes "daemon alive" from "daemon dead" without
        // GError introspection. libsecret_password_clear_sync returns
        // FALSE when nothing was matched, NOT "FALSE on dead daemon",
        // so a bare clear cannot distinguish those.
        return runCatching {
            val ok = store(SCHEMA_NAME, "isAvailable-probe", "ok")
            if (ok) clear(SCHEMA_NAME, "isAvailable-probe")
            ok
        }.getOrDefault(false)
    }

    override fun store(service: String, account: String, secret: String): Boolean {
        require(service.isNotBlank() && account.isNotBlank()) { "service and account must be non-blank" }
        val handle = storeHandle ?: return false
        val schema = schemaPtr ?: return false
        return Arena.ofConfined().use { call ->
            try {
                handle.invokeExact(
                    schema,
                    MemorySegment.NULL,
                    call.allocateUtf8("Aura: $service/$account"),
                    call.allocateUtf8(secret),
                    MemorySegment.NULL,
                    call.allocate(ValueLayout.ADDRESS), // GError**, zero-init
                    call.allocateUtf8(ATTR_SERVICE),
                    call.allocateUtf8(service),
                    call.allocateUtf8(ATTR_ACCOUNT),
                    call.allocateUtf8(account),
                    MemorySegment.NULL,
                ) as Boolean
            } catch (t: Throwable) {
                log.warn("libsecret store failed for {}/{}: {}", service, account, t.message)
                false
            }
        }
    }

    override fun retrieve(service: String, account: String): String? {
        require(service.isNotBlank() && account.isNotBlank()) { "service and account must be non-blank" }
        val handle = lookupHandle ?: return null
        val free = freeHandle ?: return null
        val schema = schemaPtr ?: return null
        return Arena.ofConfined().use { call ->
            try {
                val raw = handle.invokeExact(
                    schema,
                    MemorySegment.NULL,
                    call.allocate(ValueLayout.ADDRESS), // GError**
                    call.allocateUtf8(ATTR_SERVICE),
                    call.allocateUtf8(service),
                    call.allocateUtf8(ATTR_ACCOUNT),
                    call.allocateUtf8(account),
                    MemorySegment.NULL,
                ) as MemorySegment
                if (raw.address() == 0L) null
                else {
                    // The returned char* points to caller-owned native memory;
                    // we can't freely read .getString() from it because the
                    // returned segment is zero-length (Panama doesn't know
                    // the C string's length). Reinterpret with a "long
                    // enough" bound, then read up to NUL.
                    val bound = raw.reinterpret(Long.MAX_VALUE)
                    val value = bound.getString(0)
                    free.invokeExact(raw)
                    value
                }
            } catch (t: Throwable) {
                log.warn("libsecret retrieve failed for {}/{}: {}", service, account, t.message)
                null
            }
        }
    }

    override fun clear(service: String, account: String): Boolean {
        require(service.isNotBlank() && account.isNotBlank()) { "service and account must be non-blank" }
        val handle = clearHandle ?: return false
        val schema = schemaPtr ?: return false
        return Arena.ofConfined().use { call ->
            try {
                handle.invokeExact(
                    schema,
                    MemorySegment.NULL,
                    call.allocate(ValueLayout.ADDRESS), // GError**
                    call.allocateUtf8(ATTR_SERVICE),
                    call.allocateUtf8(service),
                    call.allocateUtf8(ATTR_ACCOUNT),
                    call.allocateUtf8(account),
                    MemorySegment.NULL,
                ) as Boolean
            } catch (t: Throwable) {
                log.warn("libsecret clear failed for {}/{}: {}", service, account, t.message)
                false
            }
        }
    }

    private fun buildSchema(): MemorySegment {
        val s = arena.allocate(SCHEMA_LAYOUT)
        // name (offset 0): pointer to "io.github.kitty_hivens.AuraLauncher"
        val schemaName = arena.allocateUtf8(SCHEMA_NAME)
        s.set(ValueLayout.ADDRESS, 0, schemaName)
        // flags (offset 8): SECRET_SCHEMA_DONT_MATCH_NAME
        s.set(ValueLayout.JAVA_INT, 8, SCHEMA_FLAG_DONT_MATCH_NAME)
        // attributes[0] starts at offset 16; pair stride is 16 bytes (8 ptr + 4 int + 4 pad)
        val attrServiceName = arena.allocateUtf8(ATTR_SERVICE)
        val attrAccountName = arena.allocateUtf8(ATTR_ACCOUNT)
        s.set(ValueLayout.ADDRESS, 16, attrServiceName)
        s.set(ValueLayout.JAVA_INT, 24, ATTR_TYPE_STRING)
        s.set(ValueLayout.ADDRESS, 32, attrAccountName)
        s.set(ValueLayout.JAVA_INT, 40, ATTR_TYPE_STRING)
        // remaining attributes[2..31] stay zero -- Arena.allocate zero-fills
        return s
    }
}

/**
 * Helper: turn a `find(name)` Optional<MemorySegment> into a downcall
 * MethodHandle, returning null when the symbol isn't present.
 *
 * `firstVariadic = -1` means the function is non-variadic.
 */
private fun SymbolLookup.downcallOrNull(
    name: String,
    descriptor: FunctionDescriptor,
    firstVariadic: Int,
): MethodHandle? {
    val symbol = find(name).orElse(null) ?: return null
    val linker = Linker.nativeLinker()
    val options = if (firstVariadic >= 0) {
        arrayOf(Linker.Option.firstVariadicArg(firstVariadic))
    } else {
        emptyArray()
    }
    return linker.downcallHandle(symbol, descriptor, *options)
}

private fun Arena.allocateUtf8(s: String): MemorySegment = allocateFrom(s)
