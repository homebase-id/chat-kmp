package id.homebase.core.ui.screens.card

import co.touchlab.kermit.Logger
import id.homebase.api.client.KeyHeader
import id.homebase.api.client.drives.HomebaseFile
import id.homebase.api.client.drives.QueryBatchRequest
import id.homebase.api.client.drives.QueryBatchResponse
import id.homebase.api.client.drives.QueryBatchResultOptionsRequest
import id.homebase.api.client.drives.QueryBatchSortField
import id.homebase.api.client.drives.QueryBatchSortOrder
import id.homebase.api.client.drives.isVisibleTo
import id.homebase.api.client.drives.query.FileQueryParams
import id.homebase.api.client.profile.ProfileVisibility
import id.homebase.api.serialization.OdinSystemSerializer
import id.homebase.core.feed.services.ChannelDefinition
import id.homebase.core.feed.services.FeedProtocol
import id.homebase.core.feed.services.PostContent
import id.homebase.core.image.HomebaseImageData
import id.homebase.core.image.thumbSizesFrom
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.uuid.Uuid
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private const val TAG = "CardPosts"

internal const val CARD_POST_LIMIT = 12
internal const val CARD_POST_IMAGE_MAX_EDGE = 400
private const val POST_PAGE_SIZE = 30

// Ceiling for a channel whose newest posts are mostly not public: 300 posts, then give up.
private const val MAX_POST_PAGES = 10
private const val DEFAULT_PAYLOAD_KEY = "dflt_key"
private const val PUBLIC_CHANNEL_SLUG = "public-posts"

/** A post for the card, its thumbnail not loaded yet. */
data class CardPostEntry(val post: CardPost, val image: HomebaseImageData?)

interface CardPostDrives {
    suspend fun channelIds(): List<Uuid>
    suspend fun query(channelId: Uuid, request: QueryBatchRequest): QueryBatchResponse
    suspend fun payloadText(channelId: Uuid, fileId: Uuid, key: String): String?
}

internal class CardChannel(val slug: String)

/** The newest [CARD_POST_LIMIT] public posts across the home-page channels. A channel that fails is logged and left out. */
suspend fun loadCardPosts(odinId: String, drives: CardPostDrives): List<CardPostEntry> = coroutineScope {
    mergeCardPosts(drives.channelIds().map { async { channelPosts(odinId, it, drives) } }.awaitAll())
}

internal fun mergeCardPosts(channels: List<List<CardPostEntry>>): List<CardPostEntry> =
    channels.flatten().sortedByDescending { it.post.date }.take(CARD_POST_LIMIT)

private suspend fun channelPosts(odinId: String, channelId: Uuid, drives: CardPostDrives): List<CardPostEntry> = try {
    val definition = drives.query(channelId, definitionQuery(channelId)).searchResults
        .firstOrNull { !it.isSoftDeleted() }
    val channel = definition
        ?.let { channelDefinition(channelId, it, drives) }
        ?.let { cardChannel(definition, it) }
    if (channel == null) emptyList() else pagePosts(odinId, channelId, channel, drives)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Logger.w(tag = TAG, throwable = e) { "channel $channelId skipped" }
    emptyList()
}

private suspend fun channelDefinition(channelId: Uuid, definition: HomebaseFile, drives: CardPostDrives): ChannelDefinition? {
    val json = definition.fileMetadata.appData.content?.ifBlank { null }
        ?: definition.fileMetadata.getPayloadDescriptor(DEFAULT_PAYLOAD_KEY)
            ?.let { drives.payloadText(channelId, definition.fileId, it.key) }
    val content = json?.let { runCatching { OdinSystemSerializer.deserialize<ChannelDefinition>(it) }.getOrNull() }
    if (content == null) Logger.w(tag = TAG) { "channel $channelId definition unreadable, skipped" }
    return content
}

internal fun cardChannel(definition: HomebaseFile, content: ChannelDefinition): CardChannel? {
    if (!content.showOnHomePage || !definition.isPublic()) return null
    return CardChannel(content.slug.ifBlank { PUBLIC_CHANNEL_SLUG })
}

