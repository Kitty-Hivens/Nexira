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
 * Localhost HTTP server that exposes [PuppetRegistry] over a small JSON
 * API, allowing external scripts (curl, automated UI test harnesses)
 * to drive the Compose UI semantically — querying the current screen,
 * clicking buttons, filling fields, toggling switches.
 *
 * **Strictly opt-in.** Only binds when `-Daura.puppet.port=N` is set
 * at JVM launch. Without the flag, [start] is a no-op and no port is
 * occupied; production builds therefore cannot accidentally expose
 * the surface even with the classes present in the JAR.
 *
 * Bind is hardcoded to `127.0.0.1` — no remote access, no auth, the
 * threat model assumes a trusted developer workstation. If you need
 * authentication beyond "only local processes can connect", that's a
 * separate iteration.
 *
 * **Threading.** Ktor handlers run on Dispatchers.IO by default;
 * calls into [PuppetRegistry.click] / [setField] / [setToggle]
 * mutate Compose `mutableStateOf` values, which MUST happen on the
 * AWT EDT (Compose Desktop's UI thread). We hop via
 * `withContext(Dispatchers.Swing)` before invoking the registry's
 * mutating methods. Reads (snapshot, screen) are safe off-thread
 * because the registry is backed by [java.util.concurrent.ConcurrentHashMap]
 * and the values it reads (mutableState getters) tolerate concurrent
 * reads.
 *
 * Endpoints:
 *   * `GET  /screen`     → { screen: String }
 *   * `GET  /elements`   → { screen: String, elements: [PuppetElement] }
 *   * `POST /click`      ← { id: String }                  → { ok: true } | 404
 *   * `POST /setField`   ← { id: String, value: String }   → { ok: true } | 404
 *   * `POST /setToggle`  ← { id: String, value: Boolean }  → { ok: true } | 404
 */
internal object PuppetServer {

    private val log = LoggerFactory.getLogger(PuppetServer::class.java)

    @Volatile
    private var server: EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration>? = null

    /**
     * Start the puppet HTTP server if `-Daura.puppet.port=N` is set.
     * Idempotent — a second call when already running is a no-op.
     */
    fun startIfRequested() {
        if (server != null) return
        val portProp = System.getProperty("aura.puppet.port") ?: return
        val port = portProp.toIntOrNull() ?: run {
            log.warn("aura.puppet.port='{}' is not an integer — puppet mode disabled", portProp)
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
                "PUPPET MODE ACTIVE — HTTP control surface on http://127.0.0.1:{}. " +
                "Do not enable in production.",
                port,
            )
        } catch (t: Throwable) {
            log.error("Puppet HTTP server failed to start on port {}: {}", port, t.message)
        }
    }

    fun stop() {
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
