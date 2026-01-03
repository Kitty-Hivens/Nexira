package hivens.launcher

import hivens.core.data.SessionData
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.*

class CredentialsManager(
    workDir: Path,
    private val json: Json
) {
    private val log = LoggerFactory.getLogger(CredentialsManager::class.java)
    private val credentialsFile = workDir.resolve("credentials.json")

    // DTO для хранения только нужных полей (безопаснее, чем дампить весь SessionData)
    @Serializable
    private data class SavedCredentials(
        val username: String,
        val accessToken: String,
        val uuid: String,
        val uid: String? = null,
        // Пароль хранить в открытом виде плохо, но для совместимости с SessionData сохраняем. TODO
        // В идеале здесь должно быть шифрование или использование OS Keyring.
        // Для простоты кодируем в Base64, чтобы он не лежал plain-text'ом (защита "от честных людей").
        val savedPasswordBase64: String? = null
    )

    fun save(session: SessionData) {
        // Сохраняем, только если пользователь просил или сессия валидна
        if (session.accessToken.isBlank()) return

        try {
            val passwordEncoded = session.cachedPassword?.let {
                Base64.getEncoder().encodeToString(it.toByteArray())
            }

            val data = SavedCredentials(
                username = session.playerName,
                accessToken = session.accessToken,
                uuid = session.uuid,
                uid = session.uid,
                savedPasswordBase64 = passwordEncoded
            )

            if (credentialsFile.parent != null) Files.createDirectories(credentialsFile.parent)

            val text = json.encodeToString(data)
            Files.writeString(credentialsFile, text)

        } catch (e: IOException) {
            log.error("Не удалось сохранить данные входа", e)
        }
    }

    fun load(): SessionData? {
        if (!Files.exists(credentialsFile)) return null

        return try {
            val text = Files.readString(credentialsFile)
            val data = json.decodeFromString<SavedCredentials>(text)

            val passwordDecoded = data.savedPasswordBase64?.let {
                String(Base64.getDecoder().decode(it))
            }

            // Восстанавливаем SessionData.
            // Остальные поля (manifest, balance) подтянутся при обновлении профиля.
            SessionData(
                playerName = data.username,
                accessToken = data.accessToken,
                uuid = data.uuid,
                uid = data.uid ?: "",
                cachedPassword = passwordDecoded,
                status = null // Статус неизвестен до повторной проверки
            )
        } catch (e: Exception) {
            log.error("Ошибка чтения файла credentials.json", e)
            null
        }
    }

    fun clear() {
        try {
            Files.deleteIfExists(credentialsFile)
        } catch (e: IOException) {
            log.warn("Не удалось удалить файл credentials", e)
        }
    }
}
