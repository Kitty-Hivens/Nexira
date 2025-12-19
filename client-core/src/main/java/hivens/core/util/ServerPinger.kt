package hivens.core.util

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

object ServerPinger {  // TODO: Не используется. Проверить связи.

    data class ServerStatus(
        val description: String,
        val online: Int,
        val max: Int,
        val ping: Long
    )

    fun ping(ip: String, port: Int): ServerStatus {
        val start = System.currentTimeMillis()
        try {
            Socket().use { socket ->
                socket.soTimeout = 3000 // Тайм-аут 3 секунды
                socket.connect(InetSocketAddress(ip, port), 3000)

                val outStream = DataOutputStream(socket.getOutputStream())
                val inStream = DataInputStream(socket.getInputStream())

                // 1. Handshake packet
                val b = ByteArrayOutputStream()
                val handshake = DataOutputStream(b)
                handshake.writeByte(0x00) // Packet ID
                writeVarInt(handshake, 47) // Protocol Version (1.8+)
                writeString(handshake, ip)
                handshake.writeShort(port)
                writeVarInt(handshake, 1) // State 1 (Status)

                writeVarInt(outStream, b.size())
                outStream.write(b.toByteArray())

                // 2. Request packet
                outStream.writeByte(0x01) // Size
                outStream.writeByte(0x00) // Packet ID

                // 3. Response
                readVarInt(inStream) // Packet size
                val packetId = readVarInt(inStream)

                if (packetId == -1) throw IOException("Premature end of stream")
                if (packetId != 0x00) throw IOException("Invalid packetID")

                val jsonLength = readVarInt(inStream)
                if (jsonLength == -1) throw IOException("Premature end of stream")

                val inBytes = ByteArray(jsonLength)
                inStream.readFully(inBytes)
                val json = String(inBytes, StandardCharsets.UTF_8)

                val pingTime = System.currentTimeMillis() - start

                // Парсим JSON
                val gson = Gson()
                val root = gson.fromJson(json, JsonObject::class.java)

                var motd = "Сервер онлайн"
                if (root.has("description")) {
                    motd = if (root.get("description").isJsonObject) {
                        root.getAsJsonObject("description").get("text").asString
                    } else {
                        root.get("description").asString
                    }
                }

                var online = 0
                var max = 0
                if (root.has("players")) {
                    val players = root.getAsJsonObject("players")
                    online = players.get("online").asInt
                    max = players.get("max").asInt
                }

                return ServerStatus(motd, online, max, pingTime)
            }
        } catch (e: Exception) {
            // Сервер офлайн
            return ServerStatus("Офлайн", 0, 0, -1)
        }
    }

    // Утилиты для протокола Minecraft

    private fun writeVarInt(out: DataOutputStream, value: Int) {
        var paramInt = value
        while (true) {
            if ((paramInt and 0xFFFFFF80.toInt()) == 0) {
                out.writeByte(paramInt)
                return
            }
            out.writeByte(paramInt and 0x7F or 0x80)
            paramInt = paramInt ushr 7
        }
    }

    private fun writeString(out: DataOutputStream, string: String) {
        val bytes = string.toByteArray(StandardCharsets.UTF_8)
        writeVarInt(out, bytes.size)
        out.write(bytes)
    }

    private fun readVarInt(`in`: DataInputStream): Int {
        var i = 0
        var j = 0
        while (true) {
            val k = `in`.readByte().toInt()
            i = i or (k and 0x7F shl j++ * 7)
            if (j > 5) throw RuntimeException("VarInt too big")
            if (k and 0x80 != 128) break
        }
        return i
    }
}
