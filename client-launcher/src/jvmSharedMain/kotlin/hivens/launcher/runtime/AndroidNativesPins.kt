package hivens.launcher.runtime

/**
 * The natives for Android this launcher was released with, one release per LWJGL version.
 * Written by scripts/pin-android-natives.py from the releases of the build repository.
 */
internal val ANDROID_NATIVES_PINS: List<AndroidNativesRelease> = listOf(
    AndroidNativesRelease("3.2.1", listOf(
            AndroidNativesAsset("lwjgl-3.2.1-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.2.1", "lwjgl-3.2.1-natives-android-arm64.jar"),
                "7fda5d995c66c33b43e2f628b463cb9be930d1045c237af79884c5388abd6353"),
            AndroidNativesAsset("nexira-host-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.2.1", "nexira-host-natives-android-arm64.jar"),
                "77fe83e6bdaa91529a2aec26c64140593c242215f7c06bd8b3328a1cd3a5f0cf"),
    )),
    AndroidNativesRelease("3.2.2", listOf(
            AndroidNativesAsset("lwjgl-3.2.2-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.2.2", "lwjgl-3.2.2-natives-android-arm64.jar"),
                "94a676eafae145a385622554343116580b0a1fcd2c869b5213b76993f335c886"),
            AndroidNativesAsset("nexira-host-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.2.2", "nexira-host-natives-android-arm64.jar"),
                "057ac35a2bbfb5cb6171906441e4aa255ed285e6b04d33b775624f05b810a980"),
    )),
    AndroidNativesRelease("3.3.1", listOf(
            AndroidNativesAsset("lwjgl-3.3.1-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.3.1", "lwjgl-3.3.1-natives-android-arm64.jar"),
                "05f0b4147992775a19db7ae547a6ec4a0832aaba6b665923a10ca1ab9af50366"),
            AndroidNativesAsset("nexira-host-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.3.1", "nexira-host-natives-android-arm64.jar"),
                "b06cf315373b307fadbd7c7305652f069f3b324df4502a59a63a1bcc57e112b1"),
    )),
    AndroidNativesRelease("3.3.2", listOf(
            AndroidNativesAsset("lwjgl-3.3.2-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.3.2", "lwjgl-3.3.2-natives-android-arm64.jar"),
                "1761615eecb0452ba6929f92aaa01dcc6fd25e7c994039f5332b65747aebe564"),
            AndroidNativesAsset("nexira-host-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.3.2", "nexira-host-natives-android-arm64.jar"),
                "3ccb46e0502933ca83618029da72a1054c2268924f8148b726e41f6802078553"),
    )),
    AndroidNativesRelease("3.3.3", listOf(
            AndroidNativesAsset("lwjgl-3.3.3-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.3.3", "lwjgl-3.3.3-natives-android-arm64.jar"),
                "d0d13fea9f9055ef373ed1326529b107b7784300cc8edfeb33dcc229106e4be5"),
            AndroidNativesAsset("nexira-host-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.3.3", "nexira-host-natives-android-arm64.jar"),
                "dae7f1bdbb6595582d5ec922a286831d627bbc964dab8c055a70acb0cb44dde8"),
    )),
    AndroidNativesRelease("3.4.1", listOf(
            AndroidNativesAsset("lwjgl-3.4.1-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.4.1", "lwjgl-3.4.1-natives-android-arm64.jar"),
                "3779f149063f8830dbe9bce28eab9fc9c10c6f6a75f1ec56f8111ac17f69d956"),
            AndroidNativesAsset("nexira-host-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.4.1", "nexira-host-natives-android-arm64.jar"),
                "e98cd66a94fa8a22764845a7b67ddb6bfb8327ed54f11ceb85fe6b8d8b255f4d"),
    )),
    AndroidNativesRelease("3.4.3", listOf(
            AndroidNativesAsset("lwjgl-3.4.3-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.4.3", "lwjgl-3.4.3-natives-android-arm64.jar"),
                "e7e2b31cb88d41edfa182985f00debc98afc0a2ec81e5bc2844eb84277b72fba"),
            AndroidNativesAsset("nexira-host-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.4.3", "nexira-host-natives-android-arm64.jar"),
                "729dcf20c2319d16a1eeb3e71f00353b75472b2e3b0abf09c71f755d336b9c31"),
    )),
)
