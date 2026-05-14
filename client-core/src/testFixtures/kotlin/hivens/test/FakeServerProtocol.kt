package hivens.test

import hivens.core.api.interfaces.IServerProtocol
import hivens.core.api.protocol.LoaderResponse
import hivens.core.api.protocol.LoginRequest
import hivens.core.api.protocol.LoginResponse
import hivens.core.api.protocol.StatusOnlyResponse

/**
 * Hand-rolled fake [IServerProtocol] for unit tests.
 *
 * Each method returns whatever the corresponding `*Result` lambda is set to,
 * defaulting to a sensible OK shape. Recorded inputs are kept on each `*Calls`
 * list so tests can assert "was login() called with this username".
 *
 * Prefer this over mockk for protocol-level testing: explicit, debuggable,
 * survives Kotlin / mockk version drift, no inline-mock-agent gymnastics.
 *
 * ## Usage
 *
 * ```kotlin
 * val protocol = FakeServerProtocol().apply {
 *     loaderResult = { LoaderResponse(status = "OK", servers = listOf(...)) }
 *     loginResult = { req -> LoginResponse(status = "OK", playername = req.login, ...) }
 * }
 * val repo = ServerRepository(protocol)
 * val dashboard = repo.fetchDashboard()
 * assertEquals(1, protocol.loaderCalls.size)
 * ```
 */
class FakeServerProtocol : IServerProtocol {

    var loaderResult: () -> LoaderResponse = { LoaderResponse(status = "OK") }
    var loginResult: (LoginRequest) -> LoginResponse = { req ->
        LoginResponse(
            status = "OK",
            playername = req.login,
            uid = "fake-uid-${req.login}",
            uuid = "00000000-0000-0000-0000-000000000000",
            session = "ZmFrZS1zZXNzaW9uLWJ5dGVz",
        )
    }
    var spawnResult: (String, String, String) -> StatusOnlyResponse = { _, _, _ ->
        StatusOnlyResponse(status = "OK")
    }
    var twoauthResult: (String, String, String) -> StatusOnlyResponse = { _, _, _ ->
        StatusOnlyResponse(status = "OK")
    }
    var uploadSkinResult: (String, String, ByteArray) -> StatusOnlyResponse = { _, _, _ ->
        StatusOnlyResponse(status = "OK")
    }
    var uploadCloakResult: (String, String, ByteArray) -> StatusOnlyResponse = { _, _, _ ->
        StatusOnlyResponse(status = "OK")
    }

    val loaderCalls = mutableListOf<Unit>()
    val loginCalls = mutableListOf<LoginRequest>()
    val spawnCalls = mutableListOf<Triple<String, String, String>>()
    val twoauthCalls = mutableListOf<Triple<String, String, String>>()
    val uploadSkinCalls = mutableListOf<Triple<String, String, ByteArray>>()
    val uploadCloakCalls = mutableListOf<Triple<String, String, ByteArray>>()

    override suspend fun loader(): LoaderResponse {
        loaderCalls += Unit
        return loaderResult()
    }

    override suspend fun login(request: LoginRequest): LoginResponse {
        loginCalls += request
        return loginResult(request)
    }

    override suspend fun spawn(uid: String, login: String, server: String): StatusOnlyResponse {
        spawnCalls += Triple(uid, login, server)
        return spawnResult(uid, login, server)
    }

    override suspend fun twoauth(uid: String, login: String, code: String): StatusOnlyResponse {
        twoauthCalls += Triple(uid, login, code)
        return twoauthResult(uid, login, code)
    }

    override suspend fun uploadSkin(uid: String, login: String, png: ByteArray): StatusOnlyResponse {
        uploadSkinCalls += Triple(uid, login, png)
        return uploadSkinResult(uid, login, png)
    }

    override suspend fun uploadCloak(uid: String, login: String, png: ByteArray): StatusOnlyResponse {
        uploadCloakCalls += Triple(uid, login, png)
        return uploadCloakResult(uid, login, png)
    }
}
