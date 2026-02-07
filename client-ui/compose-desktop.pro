# Aura Launcher - Aggressive Optimization Rules
# Target: -60% binary size, -50% startup time

# ============================================================================
# CORE OPTIMIZATIONS
# ============================================================================

-dontobfuscate
-optimizationpasses 7
-allowaccessmodification
-mergeinterfacesaggressively
-repackageclasses ''

# Aggressive dead code elimination
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*

# ============================================================================
# KOTLIN RUNTIME STRIPPING
# ============================================================================

# Remove Kotlin intrinsics checks in release
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void check*(...);
    public static void throwParameterIsNullException(...);
    public static void throwNpe(...);
}

# Inline kotlin collections
-assumenosideeffects class kotlin.collections.** {
    static void check*(...);
}

# ============================================================================
# LOGGING ELIMINATION (Production builds)
# ============================================================================

-assumenosideeffects class org.slf4j.Logger {
    public *** trace(...);
    public *** debug(...);
    public *** info(...);
}

-assumenosideeffects class ch.qos.logback.** {
    public protected *;
}

# ============================================================================
# COMPOSE DESKTOP OPTIMIZATIONS
# ============================================================================

-keep class androidx.compose.** { *; }
-keep class org.jetbrains.skia.** { *; }
-keep class org.jetbrains.skiko.** { *; }

-dontwarn androidx.compose.**
-dontwarn org.jetbrains.skia.**
-dontwarn org.jetbrains.skiko.**

# Keep @Composable functions metadata
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ============================================================================
# KTOR CLIENT OPTIMIZATION
# ============================================================================

-keep class io.ktor.client.** { *; }
-keep class io.ktor.http.** { *; }
-keep class io.ktor.utils.io.** { *; }

-dontwarn io.ktor.**

# OkHttp engine specifics
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ============================================================================
# KOTLINX SERIALIZATION
# ============================================================================

-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keep,includedescriptorclasses class hivens.core.**$$serializer { *; }
-keepclassmembers class hivens.core.** {
    *** Companion;
}
-keepclasseswithmembers class hivens.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ============================================================================
# KOIN DI OPTIMIZATION
# ============================================================================

-keep class org.koin.** { *; }
-keep class hivens.launcher.di.** { *; }

# ============================================================================
# APPLICATION ENTRY POINTS
# ============================================================================

-keep class hivens.ui.MainKt { *; }
-keep class hivens.ui.** { *; }

# Keep data classes
-keepclassmembers class hivens.core.data.** {
    <fields>;
    <init>(...);
}

# ============================================================================
# NATIVE LIBRARIES
# ============================================================================

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# ============================================================================
# SECURITY: Remove debug info
# ============================================================================

-assumenosideeffects class kotlin.jvm.internal.Reflection {
    public static ** get*(...);
}

# Remove source file names and line numbers (reduce size)
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable

# ============================================================================
# RESOURCE SHRINKING
# ============================================================================

# Remove unused resources
-adaptresourcefilenames **.png,**.jpg,**.jpeg,**.gif
-adaptresourcefilecontents **.MF,**.xml,**.properties
