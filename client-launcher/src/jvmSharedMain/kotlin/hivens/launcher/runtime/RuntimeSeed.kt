package hivens.launcher.runtime

import java.nio.file.Path

/**
 * A runtime already on disk elsewhere, offered to [RuntimeProvisioner] as a
 * source of files it would otherwise download: another launcher's `.minecraft`.
 *
 * Offered, not trusted. A file is taken only when its bytes match the sha1 the
 * provisioner already holds for that path from Mojang's manifest or the loader's
 * profile, and anything that does not match is downloaded as if it were absent.
 * Adopting on presence alone put foreign bytes into the shared roots that the
 * size-only skip then never looked at again, for every pack of every version.
 * An asset object's name is its own digest, but a library sits at a maven
 * coordinate, which is a path anything can have written to.
 */
data class RuntimeSeed(
    /** A `libraries` tree laid out like the shared one. */
    val librariesDir: Path? = null,
    /** An `assets` tree laid out like the shared one. */
    val assetsDir: Path? = null,
    /** The vanilla client jar, which the shared root keeps at a maven coordinate instead. */
    val clientJar: Path? = null,
)
