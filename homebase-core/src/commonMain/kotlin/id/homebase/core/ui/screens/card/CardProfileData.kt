package id.homebase.core.ui.screens.card

import co.touchlab.kermit.Logger
import id.homebase.api.client.profile.ProfileAttribute
import id.homebase.api.client.profile.ProfileAttributeTypes
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.image.ImageFormat
import id.homebase.api.image.ImageUtils
import id.homebase.core.image.CachedImage
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.HomebaseImageLoader
import id.homebase.core.image.ImageSize
import id.homebase.core.ui.screens.profile.ProfileField
import id.homebase.core.ui.screens.profile.profileNameValue
import id.homebase.core.ui.screens.profile.visibleBio
import id.homebase.core.ui.screens.profile.visibleLinks
import id.homebase.core.ui.screens.profile.visibleValues
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
    attributes: List<ProfileAttribute>,
    tier: ProfileVisibility,
    design: String,
    photoSrc: String?,
    headerSrc: String?,
    tagLine: String?,
    posts: List<CardPost> = emptyList(),
): CardPayload {
    val values = attributes.visibleValues(tier)
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
            bio = attributes.visibleBio(tier)?.trim()?.ifEmpty { null },
            photo = photoSrc?.ifBlank { null }?.let(::CardImage),
            header = headerSrc?.ifBlank { null }?.let(::CardImage),
            links = cardLinks(attributes.visibleLinks(tier)),
            socials = cardSocialFields.mapNotNull { (type, social) ->
                text(social.field)?.let { socialUsername(type, it) }?.let { CardSocial(type = type, username = it) }
            },
            posts = posts,
        ),
    )
}

// A link kept at both tiers is two records, so the vetted card would list it twice.
private fun cardLinks(links: List<ProfileAttribute>): List<CardLink> =
    links
        .mapNotNull { link ->
            val target = link.string(ProfileAttributeTypes.KEY_LINK_TARGET)?.trim()?.takeIf(::isWebUrl)
                ?: return@mapNotNull null
            (link.string(ProfileAttributeTypes.KEY_LINK_TEXT)?.trim()?.ifEmpty { null } ?: target) to target
        }
        .distinctBy { (_, target) -> target }
        .mapIndexed { index, (text, target) -> CardLink(id = "${index + 1}", text = text, target = target) }

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
 * Long edge ≤ [maxEdge] JPEG as a `data:` URL; SVG passes through unchanged, since it
 * can't be re-encoded and `<img>` draws it at any size. Null (logged) if the bytes don't decode.
 */
@OptIn(ExperimentalEncodingApi::class)
suspend fun cardImageSrc(bytes: ByteArray, maxEdge: Int = CARD_IMAGE_MAX_EDGE): String? = withContext(Dispatchers.Default) {
    if (isSvg(bytes)) return@withContext "data:image/svg+xml;base64," + Base64.encode(bytes)
    try {
        val jpeg = ImageUtils.resizePreserveAspect(
            srcBytes = bytes,
            maxWidth = maxEdge,
            maxHeight = maxEdge,
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

// The full payload is the fallback for images with no usable thumbnail, such as SVG; a video's payload is never an image.
suspend fun loadCardImageSrc(
    image: HomebaseImageData,
    imageLoader: HomebaseImageLoader,
    maxEdge: Int = CARD_IMAGE_MAX_EDGE,
): String? =
    fetchCardImage(image, "thumb") { imageLoader.loadThumbnail(image, ImageSize(maxEdge, maxEdge)) }
        ?.let { cardImageSrc(it, maxEdge) }
        ?: if (image.effectiveContentType?.startsWith("video/", ignoreCase = true) == true) {
            null
        } else {
            fetchCardImage(image, "payload") { imageLoader.loadFullPayload(image) }?.let { cardImageSrc(it, maxEdge) }
        }

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
