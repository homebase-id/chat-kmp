package id.homebase.core.ui.screens.card

import co.touchlab.kermit.Logger
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileCard
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.image.ImageFormat
import id.homebase.api.image.ImageUtils
import id.homebase.core.image.CachedImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.screens.profile.ProfileEditUiState
import id.homebase.core.ui.screens.profile.ProfileField
import id.homebase.core.ui.screens.profile.profileNameValue
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "CardData"

internal const val CARD_IMAGE_MAX_EDGE = 1080
private const val CARD_IMAGE_JPEG_QUALITY = 85

private class CardSocialField(val field: ProfileField, val hosts: Set<String>)

private val cardSocialFields = mapOf(
    ProfileAttributeTypes.KEY_TWITTER to CardSocialField(ProfileField.TWITTER, setOf("twitter.com", "x.com")),
    ProfileAttributeTypes.KEY_FACEBOOK to CardSocialField(ProfileField.FACEBOOK, setOf("facebook.com", "fb.com")),
    ProfileAttributeTypes.KEY_INSTAGRAM to CardSocialField(ProfileField.INSTAGRAM, setOf("instagram.com")),
    ProfileAttributeTypes.KEY_TIKTOK to CardSocialField(ProfileField.TIKTOK, setOf("tiktok.com")),
    ProfileAttributeTypes.KEY_LINKEDIN to CardSocialField(ProfileField.LINKEDIN, setOf("linkedin.com")),
)

fun buildCardPayload(
    odinId: String,
    state: ProfileEditUiState,
    tier: ProfileVisibility,
    design: String,
    publicProfile: ProfileCard?,
    photoSrc: String?,
    headerSrc: String?,
    tagLine: String?,
): CardPayload {
    val values = state.visibleValues(tier)
    fun text(field: ProfileField) = values[field]?.trim()?.ifEmpty { null }
    return CardPayload(
        design = design,
        data = CardData(
            odinId = odinId,
            firstName = text(ProfileField.GIVEN_NAME),
            surName = text(ProfileField.SURNAME),
            displayName = profileNameValue(values)?.trim(),
            headline = tagLine?.trim()?.ifEmpty { null } ?: text(ProfileField.STATUS),
            // The public site's card shows the summary, never the full bio.
            bio = publicProfile?.bioSummary?.trim()?.ifEmpty { null },
            photo = photoSrc?.ifBlank { null }?.let(::CardImage),
            header = headerSrc?.ifBlank { null }?.let(::CardImage),
            links = publicProfile?.let(::cardLinks).orEmpty(),
            socials = cardSocialFields.mapNotNull { (type, social) ->
                text(social.field)?.let { socialUsername(type, it) }?.let { CardSocial(type = type, username = it) }
            },
        ),
    )
}

// /pub/profile repeats every social under `links`; the card takes socials from the profile fields instead.
private fun cardLinks(profile: ProfileCard): List<CardLink> {
    val socials = profile.sameAs.mapTo(HashSet()) { it.type to it.url }
    return profile.links
        .filterNot { (it.type to it.url) in socials }
        .mapNotNull { link ->
            val target = link.url?.trim()?.takeIf(::isWebUrl) ?: return@mapNotNull null
            link.type.trim().ifEmpty { target } to target
        }
        .mapIndexed { index, (text, target) -> CardLink(id = "${index + 1}", text = text, target = target) }
}

internal fun isWebUrl(url: String): Boolean {
    val scheme = url.substringBefore("://", missingDelimiterValue = "").lowercase()
    val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
    return (scheme == "http" || scheme == "https") && host.isNotEmpty() &&
        url.none { it.isWhitespace() || it.isISOControl() }
}

/** What the page appends to `https://<type>.com/`, from a handle, `@handle` or profile URL; null if unusable. */
internal fun socialUsername(type: String, raw: String): String? {
    val value = raw.trim()
    val handle = if ("://" in value || '/' in value) {
        val afterScheme = value.substringAfter("://")
        val host = afterScheme.substringBefore('/').lowercase()
            .removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
        if (host !in cardSocialFields[type]?.hosts.orEmpty()) return null
        val segments = afterScheme.substringAfter('/', missingDelimiterValue = "")
            .substringBefore('?').substringBefore('#')
            .split('/').filter { it.isNotEmpty() }
        if (type == ProfileAttributeTypes.KEY_LINKEDIN) segments.takeIf { it.firstOrNull() == "in" }?.getOrNull(1) else segments.firstOrNull()
    } else {
        value
    }
    return handle?.removePrefix("@")?.takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }
}

/**
 * Long edge ≤ [CARD_IMAGE_MAX_EDGE] JPEG as a `data:` URL; SVG passes through unchanged, since it
 * can't be re-encoded and `<img>` draws it at any size. Null (logged) if the bytes don't decode.
 */
@OptIn(ExperimentalEncodingApi::class)
suspend fun cardImageSrc(bytes: ByteArray): String? = withContext(Dispatchers.Default) {
    if (isSvg(bytes)) return@withContext "data:image/svg+xml;base64," + Base64.encode(bytes)
    try {
        val jpeg = ImageUtils.resizePreserveAspect(
            srcBytes = bytes,
            maxWidth = CARD_IMAGE_MAX_EDGE,
            maxHeight = CARD_IMAGE_MAX_EDGE,
            outputFormat = ImageFormat.JPEG,
            quality = CARD_IMAGE_JPEG_QUALITY,
        )
        "data:image/jpeg;base64," + Base64.encode(jpeg.bytes)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Logger.w(tag = TAG, throwable = e) { "card image encode failed for ${bytes.size}B" }
        null
    }
}

private const val SVG_SNIFF_BYTES = 1024

// An SVG may open with a BOM, an XML declaration, a comment or a doctype before its root element.
internal fun isSvg(bytes: ByteArray): Boolean {
    val head = bytes.copyOf(minOf(bytes.size, SVG_SNIFF_BYTES)).decodeToString().trimStart('﻿', ' ', '\t', '\r', '\n')
    return head.startsWith("<svg") || (head.startsWith("<?xml") || head.startsWith("<!")) && "<svg" in head
}

// The full payload is the fallback for images with no usable thumbnail, such as SVG.
suspend fun loadCardImageSrc(image: HomebaseImageData, imageLoader: HomebaseImageLoader): String? =
    fetchCardImage(image, "thumb") { imageLoader.loadThumbnail(image, ImageSize.THUMB_LARGE) }
        ?.let { cardImageSrc(it) }
        ?: fetchCardImage(image, "payload") { imageLoader.loadFullPayload(image) }?.let { cardImageSrc(it) }

private suspend fun fetchCardImage(
    image: HomebaseImageData,
    kind: String,
    load: suspend () -> CachedImage?,
): ByteArray? = try {
    load()?.bytes
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Logger.w(tag = TAG, throwable = e) { "card image $kind fetch failed for ${image.fileId}/${image.payloadKey}" }
    null
}
