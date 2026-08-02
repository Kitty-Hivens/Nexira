package hivens.core.api.interfaces

import hivens.core.data.SessionData
import hivens.core.launch.SyncProgress
import java.nio.file.Path

@Deprecated(
    "Deprecated since 2.4.0; removed in 2.5.0 at the latest. The SmartyCraft server list is being retired: a pack is the unit of content, and the raw-server path duplicates install, sync and launch with an older, weaker set of guarantees (see #318). Do not build on it -- nothing new should reach clients/.",
    level = DeprecationLevel.WARNING,
)
interface IFileDownloadService {
    /**
     * Parses the manifest, downloads files, unpacks `extra.zip`.
     *
     * [progressUI]: per-tick [SyncProgress] (raw counters + bytes/sec rate).
     * [verifyUI]: `(verifiedCount, totalCount)` fired during the MD5
     *   integrity walk *before* downloads. Keeps the UI bar moving while
     *   hashing a 1000-file modpack -- otherwise the user sees the
     *   launcher silent for tens of seconds and assumes a hang.
     * [injectModJar]: an open-smrt-network helper jar copied into `mods/`
     *   in place of the upstream Smarty coremod (whose manifest name the
     *   caller adds to [ignoredFiles]). Null leaves the manifest untouched.
     * [strictModCheck]: when true, deletes every jar in `mods/` the manifest
     *   does not list ([injectModJar] and [helperKeepGlobs] excepted) -- "only
     *   what the server asks for runs".
     * [helperKeepGlobs]: jar-name globs strict verification must always keep
     *   (the open-smrt helper), even when [injectModJar] is null this launch.
     */
    suspend fun processSession(
        session: SessionData,
        serverId: String,
        targetDir: Path,
        extraCheckSum: String?,
        ignoredFiles: Set<String>?,
        messageUI: ((String) -> Unit)?,
        progressUI: ((SyncProgress) -> Unit)?,
        verifyUI: ((Int, Int) -> Unit)? = null,
        injectModJar: Path? = null,
        strictModCheck: Boolean = false,
        helperKeepGlobs: List<String> = emptyList(),
    )
}
