package hivens.core.data

import com.google.gson.annotations.SerializedName

enum class AuthStatus { // TODO: Удалить / использовать неиспользованные статусы
    @SerializedName("OK")
    OK,

    @SerializedName("LOGIN") // Сервер возвращает это, если логин не найден
    BAD_LOGIN,

    @SerializedName("PASSWORD") // Сервер возвращает это, если пароль неверный
    BAD_PASSWORD,

    @SerializedName("SERVER") // Сервер возвращает это, если ID сервера неверный
    SERVER,

    NO_SERVER,
    INTERNAL_ERROR
}
