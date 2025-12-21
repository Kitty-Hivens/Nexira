package hivens.core.data

data class NewsItem(
    val id: Int = 0,
    val title: String = "",
    val description: String = "",
    val date: String = "",
    val imageUrl: String? = null
)
