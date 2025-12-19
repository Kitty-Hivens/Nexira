package hivens.core.api.model

data class ServerProfile(
    var name: String = "",
    var title: String? = null,
    var version: String = "",
    var ip: String = "",
    var port: Int = 0,
    var assetDir: String = "",
    var extraCheckSum: String? = null,
    var optionalModsData: Map<String, Any>? = null
) {
    constructor(name: String, version: String, ip: String, port: Int) : this(
        name = name,
        version = version,
        ip = ip,
        port = port,
        assetDir = name,
        title = "$name $version"
    )

    override fun toString(): String {
        return title ?: name
    }
}
