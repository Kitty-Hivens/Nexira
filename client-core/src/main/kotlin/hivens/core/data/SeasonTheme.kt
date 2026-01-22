package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.*

@Serializable
enum class SeasonTheme(val title: String) {
    @SerialName("AUTO")
    AUTO("Automatically"),

    @SerialName("NONE")
    NONE("Disabled"),

    @SerialName("WINTER")
    WINTER("Winter (Snow)"),

    @SerialName("NEW_YEAR")
    NEW_YEAR("New Year"),

    @SerialName("SPRING")
    SPRING("Spring (Sakura)"),

    @SerialName("SUMMER")
    SUMMER("Summer (Fireflies)"),

    @SerialName("AUTUMN")
    AUTUMN("Autumn (Leaf fall)");

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
