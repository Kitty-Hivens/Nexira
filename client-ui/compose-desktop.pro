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
