package hivens.launcher.runtime

/**
 * The natives for Android this launcher was released with, one release per LWJGL version.
 * Written by scripts/pin-android-natives.py from the releases of the build repository.
 */
internal val ANDROID_NATIVES_PINS: List<AndroidNativesRelease> = listOf(
    AndroidNativesRelease("3.2.1", listOf(
            AndroidNativesAsset("lwjgl-3.2.1-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.2.1", "lwjgl-3.2.1-natives-android-arm64.jar"),
                "c672590840e91c79c177e5fe37265a43bc1f0aa3b7b8d83fb16f20f8caedbb36"),
            AndroidNativesAsset("nexira-host-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.2.1", "nexira-host-natives-android-arm64.jar"),
                "c3a43851e739daf653323ff450ce59eccfad2594f725955de444ab1fdf47e12e"),
    )),
    AndroidNativesRelease("3.2.2", listOf(
            AndroidNativesAsset("lwjgl-3.2.2-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.2.2", "lwjgl-3.2.2-natives-android-arm64.jar"),
                "156ee6de998fbfdd401bcf64ad330782f50b476fcc6144abb9f924e335a59cf9"),
            AndroidNativesAsset("nexira-host-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.2.2", "nexira-host-natives-android-arm64.jar"),
                "8928d6060c86d5ba1f9a6fdb86f7405bb06e1770191596b03b22d5295964d4c4"),
    )),
    AndroidNativesRelease("3.3.1", listOf(
            AndroidNativesAsset("lwjgl-3.3.1-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.3.1", "lwjgl-3.3.1-natives-android-arm64.jar"),
                "e36c2864706e46b5b01ef323b9ae2422e534bda4036633f19a4882e7046d30f0"),
            AndroidNativesAsset("nexira-host-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.3.1", "nexira-host-natives-android-arm64.jar"),
                "1bb8c14a204640cf3b7a79ae30a00346b717405fc744df5c350c4c7bd8976f16"),
    )),
    AndroidNativesRelease("3.3.2", listOf(
            AndroidNativesAsset("lwjgl-3.3.2-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.3.2", "lwjgl-3.3.2-natives-android-arm64.jar"),
                "79ac310497f7d4caaa5dd257bdfd03967c7bcddd0cbc1fec9782052871e15eba"),
            AndroidNativesAsset("nexira-host-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.3.2", "nexira-host-natives-android-arm64.jar"),
                "ace0cdc4983d0bb5f3d33f73c897c0808cd1a155969658f937a728fd546f094a"),
    )),
    AndroidNativesRelease("3.3.3", listOf(
            AndroidNativesAsset("lwjgl-3.3.3-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.3.3", "lwjgl-3.3.3-natives-android-arm64.jar"),
                "054c9e26da22e84db7838bd1589a33aa01dd27a8a6768afd059ef8125a0a801b"),
            AndroidNativesAsset("nexira-host-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.3.3", "nexira-host-natives-android-arm64.jar"),
                "66c65bea0f9e2597a49ba3c62f964b56073ec80d4e2539faa0490bb91239a823"),
    )),
    AndroidNativesRelease("3.4.1", listOf(
            AndroidNativesAsset("lwjgl-3.4.1-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.4.1", "lwjgl-3.4.1-natives-android-arm64.jar"),
                "7ca0598a2b25203593c850ecc97a80a559dd7de25de3f14effd986efd29e2a30"),
            AndroidNativesAsset("nexira-host-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.4.1", "nexira-host-natives-android-arm64.jar"),
                "aa041b754e0caf4b03eb22b5462c3bbed3697c566478c09026500794e35df7b8"),
    )),
    AndroidNativesRelease("3.4.3", listOf(
            AndroidNativesAsset("lwjgl-3.4.3-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.4.3", "lwjgl-3.4.3-natives-android-arm64.jar"),
                "6cfb08c2acd67308eb6eef95fb6158ff69d2d2d0677812ae0a767a5878701a0f"),
            AndroidNativesAsset("nexira-host-natives-android-arm64.jar", AndroidNativesCatalog.assetUrl("3.4.3", "nexira-host-natives-android-arm64.jar"),
                "e38cc4c3ad93e81261d29cc6546f0ee22de51c3b6e1ef14827d3fe35f71710ce"),
    )),
)
