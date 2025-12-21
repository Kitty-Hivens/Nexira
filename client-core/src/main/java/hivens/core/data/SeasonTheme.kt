package hivens.core.data

import com.google.gson.annotations.SerializedName
import java.util.Calendar

enum class SeasonTheme(val title: String) {
    @SerializedName("AUTO")
    AUTO("Автоматически"),

    @SerializedName("NONE")
    NONE("Отключено"),

    @SerializedName("WINTER")
    WINTER("Зима (Снег)"),

    @SerializedName("NEW_YEAR")
    NEW_YEAR("Новый год"),

    @SerializedName("SPRING")
    SPRING("Весна (Сакура)"),

    @SerializedName("SUMMER")
    SUMMER("Лето (Светлячки)"),

    @SerializedName("AUTUMN")
    AUTUMN("Осень (Листопад)");

    companion object {
        fun getCurrentSeasonalTheme(): SeasonTheme {
            val calendar = Calendar.getInstance()
            val month = calendar.get(Calendar.MONTH) // 0-based
            val day = calendar.get(Calendar.DAY_OF_MONTH)

            return when (month) {
                Calendar.DECEMBER -> if (day >= 20) NEW_YEAR else WINTER
                Calendar.JANUARY -> if (day <= 14) NEW_YEAR else WINTER
                Calendar.FEBRUARY -> WINTER

                Calendar.MARCH, Calendar.APRIL, Calendar.MAY -> SPRING

                Calendar.JUNE, Calendar.JULY, Calendar.AUGUST -> SUMMER

                Calendar.SEPTEMBER, Calendar.OCTOBER, Calendar.NOVEMBER -> AUTUMN

                else -> NONE
            }
        }
    }
}
