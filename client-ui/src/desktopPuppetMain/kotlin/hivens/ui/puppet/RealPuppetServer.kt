package hivens.ui.puppet

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

/**
 * Real [PuppetServerLifecycle] -- localhost HTTP server exposing
 * [PuppetRegistry] over a small JSON API so external scripts (curl,
 * automated UI test harnesses) can drive the Compose UI semantically.
 *
 * **Lives in the `desktopPuppetMain` source dir** which is only added
 * to the desktop compilation when `-PauraPuppetPort=N` is on the
 * Gradle command line. Production builds therefore do NOT contain
 * this class or the Ktor server classes it depends on; the
 * `META-INF/services/hivens.ui.puppet.PuppetServerLifecycle` descriptor
 * that points ServiceLoader here is similarly only packaged for
 * puppet-enabled builds. See [PuppetServerLifecycle] for the full
 * security-boundary rationale.
 *
 * **Strictly opt-in at runtime too.** Even when this class IS on the
 * classpath, [startIfRequested] only binds when `-Daura.puppet.port=N`
 * is set at JVM launch. Without the system property, this is a no-op.
 *
 * Bind is hardcoded to `127.0.0.1` -- no remote access, no auth, the
 * threat model assumes a trusted developer workstation.
 *
 * **Threading.** Ktor handlers run on Dispatchers.IO by default; calls
 * into [PuppetRegistry.click] / [setField] / [setToggle] mutate Compose
 * `mutableStateOf` values, which MUST happen on the AWT EDT (Compose
 * Desktop's UI thread). We hop via `withContext(Dispatchers.Swing)`
 * before invoking the registry's mutating methods. Reads (snapshot,
 * screen) are safe off-thread because the registry is backed by
 * [java.util.concurrent.ConcurrentHashMap].
 *
 * Endpoints:
 *   * `GET  /screen`     -> { screen: String }
 *   * `GET  /elements`   -> { screen: String, elements: [PuppetElement] }
 *   * `POST /click`      <- { id: String }                  -> { ok: true } | 404
 *   * `POST /setField`   <- { id: String, value: String }   -> { ok: true } | 404
 *   * `POST /setToggle`  <- { id: String, value: Boolean }  -> { ok: true } | 404
 *
 * **Concrete class, public no-arg constructor**: ServiceLoader requires
 * both. Singleton-by-convention via the [PuppetServerLoader.instance]
 * lazy that caches the first SL-returned provider for the JVM lifetime.
 */
class RealPuppetServer : PuppetServerLifecycle {

    private val log = LoggerFactory.getLogger(RealPuppetServer::class.java)

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    override fun startIfRequested() {
        if (server != null) return
        val portProp = System.getProperty("aura.puppet.port") ?: return
        val port = portProp.toIntOrNull() ?: run {
            log.warn("aura.puppet.port='{}' is not an integer -- puppet mode disabled", portProp)
            return
        }

        try {
            val s = embeddedServer(CIO, port = port, host = "127.0.0.1") {
                install(ContentNegotiation) { json() }
                routing {
                    get("/screen") {
                        call.respond(ScreenResponse(PuppetRegistry.snapshot().screen))
                    }
                    get("/elements") {
                        call.respond(PuppetRegistry.snapshot())
                    }
                    post("/click") {
                        val req = call.receive<ClickRequest>()
                        val result = withContext(Dispatchers.Swing) {
                            PuppetRegistry.click(req.id)
                        }
                        replyResult(result)
                    }
                    post("/setField") {
                        val req = call.receive<SetFieldRequest>()
                        val result = withContext(Dispatchers.Swing) {
                            PuppetRegistry.setField(req.id, req.value)
                        }
                        replyResult(result)
                    }
                    post("/setToggle") {
                        val req = call.receive<SetToggleRequest>()
                        val result = withContext(Dispatchers.Swing) {
                            PuppetRegistry.setToggle(req.id, req.value)
                        }
                        replyResult(result)
                    }
                }
            }
            s.start(wait = false)
            server = s
            log.warn(
                "PUPPET MODE ACTIVE -- HTTP control surface on http://127.0.0.1:{}. " +
                "Do not enable in production.",
                port,
            )
        } catch (t: Throwable) {
            log.error("Puppet HTTP server failed to start on port {}: {}", port, t.message)
        }
    }

    override fun stop() {
        server?.stop(gracePeriodMillis = 100, timeoutMillis = 500)
        server = null
    }

    private suspend fun io.ktor.server.routing.RoutingContext.replyResult(result: Result<Unit>) {
        result.fold(
            onSuccess = { call.respond(PuppetOk()) },
            onFailure = { t ->
                val code = when (t) {
                    is NoSuchElementException -> HttpStatusCode.NotFound
                    is IllegalStateException  -> HttpStatusCode.Conflict
                    else                      -> HttpStatusCode.InternalServerError
                }
                call.respond(code, PuppetError(t.message ?: t::class.simpleName ?: "unknown error"))
            },
        )
    }
}
