package dev.hivens.nexira

import android.content.Context
import android.util.Log
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.launcher.XodusPackRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Opens the pack registry the way the launcher does, on the phone, writes an instance,
 * closes the database and opens it again to read the instance back.
 *
 * Xodus was chosen on the desktop and had never run on ART; this is the check.
 */
object XodusProbe {

    private const val TAG = "NexiraProbe"

    fun run(context: Context) {
        val root = File(context.filesDir, "xodus-probe").apply { deleteRecursively(); mkdirs() }
        val json = Json { ignoreUnknownKeys = true }
        val instance = PackInstance(
            id = "00000000-0000-0000-0000-000000000001",
            packRef = PackReference(origin = PackOrigin.Local, id = "probe"),
            displayName = "Probe pack",
            instanceDirName = "probe-1",
            createdAtEpoch = 1L,
        )
        val started = System.nanoTime()
        runBlocking {
            val first = XodusPackRepository(root.toPath().resolve("db"), root.toPath().resolve("packs.json"), json, holdOpen = false)
            first.put(instance)
            Log.i(TAG, "xodus wrote: ${first.list().size} instance(s)")

            val second = XodusPackRepository(root.toPath().resolve("db"), root.toPath().resolve("packs.json"), json, holdOpen = false)
            val back = second.get(instance.id)
            Log.i(TAG, "xodus reopened: name=${back?.displayName} dirName=${back?.instanceDirName} equal=${back == instance}")
        }
        Log.i(TAG, "xodus done in ${(System.nanoTime() - started) / 1_000_000} ms")
    }
}
