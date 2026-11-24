package hivens.ui.pictures

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.imageio.ImageIO

/** A square PNG of one colour, [edge] pixels a side. */
internal fun png(rgb: Int, edge: Int = 8): ByteArray {
    val image = BufferedImage(edge, edge, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until edge) for (y in 0 until edge) image.setRGB(x, y, rgb)
    return ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}

/** A picture server on this machine. What it answers with is [answer]'s, and what it was asked is [asked]. */
internal class PictureServer : AutoCloseable {
    /** One request: the conditional tag it carried, if it carried one. */
    class Asked(val path: String, val tag: String?)

    val asked = CopyOnWriteArrayList<Asked>()

    @Volatile
    var answer: (HttpExchange) -> Unit = { it.send(200, png(RED)) }

    private val pool = Executors.newCachedThreadPool()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/") { exchange ->
            asked += Asked(exchange.requestURI.path, exchange.requestHeaders.getFirst("If-None-Match"))
            exchange.use { answer(it) }
        }
        executor = pool
        start()
    }

    fun url(path: String = "/banner.png"): String = "http://127.0.0.1:${server.address.port}$path"

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }

    companion object {
        const val RED = 0xFF0000
        const val BLUE = 0x0000FF

        fun HttpExchange.send(code: Int, body: ByteArray? = null, vararg headers: Pair<String, String>) {
            headers.forEach { (name, value) -> responseHeaders.set(name, value) }
            responseHeaders.set("Content-Type", "image/png")
            if (body == null) {
                sendResponseHeaders(code, -1)
            } else {
                sendResponseHeaders(code, body.size.toLong())
                responseBody.write(body)
            }
        }
    }
}
