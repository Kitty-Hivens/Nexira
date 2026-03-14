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
-keep class dorkbox.** { *; }

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
