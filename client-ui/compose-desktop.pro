# ============================================================================
# Aura Launcher - ProGuard Rules
# Java 25 | Compose Multiplatform | ProGuard 7.8+
# ============================================================================

# --- Core Settings ---
-dontobfuscate
-allowaccessmodification
-repackageclasses ''
-optimizationpasses 3

# --- JNA ---
-keep class com.sun.jna.** { *; }
-keepclassmembers class com.sun.jna.** { *; }
-dontwarn com.sun.jna.**

# --- Kotlin & Coroutines ---
-keep class kotlinx.coroutines.** { *; }
-keep class kotlinx.coroutines.swing.** { *; }
-keep class kotlinx.coroutines.internal.** { *; }
-keep class kotlinx.coroutines.scheduling.** { *; }
-keep class kotlinx.atomicfu.** { *; }

-keepnames class * implements kotlinx.coroutines.internal.MainDispatcherFactory
-keepnames class * implements kotlinx.coroutines.CoroutineExceptionHandler

# --- Compose & Skiko (Graphics) ---
-keep class org.jetbrains.skiko.** { *; }
-keep class org.jetbrains.skia.** { *; }
-keep class androidx.compose.** { *; }

# --- Ktor & Network ---
-keep class io.ktor.** { *; }
-keep class io.ktor.client.** { *; }
-keep class io.ktor.utils.io.** { *; }

# Fix for Ktor AttributesJvmBase NPE
-keepclassmembers class io.ktor.util.AttributesJvmBase {
    public <methods>;
}

# --- Okio  ---
-keep class okio.** { *; }
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# OkHttp
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# --- Coil 3 (Image Loading) ---
-keep class coil3.** { *; }
-keepnames class * implements coil3.ImageLoaderFactory

# --- Dependency Injection (Koin) ---
-keep class org.koin.** { *; }
-keep class hivens.launcher.di.** { *; }

# --- Application Code ---
-keep class hivens.ui.MainKt { *; }
-keep class hivens.ui.** { *; }
-keep class hivens.launcher.** { *; }
# Credential store + media resolvers moved out of hivens.launcher into their own
# modules; without these they silently fall out of the keep set (release-only breakage).
-keep class hivens.auth.** { *; }
-keep class hivens.media.** { *; }
-keep class hivens.core.** { *; }

# --- Serialization & Data Classes ---
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-keep class kotlinx.serialization.** { *; }

-keep,includedescriptorclasses class hivens.core.**$$serializer { *; }
-keepclassmembers class hivens.core.** {
    *** Companion;
}
-keepclasseswithmembers class hivens.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# --- Logging & SPI ---
-keep class ch.qos.logback.** { *; }
-keep class org.slf4j.** { *; }

-keepnames class * implements org.slf4j.spi.SLF4JServiceProvider
-keepnames class * implements ch.qos.logback.core.spi.LifeCycle
-keepnames class * implements java.util.spi.ToolProvider

