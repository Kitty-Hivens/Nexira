package hivens.launcher.runtime

/**
 * The Java builds for Android this launcher was released with, one per major.
 * Written by scripts/pin-android-jdk.py from the releases of the build repository.
 */
internal val ANDROID_JDK_PINS: List<AndroidJdkRelease> = listOf(
    AndroidJdkRelease(8, "1.8.0_492", AndroidJdkCatalog.assetUrl("8u492"),
        "3f50bb47df5674ce94c89653f33cd22df24cebf69feaa3674ed82dddf4ccffb0"),
    AndroidJdkRelease(17, "17.0.21", AndroidJdkCatalog.assetUrl("17.0.21"),
        "1b738258a9e2a974701f40f3444d1df3f952106c3e8489f330744b6e1378502c"),
    AndroidJdkRelease(21, "21.0.13", AndroidJdkCatalog.assetUrl("21.0.13"),
        "3f63b6e3c56d97708efea100bbda69b543179f1f43c04257535bf888b098f8a0"),
    AndroidJdkRelease(25, "25.0.5", AndroidJdkCatalog.assetUrl("25.0.5"),
        "2b7277f2f5531a235e09956d7fe50c95264cd15790c55787c636df2ef61294d9"),
)