private suspend fun pagePosts(odinId: String, channelId: Uuid, channel: CardChannel, drives: CardPostDrives): List<CardPostEntry> {
    val kept = mutableListOf<CardPostEntry>()
    var cursor: String? = null
    repeat(MAX_POST_PAGES) {
        val page = drives.query(channelId, postsQuery(cursor))
        page.searchResults.mapNotNullTo(kept) { cardPost(odinId, channel, it) }
        val next = page.cursorState
        if (kept.size >= CARD_POST_LIMIT || !page.hasMoreRows || next == null || next == cursor) return kept
        cursor = next
    }
    return kept
}

private fun HomebaseFile.isPublic() = serverMetadata.accessControlList.isVisibleTo(ProfileVisibility.ANONYMOUS)

internal fun cardPost(odinId: String, channel: CardChannel, file: HomebaseFile): CardPostEntry? {
    if (file.isSoftDeleted() || !file.isPublic()) return null
    val content = file.fileMetadata.appData.content
        ?.let { runCatching { OdinSystemSerializer.deserialize<PostContent>(it) }.getOrNull() }
        ?: return null
    val postKey = content.slug.ifBlank { content.id }.ifBlank { return null }
    val post = CardPost(
        id = file.fileId.toString(),
        href = cardPostHref(odinId, channel.slug, postKey),
        date = file.fileMetadata.appData.userDate?.takeIf { it > 0 } ?: file.fileMetadata.created.milliseconds,
        title = content.caption.trim().ifEmpty { null },
        excerpt = content.abstract?.trim()?.ifEmpty { null },
        minutes = content.readingTimeStats?.minutes,
        type = content.type.name.lowercase(),
    )
    return CardPostEntry(post, postImage(file, content))
}

internal fun cardPostHref(odinId: String, channelSlug: String, postKey: String): String =
    "https://$odinId/posts/$channelSlug/$postKey"

// Media on another file would need that file's own payload descriptor; the card leaves it out.
@OptIn(ExperimentalEncodingApi::class)
internal fun postImage(file: HomebaseFile, content: PostContent): HomebaseImageData? {
    val media = content.primaryMediaFile ?: return null
    if (media.type.substringBefore('/').lowercase() !in setOf("image", "video")) return null
    val mediaFileId = media.fileId?.ifBlank { null }
    if (mediaFileId != null && !mediaFileId.replace("-", "").equals(file.fileId.toHexString(), ignoreCase = true)) {
        return null
    }
    val payload = file.fileMetadata.getPayloadDescriptor(media.fileKey) ?: return null
    val iv = payload.iv?.let { runCatching { Base64.decode(it) }.getOrNull() }
    return HomebaseImageData(
        driveId = file.driveId,
        fileId = file.fileId,
        payloadKey = payload.key,
        availableThumbSizes = thumbSizesFrom(payload.thumbnails),
        lastModified = payload.lastModified,
        payloadContentType = payload.contentType,
        isEncrypted = iv != null,
        keyHeader = iv?.let { KeyHeader(iv = it, aesKey = file.keyHeader.aesKey) } ?: file.keyHeader,
    )
}

private fun definitionQuery(channelId: Uuid) = QueryBatchRequest(
    queryParams = FileQueryParams(
        fileType = listOf(FeedProtocol.ChannelDefinitionFileType),
        tagsMatchAtLeastOne = listOf(channelId),
    ),
    resultOptionsRequest = QueryBatchResultOptionsRequest(maxRecords = 1, includeMetadataHeader = true),
)

private fun postsQuery(cursor: String?) = QueryBatchRequest(
    queryParams = FileQueryParams(fileType = listOf(FeedProtocol.PostFileType)),
    resultOptionsRequest = QueryBatchResultOptionsRequest(
        cursorState = cursor,
        maxRecords = POST_PAGE_SIZE,
        includeMetadataHeader = true,
        ordering = QueryBatchSortOrder.NewestFirst,
        sorting = QueryBatchSortField.UserDate,
    ),
)
