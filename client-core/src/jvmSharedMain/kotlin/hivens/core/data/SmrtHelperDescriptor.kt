package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Out-of-band descriptor that pins, per Minecraft version, which
 * open-smrt-network release jar replaces the upstream Smarty coremod on
 * a raw SmartyCraft server sync. Lives in the open-smrt-network repo
 * (`meta/smrt-helper.json` on its `main` branch) so the pinned release
 * can move without cutting a Nexira launcher release -- same out-of-band
 * pattern as [UpdateChannelMeta].
 *
 * Fetched remote-only; an absent file, parse failure, or network error
 * means "no helper available", and the sync falls back to the upstream
 * Smarty jar. The mirror packs do not consult this -- they already carry
 * the replacement inline in their smrt manifest.
 */
@Serializable
data class SmrtHelperDescriptor(
    @SerialName("schema_version") val schemaVersion: Int = 1,
    val variants: List<SmrtHelperVariant> = emptyList(),
) {
    /**
     * The variant whose [SmrtHelperVariant.mcPrefix] is the longest prefix of
     * [mcVersion], or null if none match. Longest-prefix so a "1.12.2" entry
     * wins over a broader "1.12" entry when both are present.
     */
    fun variantFor(mcVersion: String): SmrtHelperVariant? =
        variants
            .filter { mcVersion.startsWith(it.mcPrefix) }
            .maxByOrNull { it.mcPrefix.length }
}

/**
 * One Minecraft-version variant of the open-smrt-network helper.
 *
 * [smartyNames] are the manifest filenames (or `*`-globs) that identify the
 * upstream Smarty jar to strip for this version; the default catches the
 * common `Smarty-<version>.jar`. The jar is downloaded from the
 * open-smrt-network release tagged [tag], asset [asset], and must hash to
 * [sha256] (lowercase hex SHA-256) before it is trusted.
 */
@Serializable
data class SmrtHelperVariant(
    @SerialName("mc_prefix") val mcPrefix: String,
    val tag: String,
    val asset: String,
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long = 0,
    @SerialName("smarty_names") val smartyNames: List<String> = listOf("Smarty*.jar"),
)
