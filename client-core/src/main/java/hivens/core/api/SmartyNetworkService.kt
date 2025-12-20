package hivens.core.api

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import hivens.config.ServiceEndpoints
import hivens.core.api.dto.SmartyResponse
import hivens.core.data.SessionData
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class SmartyNetworkService(baseClient: OkHttpClient, private val gson: Gson) {

    private val logger = LoggerFactory.getLogger(SmartyNetworkService::class.java)
    private val cachedHashFile = File("smarty_hash.cache")
    private var currentHash = "5515a4bdd5f532faf0db61b8263d1952"

    private val client: OkHttpClient = baseClient.newBuilder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(ServiceEndpoints.PROXY_HOST, ServiceEndpoints.PROXY_PORT)))
        .proxyAuthenticator { _, response ->
            val credential = Credentials.basic("proxyuser", "proxyuserproxyuser")
            response.request.newBuilder()
                .header("Proxy-Authorization", credential)
                .build()
        }
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    init {
        if (cachedHashFile.exists()) {
            try {
                currentHash = Files.readString(cachedHashFile.toPath()).trim()
            } catch (_: IOException) { }
        }
    }

    /**
     * Получает данные для Дашборда (Серверы + Новости).
     */
    fun getDashboardResponse(): SmartyResponse {
        var response = tryGetDashboard(currentHash)
        if (response.status == "UPDATE") {
            val newHash = downloadAndCalculateHash()
            if (newHash != null) {
                currentHash = newHash
                saveHashToCache(newHash)
                response = tryGetDashboard(newHash)
            }
        }
        return response
    }

    /**
     * Загрузка скина с эмуляцией протокола Scold.
     */
    fun uploadAsset(file: File, type: String, session: SessionData): String {
        val action = if (type == "cloak") "cloakupload" else "skinupload"
        val fileField = if (type == "cloak") "cloak" else "skin"

        val mediaType = "image/png".toMediaTypeOrNull()
        val fileBody = file.asRequestBody(mediaType)

        // 1. JSON с логином
        val jsonPayload = gson.toJson(mapOf("login" to session.playerName))

        // 2. Подпись: MD5( (time/10) + "|" + uid + "|" + login )
        val timestamp = System.currentTimeMillis() / 1000L / 10L
        val signString = "$timestamp|${session.uid}|${session.playerName}"
        val checkHash = getMD5(signString)

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("action", action)
            .addFormDataPart("json", jsonPayload)
            .addFormDataPart("check", checkHash)
            .addFormDataPart(fileField, file.name, fileBody)
            .build()

        val request = Request.Builder()
            .url(ServiceEndpoints.AUTH_LOGIN)
            .post(requestBody)
            .header("User-Agent", "SMARTYlauncher/3.6.2")
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string()?.trim()
                println(">>> UPLOAD RESPONSE (${response.code}): '$body'")

                if (response.code == 500) {
                    return "Ошибка: Неподдерживаемый формат"
                }

                if (!response.isSuccessful) return "Ошибка сети: ${response.code}"

                if (body != null && body.startsWith("{")) {
                    try {
                        val respObj = gson.fromJson(body, SmartyResponse::class.java)
                        if (respObj.status == "OK") return "OK"
                        return when (respObj.status) {
                            "SIZE" -> "Ошибка: Неверный размер (нужен 64x32/64x64)"
                            "TYPE" -> "Ошибка: Неверный формат файла"
                            "HD" -> "Ошибка: HD скины доступны только премиумам"
                            else -> "Ошибка сервера: ${respObj.status}"
                        }
                    } catch (_: Exception) {}
                }

                if (body.isNullOrEmpty() || body.contains("OK")) return "OK"

                return body
            }
        } catch (e: Exception) {
            logger.error("Upload asset failed", e)
            "Ошибка: ${e.message}"
        }
    }

    private fun getMD5(input: String): String {
        return try {
            val md = MessageDigest.getInstance("MD5")
            val hash = md.digest(input.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) { "" }
    }

    private fun tryGetDashboard(hash: String): SmartyResponse {
        val jsonBody = """
            {
                "version": "3.6.2",
                "cheksum": "$hash",
                "format": "jar",
                "testModeKey": "false",
                "debug": "false"
            }
        """.trimIndent()

        val formBody = FormBody.Builder()
            .add("action", "loader")
            .add("json", jsonBody)
            .build()

        val request = Request.Builder()
            .url(ServiceEndpoints.AUTH_LOGIN)
            .post(formBody)
            .header("User-Agent", "SMARTYlauncher/3.6.2")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return SmartyResponse()
                var rawJson = response.body?.string() ?: return SmartyResponse()
                val jsonStart = rawJson.indexOf("{")
                if (jsonStart != -1) rawJson = rawJson.substring(jsonStart)
                return try {
                    gson.fromJson(rawJson, SmartyResponse::class.java)
                } catch (e: JsonSyntaxException) {
                    SmartyResponse()
                }
            }
        } catch (e: Exception) {
            return SmartyResponse()
        }
    }

    private fun downloadAndCalculateHash(): String? {
        try {
            val tempJar = Files.createTempFile("smarty_temp", ".jar")
            val request = Request.Builder()
                .url(ServiceEndpoints.OFFICIAL_JAR_URL)
                .header("User-Agent", "SMARTYlauncher/3.6.2")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.byteStream()?.use { input ->
                    Files.copy(input, tempJar, StandardCopyOption.REPLACE_EXISTING)
                }
            }
            val digest = MessageDigest.getInstance("MD5")
            Files.newInputStream(tempJar).use { fis ->
                val buffer = ByteArray(1024)
                var bytesCount: Int
                while (fis.read(buffer).also { bytesCount = it } != -1) digest.update(buffer, 0, bytesCount)
            }
            Files.deleteIfExists(tempJar)
            val bytes = digest.digest()
            val sb = StringBuilder()
            for (b in bytes) sb.append(((b.toInt() and 0xff) + 0x100).toString(16).substring(1))
            return sb.toString()
        } catch (e: Exception) { return null }
    }

    private fun saveHashToCache(hash: String) {
        try { Files.writeString(cachedHashFile.toPath(), hash) } catch (_: IOException) {}
    }
}
