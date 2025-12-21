package hivens.core.data

import com.google.gson.annotations.SerializedName

enum class AuthStatus { // TODO: В будущем расширить
    @SerializedName("OK")
    OK,
    BAD_LOGIN,
    NEED_2FA,
    BANNED,
    INTERNAL_ERROR
}
