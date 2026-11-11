package hivens.launcher.runtime

/**
 * The Java builds for Android this launcher was released with, one per major.
 * Written by scripts/pin-android-jdk.py from the releases of the build repository.
 */
internal val ANDROID_JDK_PINS: List<AndroidJdkRelease> = listOf(
    AndroidJdkRelease(8, "1.8.0_492", AndroidJdkCatalog.assetUrl("8u492"),
        "64544c5005377a1ebbad6f49187388bf21955b80245a7a0e8bc7c2af090cdd57"),
    AndroidJdkRelease(17, "17.0.21", AndroidJdkCatalog.assetUrl("17.0.21"),
        "a153505b82bff92bd053356c61fee823b64074fedcc71baee25bc7be96cb38cd"),
    AndroidJdkRelease(21, "21.0.13", AndroidJdkCatalog.assetUrl("21.0.13"),
        "465b67474245e80493d179f5c9aec5bc78c55d48addaccfed919174ae8a618e1"),
    AndroidJdkRelease(25, "25.0.5", AndroidJdkCatalog.assetUrl("25.0.5"),
        "b886b90c964271418f4ca937be02bcfd36ea41af0e5eaf1ddd86c5889e63e0d8"),
)
