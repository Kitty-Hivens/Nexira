package hivens.accounts

import hivens.accounts.api.SkinUpload
import hivens.accounts.api.SkinUploadStatus
import hivens.accounts.api.SkinUploadView
import hivens.core.data.SessionData
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** Skins put on an account by the accounts module, signed with a session no widget holds. */
class SkinUploadsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private val store = MemoryAccounts()
    private val signedWith = CopyOnWriteArrayList<String>()
    private val release = CompletableDeferred<Unit>()

    private fun uploads(signedIn: SessionData? = null, hold: Boolean = false) = SkinUploads(
        send = { _, _, session ->
            signedWith += session.playerName
            if (hold) release.await()
            "OK"
        },
        accounts = store,
        signedIn = { signedIn },
        scope = scope,
    )

    private fun SkinUploads.ended(provider: String, ticket: String): SkinUploadView = runBlocking {
        withTimeout(5.seconds) {
            uploads.first { it[provider]?.let { v -> v.ticket == ticket && v.status != SkinUploadStatus.Uploading } == true }
        }.getValue(provider)
    }

    @Test
    fun `an upload is signed with the stored account of its provider`() {
        store.byProvider["smartycraft"] = SessionData(playerName = "Steve", accessToken = "token-token-token")
        val skins = uploads()
        skins.upload(SkinUpload("smartycraft", "/tmp/skin.png", ticket = "a"))
        assertEquals(SkinUploadStatus.Done, skins.ended("smartycraft", "a").status)
        assertEquals(listOf("Steve"), signedWith.toList())
    }

    // A sign-in the person did not keep has no stored account to sign with.
    @Test
    fun `an upload as the signed-in session needs no stored account`() {
        val skins = uploads(signedIn = SessionData(playerName = "Alex", accessToken = "token-token-token"))
        skins.upload(SkinUpload("smartycraft", "/tmp/skin.png", ticket = "b", asSignedIn = true))
        assertEquals(SkinUploadStatus.Done, skins.ended("smartycraft", "b").status)
        assertEquals(listOf("Alex"), signedWith.toList())

        skins.upload(SkinUpload("smartycraft", "/tmp/skin.png", ticket = "c"))
        assertEquals(SkinUploadStatus.Failed(null), skins.ended("smartycraft", "c").status, "without one, a stored upload has nothing to sign with")
    }

    @Test
    fun `two uploads to one account asked at once send one`() {
        store.byProvider["smartycraft"] = SessionData(playerName = "Steve", accessToken = "token-token-token")
        val skins = uploads(hold = true)
        val start = CountDownLatch(1)
        val threads = (1..8).map { n ->
            thread {
                start.await(5, TimeUnit.SECONDS)
                skins.upload(SkinUpload("smartycraft", "/tmp/skin.png", ticket = "t$n"))
            }
        }
        start.countDown()
        threads.forEach { it.join(5_000) }
        release.complete(Unit)
        val ticket = skins.uploads.value.getValue("smartycraft").ticket
        assertEquals(SkinUploadStatus.Done, skins.ended("smartycraft", ticket).status)
        assertEquals(1, signedWith.size, "more than one upload was sent")
    }
}
