package hivens.android

import com.android.build.api.dsl.CompileSdkSpec
import org.gradle.api.GradleException
import org.gradle.api.provider.ProviderFactory

/**
 * The Android API the launcher's Android targets compile against, the app and every library
 * module alike: `compileSdk { launcherApi(providers) }`.
 *
 * One place, because a dependency's floor is checked against each module that depends on it, and
 * only by a release build. Compose 1.13 for Android asks for at least 37.1, and the app had moved to
 * it while the libraries, each with its own copy of the number, stayed on 37.0 and failed the release
 * APK. The newest release level is taken rather than the floor, so the next dependency that raises it
 * finds it met.
 *
 * The Gradle property `nexira.android.compileSdk` overrides it, as `37.1` or as a bare `37`.
 */
fun CompileSdkSpec.launcherApi(providers: ProviderFactory) {
    val (major, minor) = providers.gradleProperty(PROPERTY).map(::parseApiLevel).getOrElse(COMPILE_SDK)
    version = release(major) { minorApiLevel = minor }
}

/** `37.1` as 37 and 1, a bare `37` as 37 and no minor level. */
internal fun parseApiLevel(text: String): Pair<Int, Int?> {
    val parts = text.trim().split('.')
    val major = parts[0].toIntOrNull()
    val minor = parts.getOrNull(1)?.toIntOrNull()
    if (major == null || parts.size > 2 || (parts.size == 2 && minor == null)) {
        throw GradleException("$PROPERTY=$text is not an API level such as 37 or 37.1")
    }
    return major to minor
}

private const val PROPERTY = "nexira.android.compileSdk"

private val COMPILE_SDK: Pair<Int, Int?> = 37 to 2