# --- Resources ---
-adaptresourcefilecontents META-INF/services/**
-adaptresourcefilenames **.png,**.jpg,**.jpeg,**.gif,**.ico,**.properties

# --- Native Methods (JNI) ---
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- System tray ---
# dorkbox removed in 2.2.14 (libtray swap). The libtray Panama callers
# below take its place; the dorkbox keep rule was retired with the dep.

# --- Project Panama foreign-function call sites ---
# Classes that call MethodHandle.invokeExact(...) with java.lang.foreign.*
# arguments. The method is @PolymorphicSignature — the JVM accepts any
# descriptor at the call site, but ProGuard sees a concrete signature and
# can't find it on java.lang.invoke.MethodHandle. List each class explicitly
# so adding a new Panama caller is a deliberate edit, not silent suppression.
-dontwarn hivens.launcher.security.LinuxLibsecretKeyringStorage
-dontwarn hivens.launcher.security.WindowsCredentialManagerKeyringStorage
-dontwarn hivens.launcher.security.MacOSKeychainStorage

# ── libtray: keep + dontwarn the entire namespace ─────────────────────────
# CRITICAL: libtray's per-platform backends register Panama upcall stubs
# via `MethodHandles.lookup().findStatic(class, "wndProcEntry"|"onMenuItemEntry"|…, …)`.
# These methods have NO direct call sites in source — they're invoked at
# runtime by the OS through the upcall stub function pointer (Win32 WndProc,
# Cocoa NSMenuItem target-action, etc). ProGuard's reachability analysis
# sees the methods as orphaned, removes them, and the next `findStatic` at
# runtime fails with NoSuchMethodException → Tray.create returns null →
# user sees "no tray icon".
#
# This bit production Win11 users on 2.2.13 (issue #197): launcher.log has
# repeated `libtray.Win32Tray - WndProc upcall stub creation failed: no
# such method: dev.hivens.libtray.windows.Win32TrayImpl.wndProcEntry(...)`.
# The same pattern would fire on macOS (AppKitTrayImpl.onMenuItemEntry) once
# Phase 4 ships through ProGuard, and probably already breaks Linux SNI's
# release builds the same way (we just hadn't validated proguardReleaseJars
# for the libtray pieces specifically).
#
# Wildcard `-keep class dev.hivens.libtray.** { *; }` is the right fix:
# the libtray jar is opaque to us anyway, every backend uses the same
# upcall pattern, and a single rule covers current + future backends.
-keep class dev.hivens.libtray.** { *; }
-dontwarn dev.hivens.libtray.**

# ── libnotify: same Panama treatment as libtray ───────────────────────────
# The OS-notification sibling lib uses the identical pattern --
# MethodHandle.invokeExact(MemorySegment...) downcalls plus upcall stubs for
# its event dispatch (the D-Bus signal pump on Linux, WinRT toast handlers,
# Cocoa target-action) -- so it needs the same keep (the FFI methods have no
# source call sites and would be stripped) and dontwarn (the polymorphic
# invokeExact descriptors read as unresolved library members).
-keep class dev.hivens.libnotify.** { *; }
-dontwarn dev.hivens.libnotify.**

# ── libvault: same Panama treatment as libtray + libnotify ────────────────
# The keyring lib (Secret Service / Credential Manager / Keychain via FFM)
# reaches the uber jar transitively through client-launcher. Its per-platform
# bindings call MethodHandle.invokeExact(MemorySegment...) -- signature-
# polymorphic intrinsics ProGuard reports as unresolved library members and
# then aborts on. Same keep + dontwarn as the rest of the dev.hivens family.
-keep class dev.hivens.libvault.** { *; }
-dontwarn dev.hivens.libvault.**

# ── skinema: same Panama treatment as libtray + libnotify ─────────────────
# The media engine (FFmpeg/libwebp/libass via FFM) downcalls through
# MethodHandle.invokeExact(MemorySegment...) and registers upcall stubs the
# same way -- methods with no source call sites that ProGuard would strip,
# breaking native init at runtime (a release-only failure). The -skiko bridge
# and the natives loader live under the same root, so one wildcard covers them.
-keep class dev.hivens.skinema.** { *; }
-dontwarn dev.hivens.skinema.**

# ── dbus-java: ServiceLoader-resolved transport providers ─────────────────
# Linux fielded report (file picker silently broken on AppImage): filekit's
# XdgFilePickerPortal calls `DBusConnectionBuilder.forSessionBus()` which
# triggers `TransportBuilder.findTransportProvider()` → `ServiceLoader<
# ITransportProvider>` lookup. The provider class
# `org.freedesktop.dbus.transport.jre.NativeTransportProvider` is loaded
# only via META-INF/services — no direct call site in our code or in
# dbus-java's call graph. ProGuard's reachability sees it as orphaned and
# strips the class, then runtime ServiceLoader.iterator throws:
#   ServiceConfigurationError: ITransportProvider: Provider
#   org.freedesktop.dbus.transport.jre.NativeTransportProvider not found
#
# Side effect: filekit falls back to its non-portal LinuxFilePicker path,
# which on Wayland (Hyprland / Plasma 6 wayland-only) produces no window
# at all → user clicks "Open folder" / "Pick Java" → nothing happens.
#
# `-adaptresourcefilecontents META-INF/services/**` already preserves the
# service descriptor file contents, but we ALSO need to keep the actual
# implementation classes the descriptors point at. Wildcard the dbus-java
# namespace; we don't ship anything else in those packages.
-keep class org.freedesktop.dbus.** { *; }

# ── FileKit: extension-function overloads + PlatformFile constructors ─────
# Filed report after 2.3.1: the "Move data directory" picker call site
# (only one that passes `directory = PlatformFile(...)`) was a dead
# button on Win11 AND on Linux AppImage, but worked in dev. Other
# FilePicker call sites that don't pass `directory =` worked everywhere.
# Release-vs-dev with same platform behaviour points at ProGuard:
# the with-directory overload of openDirectoryPicker is reachable from
# only one place, and the `PlatformFile(File)` constructor only from
# that same place -- ProGuard's reachability culled one or both, and
# the call exploded at runtime in a way the call site silently
# swallowed. Keep the whole filekit namespace; the jar is small, we
# use the API surface across multiple screens, and a wildcard prevents
# the next "only this picker uses parameter X and now it's broken"
# variant of the same class of bug.
-keep class io.github.vinceglb.filekit.** { *; }
-keepclassmembers class io.github.vinceglb.filekit.** { *; }

# --- Suppress Warnings ---
-dontwarn ch.qos.logback.**
-dontwarn org.slf4j.**
-dontwarn jakarta.**
-dontwarn javax.**
-dontwarn org.apache.commons.**
-dontwarn org.tukaani.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.**
-dontwarn io.ktor.**
-dontwarn kotlinx.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn coil3.**
-dontwarn com.sun.jna.**
-dontwarn org.freedesktop.dbus.**
-dontwarn sun.misc.**
-dontwarn org.objectweb.asm.**
-dontwarn androidx.compose.**
-dontwarn org.jetbrains.**
-dontwarn dorkbox.**

# --- Ignored Notes ---
-dontnote module-info
-dontnote **.kotlin_module
