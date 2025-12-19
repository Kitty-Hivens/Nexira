package hivens.core.api

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import hivens.config.ServiceEndpoints
import hivens.core.api.dto.SmartyResponse
import hivens.core.api.dto.SmartyServer
import hivens.core.api.interfaces.IServerListService
import hivens.core.api.model.ServerProfile
import okhttp3.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class SmartyNetworkService(baseClient: OkHttpClient, private val gson: Gson) : IServerListService {

    private val logger = LoggerFactory.getLogger(SmartyNetworkService::class.java)
    private val officialJarUrl = "https://www.smartycraft.ru/downloads/smartycraft.jar"
    private val cachedHashFile = File("smarty_hash.cache")
    private var currentHash = "5515a4bdd5f532faf0db61b8263d1952"

    // Настраиваем клиент с SOCKS прокси
    private val client: OkHttpClient = baseClient.newBuilder()
        .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("proxy.smartycraft.ru", 1080)))
        .proxyAuthenticator { _, response ->
            val credential = Credentials.basic("proxyuser", "proxyuserproxyuser")
            response.request.newBuilder()
                .header("Proxy-Authorization", credential)
                .build()
        }
        // Увеличенные таймауты для скачивания JAR
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    init {
        // Пытаемся загрузить кэшированный хеш с диска
        if (cachedHashFile.exists()) {
            try {
                currentHash = Files.readString(cachedHashFile.toPath()).trim()
                logger.info("Loaded cached hash: $currentHash")
            } catch (e: IOException) {
                logger.warn("Failed to read hash cache", e)
            }
        }
    }

    override fun fetchProfiles(): CompletableFuture<List<ServerProfile>> {
        return CompletableFuture.supplyAsync {
            val smartyServers = getServers()

            smartyServers.map { s ->
                ServerProfile(
                    name = s.name ?: "Unknown",
                    version = s.version ?: "",
                    ip = s.address ?: "",
                    port = s.port
                ).apply {
                    extraCheckSum = s.extraCheckSum
                    optionalModsData = s.optionalMods
                }
            }
        }
    }

    fun getServers(): List<SmartyServer> {
        return try {
            // Попытка 1: С текущим хешем
            var servers = tryGetServers(currentHash)

            if (servers == null) {
                logger.info("Server requires hash update (Status: UPDATE)...")
                val newHash = downloadAndCalculateHash()
                
                if (newHash != null) {
                    currentHash = newHash
                    // Сохраняем новый хеш на будущее
                    try {
                        Files.writeString(cachedHashFile.toPath(), newHash)
                    } catch (e: IOException) {
                        logger.error("Failed to save hash cache", e)
                    }
                    
                    // Попытка 2: С новым хешем
                    servers = tryGetServers(newHash)
                }
            }
            servers ?: emptyList()
        } catch (e: Exception) {
            logger.error("Error fetching servers", e)
            emptyList()
        }
    }

    private fun tryGetServers(hash: String): List<SmartyServer>? {
        val jsonBody = """
            {
                "version": "3.6.2",
                "cheksum": "$hash",
                "format": "jar",
                "testModeKey": "false",
                "debug": "false"
            }
        """.trimIndent() // Хоть и кажется, что мы криворукие, раз cheksum написали. Не бейте. Это не наша вина.

        val formBody = FormBody.Builder()
            .add("action", "loader")
            .add("json", jsonBody)
            .build()

        val request = Request.Builder()
            .url(ServiceEndpoints.AUTH_LOGIN)
            .post(formBody)
            .header("User-Agent", "SMARTYlauncher/3.6.2")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()

            var rawJson = response.body?.string() ?: return emptyList()

            // Очистка ответа от мусора
            val jsonStart = rawJson.indexOf("{")
            if (jsonStart != -1) {
                rawJson = rawJson.substring(jsonStart)
            }

            return try {
                val smartyResponse = gson.fromJson(rawJson, SmartyResponse::class.java)

                if ("UPDATE" == smartyResponse.status) {
                    return null // null означает сигнал "нужен пересчет хеша"
                }

                if ("OK" == smartyResponse.status) {
                    return smartyResponse.servers
                }
                
                logger.warn("API Error Status: ${smartyResponse.status}")
                emptyList()
            } catch (e: JsonSyntaxException) {
                logger.error("Parsing error: $rawJson", e)
                emptyList()
            }
        }
    }

    private fun downloadAndCalculateHash(): String? {
        try {
            logger.info("Downloading official launcher to bypass protection...")
            
            val tempJar = Files.createTempFile("smarty_temp", ".jar")
            
            val request = Request.Builder()
                .url(officialJarUrl)
                .header("User-Agent", "SMARTYlauncher/3.6.2")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    logger.error("Download failed: HTTP ${response.code}")
                    return null
                }
                response.body?.byteStream()?.use { input ->
                    Files.copy(input, tempJar, StandardCopyOption.REPLACE_EXISTING)
                }
            }

            // Вычисляем MD5
            val digest = MessageDigest.getInstance("MD5")
            Files.newInputStream(tempJar).use { fis ->
                val buffer = ByteArray(1024)
                var bytesCount: Int
                while (fis.read(buffer).also { bytesCount = it } != -1) {
                    digest.update(buffer, 0, bytesCount)
                }
            }
            
            Files.deleteIfExists(tempJar)

            // Конвертация байтов в hex строку
            val bytes = digest.digest()
            val sb = StringBuilder()
            for (b in bytes) {
                sb.append(((b.toInt() and 0xff) + 0x100).toString(16).substring(1))
            }
            return sb.toString()

        } catch (e: Exception) {
            logger.error("Failed to calculate new hash", e)
            return null
        }
    }
}
