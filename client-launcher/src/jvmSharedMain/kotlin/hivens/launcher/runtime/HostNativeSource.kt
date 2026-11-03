package hivens.launcher.runtime

import java.nio.file.Path

/**
 * Where a host gets the LWJGL natives that Mojang's manifest cannot give it.
 *
 * Mojang's `natives-linux*` jars are built against glibc, so a host on another C library (Android's bionic, musl)
 * cannot load them. Such a host supplies its own, built from the very LWJGL release the game's Java classes come
 * from, because the native glue has to match them exactly.
 */
fun interface HostNativeSource {
    /**
     * The jars to unpack into the natives directory for [lwjglVersion], fetched if need be. Empty when this host has
     * none for that release, which the launch then reports as the game having no natives. [onProgress] is told what
     * is happening while a download runs.
     */
    suspend fun natives(lwjglVersion: String, onProgress: (String) -> Unit): List<Path>
}
