package id.homebase.core.ui.screens.card

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.SystemDriveConstants
import id.homebase.api.client.identity.PublicIdentityRepository
import id.homebase.api.client.identity.siteDataSectionData
import id.homebase.api.client.identity.siteDataSectionHeader
import id.homebase.api.common.OdinId
import id.homebase.core.image.HomebaseImageData
import kotlin.uuid.Uuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

private const val TAG = "CardSiteDefaults"

data class CardSiteDefaults(
    val design: String = CardDesign.BOARD,
    val tagLine: String? = null,
    val header: HomebaseImageData? = null,
)

internal fun cardDesignForTheme(themeId: String?): String = when (themeId) {
    "555" -> CardDesign.POSTER
    "777" -> CardDesign.COLLAGE
    "888" -> CardDesign.DOSSIER
    else -> CardDesign.BOARD
}

/** The homepage Theme attribute from a `sitedata.json` root; null when it has none. */
internal fun cardSiteDefaults(siteData: JsonArray): CardSiteDefaults? {
    val (file, data) = try {
        val file = siteData.siteDataSectionHeader("theme") ?: return null
        file to (siteData.siteDataSectionData("theme") ?: return null)
    } catch (e: IllegalArgumentException) {
        Logger.w(tag = TAG, throwable = e) { "unreadable theme section in sitedata.json" }
        return null
    }
    val fileId = file.string("fileId")?.let(Uuid::parseOrNull)
    val headerKey = data.string("headerImageKey")?.takeIf(String::isNotBlank)
    return CardSiteDefaults(
        design = data.string(CARD_DESIGN_KEY)?.takeIf { it in CardDesign.all }
            ?: cardDesignForTheme((data["themeId"] as? JsonPrimitive)?.content),
        tagLine = data.string("tagLine")?.takeIf(String::isNotBlank),
        header = if (fileId != null && headerKey != null) {
            HomebaseImageData(
                // The homepage drive allows anonymous reads, which every app token inherits as Read.
                driveId = SystemDriveConstants.homePageConfigDrive.alias,
                fileId = fileId,
                payloadKey = headerKey,
                isEncrypted = false,
                lastModified = ((file["fileMetadata"] as? JsonObject)?.get("updated") as? JsonPrimitive)?.longOrNull,
                keyHeader = KeyHeader.empty(),
            )
        } else {
            null
        },
    )
}

internal suspend fun PublicIdentityRepository.loadCardSiteDefaults(odinId: OdinId): CardSiteDefaults =
    fetchSiteData(odinId)?.let(::cardSiteDefaults) ?: CardSiteDefaults()
